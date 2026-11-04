package dev.plex.ecc.safety;

import dev.plex.ECCModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows each action chat safety carries out, as it happens, in the console and to online players with
 * {@link ChatSafety#ALERTS_PERMISSION} who have not turned alerts off. As with the NUSH staff feed, the audience is
 * kept from joins and quits, so sending never reads Bukkit's live player collection, and turning alerts off lasts
 * until the player leaves. Player names and text are shown as plain text.
 */
final class Alerts implements Listener
{
    private static final String HELD = "held";
    private static final int TEXT_LIMIT = 256;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ECCModule module;
    private final Set<UUID> audience = ConcurrentHashMap.newKeySet();
    private final Set<UUID> off = ConcurrentHashMap.newKeySet();

    Alerts(ECCModule module)
    {
        this.module = module;
    }

    // Registers for joins and quits, then adds the staff already online, each on their own scheduler so a concurrent
    // quit cannot leave a stale recipient.
    void start()
    {
        module.registerListener(this);
        for (String name : module.api().players().onlineNames())
        {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null)
            {
                module.ownTask(player.getScheduler().run(module.plugin(), task -> joined(player), null));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event)
    {
        joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event)
    {
        UUID uuid = event.getPlayer().getUniqueId();
        audience.remove(uuid);
        off.remove(uuid);
    }

    private void joined(Player player)
    {
        if (player.hasPermission(ChatSafety.ALERTS_PERMISSION))
        {
            audience.add(player.getUniqueId());
        }
    }

    // True when the player's alerts are now off. Runs on the player's own thread, after the caller checked the
    // permission: a player granted it since joining is added and sees alerts from now on.
    boolean toggle(Player player)
    {
        UUID uuid = player.getUniqueId();
        if (audience.add(uuid))
        {
            off.remove(uuid);
            return false;
        }
        if (off.add(uuid))
        {
            return true;
        }
        off.remove(uuid);
        return false;
    }

    // Shows the alert of an action carried out. Runs on the action loop's thread; messaging needs no scheduler.
    void show(Actions.Action action)
    {
        Component alert = render(action);
        module.api().logging().info("{0}",
                MINI_MESSAGE.escapeTags(PlainTextComponentSerializer.plainText().serialize(alert)));
        for (UUID uuid : audience)
        {
            if (off.contains(uuid))
            {
                continue;
            }
            Player staff = Bukkit.getPlayer(uuid);
            if (staff != null)
            {
                staff.sendMessage(alert);
            }
        }
    }

    private Component render(Actions.Action action)
    {
        Actions.Why why = action.why();
        TagResolver player = Placeholder.unparsed("player", action.name());
        return switch (action.type())
        {
            case MUTE -> module.messageComponent("chatSafetyMuteAlert", player,
                    Placeholder.unparsed("channel", channel(why.channel())),
                    Placeholder.unparsed("text", shortened(why.text())));
            case UNMUTE -> module.messageComponent("chatSafetyUnmuteAlert", player,
                    Placeholder.unparsed("cause", unmuteCause(why.cause())));
            case BAN -> ban(action, player);
            case NOTICE -> HELD.equals(why.kind()) ? module.messageComponent("chatSafetyHeldAlert", player)
                    : module.messageComponent("chatSafetyNoticeAlert", player,
                    Placeholder.unparsed("kind", why.kind()));
        };
    }

    // The kick time is the server's; "offline" when the player is not here to be kicked.
    private Component ban(Actions.Action action, TagResolver player)
    {
        TagResolver role = Placeholder.unparsed("role", Bans.AT_RISK.equals(action.role()) ? "at risk" : "offender");
        TagResolver cause = Placeholder.unparsed("cause", banCause(action.why().cause()));
        if (Bukkit.getPlayer(action.uuid()) == null)
        {
            return module.messageComponent("chatSafetyBanOfflineAlert", player, role, cause);
        }
        long minutes = Math.max(0, Math.ceilDiv(Duration.between(Instant.now(), action.kickAt()).toMillis(), 60_000L));
        return module.messageComponent("chatSafetyBanAlert", player, role, cause,
                Placeholder.unparsed("minutes", String.valueOf(minutes)));
    }

    private static String channel(String channel)
    {
        return switch (channel)
        {
            case ChatSafety.CHAT -> "public chat";
            case ChatSafety.MSG -> "private message";
            case ChatSafety.MAIL -> "mail";
            case ChatSafety.ME -> "/me";
            case ChatSafety.NICK -> "nickname";
            case ChatSafety.SIGN -> "sign";
            case ChatSafety.BOOK -> "book";
            case ChatSafety.ANVIL -> "item name";
            // A module chat channel, named by its key's namespace, such as guilds:chat.
            default ->
            {
                int colon = channel.indexOf(':');
                yield (colon < 0 ? channel : channel.substring(0, colon)) + " chat";
            }
        };
    }

    private static String unmuteCause(String cause)
    {
        return switch (cause)
        {
            case "not_involved" -> "not involved";
            case "held_past_cap" -> "still not sure when the time limit ran out";
            case "kicked" -> "the ban's kick time came";
            default -> cause;
        };
    }

    private static String banCause(String cause)
    {
        return switch (cause)
        {
            case "kimi" -> "review verdict";
            case "corroborated" -> "corroborated by their own messages";
            default -> cause;
        };
    }

    // One line, cut at a whole character: a book's pages can be long and hold line breaks. The record on ECC-Server
    // keeps the whole text.
    private static String shortened(String text)
    {
        String line = text.replaceAll("\\R+", " ");
        if (line.codePointCount(0, line.length()) <= TEXT_LIMIT)
        {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, TEXT_LIMIT)) + "...";
    }
}
