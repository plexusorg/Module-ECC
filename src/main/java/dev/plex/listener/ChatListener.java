package dev.plex.listener;

import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine.Kind;
import dev.plex.ecc.nush.Quarantine.LogEntry;
import dev.plex.ecc.nush.RaidDetector.Signal;
import dev.plex.ecc.safety.ChatSafety;
import dev.plex.ecc.safety.Delivery;
import dev.plex.ecc.safety.Line;
import dev.plex.ecc.safety.LogReading;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public chat. A quarantined player's line reaches only its sender (NUSH), and goes to ECC-Server as a muted line,
 * whose verdict changes nothing: no notice, no delivery on release. Any other line waits on the async chat thread for
 * ECC-Server's verdict; a held line is cancelled, and a release later sends it to the players who would have seen it,
 * rendered as Paper would have rendered it for each, and to DiscordSRV when it runs.
 */
public class ChatListener implements Listener
{
    private final ECCModule module;

    public ChatListener(ECCModule module)
    {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event)
    {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        module.raidDetector().record(Signal.CHAT, uuid);
        if (event.isCancelled())
        {
            return;
        }
        boolean quarantined = module.isEnabled() && module.quarantine().isRestricted(uuid);
        ChatSafety safety = module.chatSafety();
        if (safety == null && !quarantined)
        {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        // A plugin's Player#chat on the server thread fires this event there, where it must not wait; /sudo forces
        // a line that way, and the line is its issuer's.
        Player author = safety == null || event.isAsynchronous() ? player : safety.chatAuthor(player);
        if (quarantined)
        {
            if (safety != null && !text.isBlank())
            {
                // Scored and judged as a line nobody else saw; its verdict changes nothing.
                safety.send(Line.of(author, ChatSafety.MUTED, text));
            }
            shadow(event, player, uuid, text);
            return;
        }
        if (text.isBlank())
        {
            return;
        }
        Line line = Line.shown(author, ChatSafety.CHAT, shown(event, player, text), text);
        Delivery late = late(event, text);
        boolean deliver = event.isAsynchronous() ? safety.await(author, line, late) : safety.check(author, line, late);
        if (!deliver)
        {
            // Cancelling also keeps the held line from every MONITOR consumer, and from DiscordSRV (DiscordSrvHook).
            event.setCancelled(true);
        }
    }

    // The speaker's name as the console line shows it, with rank tags and nickname: Paper logs the line as the event's
    // renderer renders it for the console. A renderer that writes neither " » " nor "<name> " gives the display name.
    private static String shown(AsyncChatEvent event, Player player, String text)
    {
        Component console = event.renderer().render(player, player.displayName(), event.message(),
                Bukkit.getConsoleSender());
        String name = LogReading.shownName(PlainTextComponentSerializer.plainText().serialize(console), text);
        return name != null ? name : PlainTextComponentSerializer.plainText().serialize(player.displayName());
    }

    // NUSH: the line reaches only its sender, and staff see it in the feed.
    private void shadow(AsyncChatEvent event, Player player, UUID uuid, String text)
    {
        // Cancelling keeps the raw message from every MONITOR consumer, and from DiscordSRV (DiscordSrvHook).
        event.setCancelled(true);
        Component rendered = event.renderer().render(player, player.displayName(), event.message(), player);
        player.sendMessage(rendered);
        module.quarantine().record(uuid, new LogEntry(Kind.CHAT, Instant.now(), text));
        module.feed().line(player, Kind.CHAT, rendered, text);
    }

    // What Paper and DiscordSRV would have done with the line: render it for each viewer the event had and send it to
    // those still here, then hand it to DiscordSRV. The renderer and viewers are taken now, after every earlier
    // listener changed them.
    private Delivery late(AsyncChatEvent event, String text)
    {
        Player source = event.getPlayer();
        Component displayName = source.displayName();
        Component message = event.message();
        ChatRenderer renderer = event.renderer();
        List<Audience> viewers = List.copyOf(event.viewers());
        DiscordSrvHook discord = module.discord();
        return () ->
        {
            for (Audience viewer : viewers)
            {
                if (viewer instanceof Player player && !player.isOnline())
                {
                    continue;
                }
                viewer.sendMessage(renderer.render(source, displayName, message, viewer));
            }
            if (discord != null)
            {
                discord.forward(source, text);
            }
        };
    }
}
