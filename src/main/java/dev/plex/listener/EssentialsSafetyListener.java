package dev.plex.listener;

import com.earth2me.essentials.User;
import com.earth2me.essentials.messaging.IMessageRecipient;
import com.earth2me.essentials.utils.FormatUtil;
import dev.plex.ECCModule;
import dev.plex.ecc.safety.ChatSafety;
import dev.plex.ecc.safety.Line;
import dev.plex.ecc.safety.LogReading;
import net.ess3.api.IEssentials;
import net.ess3.api.IUser;
import net.ess3.api.events.PrivateMessagePreSendEvent;
import net.essentialsx.api.v2.events.UserMailEvent;
import net.essentialsx.api.v2.services.mail.MailMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * EssentialsX private messages (/msg and its aliases, /r), mail and /me, each sent to ECC-Server with its text as the
 * player typed it. Each is stopped before EssentialsX delivers it, and delivered by EssentialsX itself once ECC-Server
 * lets it through: the message is sent again through EssentialsX's own API, which applies its format, ignore lists,
 * AFK replies, social spy and reply target as the command would have, and this listener lets that second pass through.
 */
public class EssentialsSafetyListener implements Listener
{
    // EssentialsX's commands whose text the message events carry formatted.
    private static final Set<String> TYPED_COMMANDS = Set.of("msg", "r", "mail");
    // The longest gap between two mail events of one /mail sendall: EssentialsX loads each user it mails from disk.
    private static final long MAILING_ALL_NANOS = TimeUnit.SECONDS.toNanos(60);
    // The target of a mail to every user, as the training pipeline reads /mail sendall.
    private static final String ALL_USERS = "*";

    private final ECCModule module;
    private final ChatSafety safety;
    private final IEssentials essentials;
    private final ThreadLocal<Typed> typed = new ThreadLocal<>();
    // When each player's checked /mail sendall last ran or mailed someone.
    private final Map<UUID, Long> mailingAll = new ConcurrentHashMap<>();
    // Set on the server thread while a checked message is handed back to EssentialsX.
    private boolean delivering;

    // Takes the enabled EssentialsX plugin, so its types load only when it is present.
    public EssentialsSafetyListener(ECCModule module, ChatSafety safety, Plugin essentials)
    {
        this.module = module;
        this.safety = safety;
        this.essentials = (IEssentials) essentials;
    }

    // The EssentialsX command a player is running on this thread, kept from its preprocess event until the message
    // event it fires. EssentialsX formats the message before that event (colour codes, and links broken apart for a
    // player without essentials.msg.url), so the line's text is taken from the command as typed, which the training
    // pipeline read from its log line.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        if (parts.length == 2 && Bukkit.getCommandMap().getCommand(parts[0].substring(1)) instanceof PluginCommand plugin
                && plugin.getPlugin() == essentials && TYPED_COMMANDS.contains(plugin.getName()))
        {
            typed.set(new Typed(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick(), plugin.getName(), parts[1],
                    safety.commandAuthor(event)));
        }
        else
        {
            typed.remove();
        }
    }

    // /msg and /r: EssentialsX has resolved the recipient (by name, or the reply target) when this fires.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrivateMessage(PrivateMessagePreSendEvent event)
    {
        if (delivering || !(event.getSender() instanceof IUser sender))
        {
            return;
        }
        Player player = sender.getBase();
        IMessageRecipient from = event.getSender();
        IMessageRecipient recipient = event.getRecipient();
        String message = event.getMessage();
        // ingest.command: /msg's text is what follows the recipient's name; /r's is all its arguments.
        Typed command = typedArguments(player, "msg", "r");
        String text = message;
        if (command != null && command.command().equals("r"))
        {
            text = command.arguments();
        }
        else if (command != null)
        {
            int space = command.arguments().indexOf(' ');
            text = space < 0 ? "" : command.arguments().substring(space + 1);
        }
        UUID recipientUuid = recipient instanceof IUser target ? target.getUUID() : null;
        Player author = command != null ? command.author() : player;
        Line line = Line.to(author, ChatSafety.MSG, recipientUuid, recipient.getName(), text);
        if (!safety.check(author, line, () -> onServerThread(() -> from.sendMessage(recipient, message))))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMail(UserMailEvent event)
    {
        MailMessage mail = event.getMessage();
        Player player = mail.getSenderUUID() == null ? null : Bukkit.getPlayer(mail.getSenderUUID());
        if (delivering || player == null || !Bukkit.isPrimaryThread() && mailingAll(player.getUniqueId()))
        {
            return;
        }
        IUser recipient = event.getRecipient();
        User sender = essentials.getUser(player);
        // "/mail send <name> <text>" and "/mail sendtemp <name> <time> <text>". Other ways to send mail keep the
        // event's text.
        Typed command = typedArguments(player, "mail");
        String typedText = command == null ? null : mailText(command.arguments());
        Player author = command != null ? command.author() : player;
        Line line = Line.to(author, ChatSafety.MAIL, recipient.getUUID(), recipient.getName(),
                typedText != null ? typedText : mail.getMessage());
        String text = mail.getMessage();
        long expire = mail.getTimeExpire();
        if (!safety.check(author, line, () -> onServerThread(() -> recipient.sendMail(sender, text, expire))))
        {
            event.setCancelled(true);
        }
    }

    // The text of "/mail send <name> <text>" or "/mail sendtemp <name> <time> <text>" as typed, else null.
    @Nullable
    private static String mailText(String arguments)
    {
        String[] parts = arguments.split(" ", 4);
        String subcommand = parts[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("send") && parts.length >= 3)
        {
            return arguments.split(" ", 3)[2];
        }
        return subcommand.equals("sendtemp") && parts.length == 4 ? parts[3] : null;
    }

    // "/mail sendall <text>" and "/mail sendtempall <time> <text>": EssentialsX mails every user it knows on a thread
    // of its own, one mail event each. The command is one line to ALL_USERS, checked before it runs; once it runs, its
    // mail events go through.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMailAll(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        if (parts.length < 2 || !(Bukkit.getCommandMap().getCommand(parts[0].substring(1)) instanceof PluginCommand plugin)
                || plugin.getPlugin() != essentials || !plugin.getName().equals("mail"))
        {
            return;
        }
        String[] arguments = parts[1].split(" ", 3);
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        String text = subcommand.equals("sendall") && arguments.length >= 2 ? parts[1].split(" ", 2)[1]
                : subcommand.equals("sendtempall") && arguments.length == 3 ? arguments[2] : null;
        if (text == null || LogReading.isBlank(text))
        {
            return;
        }
        Player player = event.getPlayer();
        Player author = safety.commandAuthor(event);
        String command = event.getMessage().substring(1);
        Line line = Line.to(author, ChatSafety.MAIL, null, ALL_USERS, text);
        UUID sender = player.getUniqueId();
        if (safety.check(author, line, () ->
        {
            mailingAll.put(sender, System.nanoTime());
            Commands.perform(module, player, command, () ->
                    module.getLogger().info("Not running {}'s checked command, since they left: /{}", player.getName(),
                            command));
        }))
        {
            mailingAll.put(sender, System.nanoTime());
            return;
        }
        event.setCancelled(true);
    }

    // True while the player's checked /mail sendall runs: its mail events come at most MAILING_ALL_NANOS apart.
    private boolean mailingAll(UUID sender)
    {
        long now = System.nanoTime();
        Long last = mailingAll.computeIfPresent(sender, (key, at) -> now - at <= MAILING_ALL_NANOS ? now : null);
        return last != null;
    }

    // /me runs again as its author's command; a player who has left by then has their line shown as EssentialsX's
    // /me shows it.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMe(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()
                || !isEssentialsMe(Bukkit.getCommandMap().getCommand(parts[0].substring(1))))
        {
            return;
        }
        Player player = event.getPlayer();
        User user = essentials.getUser(player);
        String shown = FormatUtil.formatMessage(user, "essentials.chat", parts[1]);
        Player author = safety.commandAuthor(event);
        Line line = Line.of(author, ChatSafety.ME, parts[1]);
        String command = event.getMessage().substring(1);
        if (!safety.check(author, line, () -> Commands.perform(module, player, command,
                () -> essentials.getOnlineUsers().forEach(online -> online.sendTl("action", user.getDisplayName(), shown)))))
        {
            event.setCancelled(true);
        }
    }

    // Runs the delivery on the server thread with this listener letting EssentialsX's events through.
    private void onServerThread(Runnable delivery)
    {
        module.ownTask(Bukkit.getGlobalRegionScheduler().run(module.plugin(), task ->
        {
            delivering = true;
            try
            {
                delivery.run();
            }
            finally
            {
                delivering = false;
            }
        }));
    }

    // The command this player is running now on this thread when it is one of these EssentialsX commands, else null:
    // a message event outside such a command, such as an AFK reply, keeps the event's text.
    @Nullable
    private Typed typedArguments(Player player, String... commands)
    {
        Typed command = typed.get();
        if (command == null || !command.player().equals(player.getUniqueId()) || command.tick() != Bukkit.getCurrentTick()
                || !Arrays.asList(commands).contains(command.command()))
        {
            return null;
        }
        return command;
    }

    // EssentialsX's /me under any of its aliases (me, eme, action, describe and their prefixed forms).
    private boolean isEssentialsMe(@Nullable Command command)
    {
        return command instanceof PluginCommand plugin && plugin.getPlugin() == essentials
                && plugin.getName().equals("me");
    }

    // author: who the line belongs to, the player whose /sudo forced the command or the player who ran it.
    private record Typed(UUID player, int tick, String command, String arguments, Player author)
    {
    }
}
