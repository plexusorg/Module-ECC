package dev.plex.listener;

import com.earth2me.essentials.User;
import com.earth2me.essentials.adventure.AdventureUtil;
import com.earth2me.essentials.messaging.IMessageRecipient;
import com.earth2me.essentials.utils.FormatUtil;
import dev.plex.ecc.safety.ChatSafety;
import net.ess3.api.IEssentials;
import net.ess3.api.IUser;
import net.ess3.api.events.PrivateMessagePreSendEvent;
import net.ess3.api.events.PrivateMessageSentEvent;
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

import java.util.UUID;

/**
 * Captures EssentialsX private messages (/msg and its aliases, /r), mail and /me for chat safety, as plain text.
 * A shadow-muted player sees their own private message and /me line as EssentialsX would show them; nobody else
 * receives them. Their mail is not delivered, and EssentialsX still tells them it was sent.
 */
public class EssentialsSafetyListener implements Listener
{
    private final ChatSafety safety;
    private final IEssentials essentials;

    // Takes the enabled EssentialsX plugin, so its types load only when it is present.
    public EssentialsSafetyListener(ChatSafety safety, Plugin essentials)
    {
        this.safety = safety;
        this.essentials = (IEssentials) essentials;
    }

    // A shadow-muted sender's message stops here; EssentialsX delivers nothing and fires no sent event for it.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrivateMessageSend(PrivateMessagePreSendEvent event)
    {
        if (!(event.getSender() instanceof IUser sender) || !safety.isShadowMuted(sender.getBase().getUniqueId()))
        {
            return;
        }
        event.setCancelled(true);
        showAsSent(essentials.getUser(sender.getBase()), event.getRecipient(), event.getMessage());
        capturePrivate(sender.getBase(), event.getRecipient(), event.getMessage(), true);
    }

    // Shows the sender what EssentialsX would show for this message (SimpleMessageRecipient#sendMessage and
    // #onReceiveMessage), and sets the reply target it would set, without delivering anything.
    private void showAsSent(User sender, IMessageRecipient recipient, String message)
    {
        if (sender.isMuted())
        {
            sender.notifyMuted();
            return;
        }
        if (!recipient.isReachable())
        {
            sender.sendTl("recentlyForeverAlone", recipient.getDisplayName());
            return;
        }
        if (recipient instanceof User target)
        {
            if (target.isIgnoreMsg() && !sender.isAuthorized("essentials.msgtoggle.bypass"))
            {
                sender.sendTl("msgIgnore", recipient.getDisplayName());
                return;
            }
            if (target.isIgnoredPlayer(sender))
            {
                return;
            }
            if (target.isAfk())
            {
                if (target.getAfkMessage() != null)
                {
                    sender.sendTl("userAFKWithMessage", recipient.getDisplayName(), target.getAfkMessage());
                }
                else
                {
                    sender.sendTl("userAFK", recipient.getDisplayName());
                }
            }
        }
        sender.sendTl("msgFormat", AdventureUtil.parsed(sender.tlSender("meSender")), recipient.getDisplayName(), message);
        sender.setReplyRecipient(recipient);
    }

    // A delivered message: EssentialsX resolved the recipient (/msg by name, /r by reply target) and fires this only
    // after delivery.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrivateMessageSent(PrivateMessageSentEvent event)
    {
        if (event.getSender() instanceof IUser user && event.getResponse().isSuccess())
        {
            capturePrivate(user.getBase(), event.getRecipient(), event.getMessage(), false);
        }
    }

    private void capturePrivate(Player player, IMessageRecipient recipient, String message, boolean shadowed)
    {
        UUID recipientUuid = recipient instanceof IUser target ? target.getUUID() : null;
        safety.capture(player, ChatSafety.MSG,
                ChatSafety.privateConversation(player.getUniqueId(), recipientUuid, recipient.getName()),
                recipientUuid, recipient.getName(), FormatUtil.stripFormat(message), shadowed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMail(UserMailEvent event)
    {
        MailMessage mail = event.getMessage();
        Player player = mail.getSenderUUID() == null ? null : Bukkit.getPlayer(mail.getSenderUUID());
        if (player == null)
        {
            return;
        }
        IUser recipient = event.getRecipient();
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            event.setCancelled(true);
        }
        safety.capture(player, ChatSafety.MAIL,
                ChatSafety.privateConversation(player.getUniqueId(), recipient.getUUID(), recipient.getName()),
                recipient.getUUID(), recipient.getName(), FormatUtil.stripFormat(mail.getMessage()), shadowed);
    }

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
        // The text as EssentialsX shows it: & codes the player may use become colour, which is then removed.
        String shown = FormatUtil.formatMessage(user, "essentials.chat", parts[1]);
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            // The line EssentialsX's /me shows, sent to its author alone.
            event.setCancelled(true);
            user.sendTl("action", user.getDisplayName(), shown);
        }
        safety.capture(player, ChatSafety.ME, ChatSafety.PUBLIC_CONVERSATION, null, null,
                FormatUtil.stripFormat(shown), shadowed);
    }

    // EssentialsX's /me under any of its aliases (me, eme, action, describe and their prefixed forms).
    private boolean isEssentialsMe(@Nullable Command command)
    {
        return command instanceof PluginCommand plugin && plugin.getPlugin() == essentials
                && plugin.getName().equals("me");
    }
}
