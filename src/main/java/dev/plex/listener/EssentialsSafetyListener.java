package dev.plex.listener;

import com.earth2me.essentials.User;
import com.earth2me.essentials.adventure.AdventureUtil;
import com.earth2me.essentials.messaging.IMessageRecipient;
import com.earth2me.essentials.utils.FormatUtil;
import dev.plex.ecc.safety.ChatSafety;
import net.ess3.api.IEssentials;
import net.ess3.api.IUser;
import net.ess3.api.events.PrivateMessagePreSendEvent;
import net.essentialsx.api.v2.events.UserMailEvent;
import net.essentialsx.api.v2.services.mail.MailMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Captures EssentialsX private messages (/msg, /tell, /w, /r), mail and /me for chat safety. A shadow-muted player
 * sees their own private message and /me line as EssentialsX would show them; nobody else receives them. Their mail
 * is not delivered, and EssentialsX still tells them it was sent.
 */
public class EssentialsSafetyListener implements Listener
{
    private static final Set<String> ME_COMMANDS = Set.of("me", "eme");

    private final ChatSafety safety;
    private final IEssentials essentials;

    // Takes the enabled EssentialsX plugin, so its types load only when it is present.
    public EssentialsSafetyListener(ChatSafety safety, Plugin essentials)
    {
        this.safety = safety;
        this.essentials = (IEssentials) essentials;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrivateMessage(PrivateMessagePreSendEvent event)
    {
        IMessageRecipient sender = event.getSender();
        if (!(sender instanceof IUser user))
        {
            return;
        }
        Player player = user.getBase();
        IMessageRecipient recipient = event.getRecipient();
        UUID recipientUuid = recipient instanceof IUser target ? target.getUUID() : null;
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            // What EssentialsX shows the sender of a delivered message, and the reply target it sets.
            event.setCancelled(true);
            sender.sendTl("msgFormat", AdventureUtil.parsed(sender.tlSender("meSender")), recipient.getDisplayName(),
                    event.getMessage());
            sender.setReplyRecipient(recipient);
        }
        safety.capture(player, ChatSafety.MSG,
                ChatSafety.privateConversation(player.getUniqueId(), recipientUuid, recipient.getName()),
                recipientUuid, recipient.getName(), event.getMessage(), shadowed);
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
                recipient.getUUID(), recipient.getName(), mail.getMessage(), shadowed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMe(PlayerCommandPreprocessEvent event)
    {
        String[] parts = event.getMessage().split(" ", 2);
        String label = parts[0].substring(1).toLowerCase(Locale.ROOT);
        if (parts.length < 2 || parts[1].isBlank() || !ME_COMMANDS.contains(label.substring(label.indexOf(':') + 1)))
        {
            return;
        }
        Player player = event.getPlayer();
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            // The line EssentialsX's /me shows, sent to its author alone.
            event.setCancelled(true);
            User user = essentials.getUser(player);
            user.sendTl("action", user.getDisplayName(), FormatUtil.formatMessage(user, "essentials.chat", parts[1]));
        }
        safety.capture(player, ChatSafety.ME, ChatSafety.PUBLIC_CONVERSATION, null, null, parts[1], shadowed);
    }
}
