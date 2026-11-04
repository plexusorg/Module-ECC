package dev.plex.ecc.nush;

import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine.Kind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StaffFeed
{
    public static final String VIEW_PERMISSION = "plex.nush.view";

    private final ECCModule module;
    private final ScheduledExecutorService executor;
    private final int intervalSeconds;
    private final int digestThreshold;
    private final Set<UUID> audience = ConcurrentHashMap.newKeySet();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();
    private final Object intervalLock = new Object();
    private final Set<UUID> senders = new HashSet<>();
    private final Map<UUID, Integer> liveLines = new HashMap<>();
    private final Map<String, Integer> textCounts = new HashMap<>();
    // Hidden lines survive the interval reset because a live line reports everything hidden since the previous one.
    private final Map<UUID, Integer> hiddenLines = new HashMap<>();
    private Instant intervalStart = Instant.now();
    private int messages;
    private int blockedCommands;

    public StaffFeed(ECCModule module, ScheduledExecutorService executor, int intervalSeconds, int digestThreshold)
    {
        this.module = module;
        this.executor = executor;
        this.intervalSeconds = intervalSeconds;
        this.digestThreshold = digestThreshold;
    }

    public void start()
    {
        // Use Plex's session snapshot, not Bukkit's live online-player collection.
        for (String name : module.api().players().onlineNames())
        {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null)
            {
                // Initialize membership on the session owner so a concurrent quit cannot leave a stale recipient.
                module.ownTask(player.getScheduler().run(module.plugin(), task ->
                {
                    if (player.hasPermission(VIEW_PERMISSION))
                    {
                        addStaff(player.getUniqueId());
                    }
                }, null));
            }
        }
        executor.scheduleAtFixedRate(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void addStaff(UUID uuid)
    {
        audience.add(uuid);
    }

    public void removeStaff(UUID uuid)
    {
        audience.remove(uuid);
        muted.remove(uuid);
    }

    public boolean toggleMute(UUID uuid)
    {
        if (muted.add(uuid))
        {
            return true;
        }
        muted.remove(uuid);
        return false;
    }

    public void alert(Component component)
    {
        send(component, false);
    }

    public boolean receivesAlerts(CommandSender sender)
    {
        return sender.equals(Bukkit.getConsoleSender())
                || sender instanceof Player player && audience.contains(player.getUniqueId());
    }

    public void line(Player source, Kind kind, Component rendered, String plainText)
    {
        UUID uuid = source.getUniqueId();
        Component message;
        synchronized (intervalLock)
        {
            senders.add(uuid);
            if (kind == Kind.CHAT)
            {
                messages++;
                textCounts.merge(plainText.trim().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            else
            {
                blockedCommands++;
            }

            if (senders.size() > digestThreshold)
            {
                return;
            }
            if (liveLines.getOrDefault(uuid, 0) > 0)
            {
                hiddenLines.merge(uuid, 1, Integer::sum);
                return;
            }

            liveLines.put(uuid, 1);
            Integer hidden = hiddenLines.remove(uuid);
            message = module.messageComponent(kind == Kind.CHAT ? "newPlayerChatPrefix" : "blockedCommandPrefix")
                    .append(rendered);
            if (hidden != null)
            {
                message = message.append(module.messageComponent("feedHidden",
                        Placeholder.unparsed("count", hidden.toString())));
            }
        }
        send(message, true);
    }

    public void clear()
    {
        audience.clear();
        muted.clear();
        synchronized (intervalLock)
        {
            reset();
            hiddenLines.clear();
        }
    }

    private void tick()
    {
        Component digest = null;
        synchronized (intervalLock)
        {
            if (senders.size() > digestThreshold)
            {
                Map.Entry<String, Integer> repeated = null;
                for (Map.Entry<String, Integer> candidate : textCounts.entrySet())
                {
                    if (repeated == null || candidate.getValue() > repeated.getValue())
                    {
                        repeated = candidate;
                    }
                }
                digest = module.messageComponent("feedDigest",
                        Placeholder.unparsed("players", String.valueOf(senders.size())),
                        Placeholder.unparsed("messages", String.valueOf(messages)),
                        Placeholder.unparsed("commands", String.valueOf(blockedCommands)),
                        Placeholder.unparsed("seconds", String.valueOf(Duration.between(intervalStart, Instant.now()).toSeconds())),
                        Placeholder.unparsed("text", repeated == null ? "" : repeated.getKey()),
                        Placeholder.unparsed("count", repeated == null ? "0" : repeated.getValue().toString()));
            }
            reset();
        }

        if (digest != null)
        {
            send(digest, true);
        }
    }

    private void reset()
    {
        senders.clear();
        liveLines.clear();
        textCounts.clear();
        messages = 0;
        blockedCommands = 0;
        intervalStart = Instant.now();
    }

    private void send(Component component, boolean respectMute)
    {
        for (UUID uuid : audience)
        {
            if (respectMute && muted.contains(uuid))
            {
                continue;
            }
            Player staff = Bukkit.getPlayer(uuid);
            if (staff != null)
            {
                staff.sendMessage(component);
            }
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }
}
