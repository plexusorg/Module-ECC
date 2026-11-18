package dev.plex.listener;

import dev.plex.api.event.ChatChannelMessageEvent;
import dev.plex.ecc.safety.ChatSafety;
import dev.plex.ecc.safety.Line;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Every module chat channel: guild chat goes to ECC-Server as {@code guild}, any other channel under its key. A message
 * fired off the server thread waits for ECC-Server's verdict and is cancelled when held; one fired on the server thread
 * cannot wait, so it is cancelled and checked. The firing module shows a cancelled message to its sender, and delivers
 * it to the rest of its conversation when chat safety delivers it late: after a deliver verdict on the server thread,
 * or after a release.
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
        String channel = ChatSafety.moduleChannel(event.getChannel());
        String text = PlainTextComponentSerializer.plainText().serialize(event.getMessage());
        if (text.isBlank())
        {
            return;
        }
        // Module-Guilds' console line names the sender by account: "[GUILD - name:uuid] <player> <message>".
        Line line = ChatSafety.GUILD.equals(channel) ? Line.shown(player, channel, player.getName(), text)
                : Line.of(player, channel, text);
        // Cancelled while it is checked: the event delivers late only while cancelled, and a late delivery can start on
        // another thread before the check returns.
        event.setCancelled(true);
        boolean delivered = event.isAsynchronous() ? safety.await(player, line, event::deliverLate)
                : safety.check(player, line, event::deliverLate);
        event.setCancelled(!delivered);
    }
}
