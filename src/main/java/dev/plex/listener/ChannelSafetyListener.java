package dev.plex.listener;

import dev.plex.api.event.ChatChannelMessageEvent;
import dev.plex.ecc.safety.ChatSafety;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Captures every module chat channel, such as guild chat, for chat safety. Cancelling keeps a shadow-muted player's
 * message from the rest of the conversation; the firing module shows it to the sender.
 */
public class ChannelSafetyListener implements Listener
{
    private final ChatSafety safety;

    public ChannelSafetyListener(ChatSafety safety)
    {
        this.safety = safety;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChannelMessage(ChatChannelMessageEvent event)
    {
        Player player = event.getPlayer();
        boolean shadowed = safety.isShadowMuted(player.getUniqueId());
        if (shadowed)
        {
            event.setCancelled(true);
        }
        String channel = event.getChannel().asString();
        safety.capture(player, channel, channel + "/" + event.getConversation(), null, null,
                PlainTextComponentSerializer.plainText().serialize(event.getMessage()), shadowed);
    }
}
