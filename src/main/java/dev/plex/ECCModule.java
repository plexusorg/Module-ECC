package dev.plex;

import dev.plex.api.config.ModuleConfiguration;
import dev.plex.command.NUSHCommand;
import dev.plex.listener.ChatListener;
import dev.plex.listener.CommandListener;
import dev.plex.listener.JoinListener;
import dev.plex.listener.LoginListener;
import dev.plex.module.PlexModule;
import dev.plex.ecc.nush.FaweHook;
import dev.plex.ecc.nush.Quarantine;
import dev.plex.ecc.nush.RaidDetector;
import dev.plex.ecc.nush.StaffFeed;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class ECCModule extends PlexModule
{
    private static final String FAWE_PLUGIN = "FastAsyncWorldEdit";

    private ModuleConfiguration config;
    private ScheduledExecutorService executor;
    private Quarantine quarantine;
    private StaffFeed feed;
    private RaidDetector raidDetector;
    private FaweHook faweHook;
    private volatile boolean enabled;
    private volatile int time;
    private volatile boolean shadowActive;

    @Override
    public void load()
    {
        config = api().moduleConfigs().create(this, "config.yml");
        loadMessages("messages.yml");
        registerCommand(new NUSHCommand(this));
    }

    @Override
    public void enable()
    {
        config.load();
        enabled = config.getBoolean("server.enabled", false);
        time = requireAtLeastOne("server.wait_time", config.getInt("server.wait_time", 5));
        int intervalSeconds = requireAtLeastOne("feed.interval_seconds", config.getInt("feed.interval_seconds", 10));
        int digestThreshold = requireAtLeastOne("feed.digest_threshold", config.getInt("feed.digest_threshold", 5));
        int recentJoinMinutes = requireAtLeastOne("server.recent_join_minutes", config.getInt("server.recent_join_minutes", 5));
        int logSize = requireAtLeastOne("log.size", config.getInt("log.size", 50));

        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("Plex-NUSH").factory());
        feed = new StaffFeed(this, executor, intervalSeconds, digestThreshold);
        quarantine = new Quarantine(this, feed, executor, logSize, recentJoinMinutes);
        raidDetector = new RaidDetector(this, feed);

        registerListener(new LoginListener(this));
        registerListener(new JoinListener(this));
        registerListener(new ChatListener(this));
        registerListener(new CommandListener(this));
        feed.start();
        quarantine.toggle(enabled);
        for (String name : api().players().onlineNames())
        {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null)
            {
                Quarantine owner = quarantine;
                ownTask(player.getScheduler().run(plugin(), task ->
                {
                    owner.joined(player, player.getLastLogin(), true);
                    owner.loadSessionTrust(player.getUniqueId());
                }, null));
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled(FAWE_PLUGIN))
        {
            faweHook = new FaweHook(this);
            shadowActive = faweHook.register();
        }
        else
        {
            getLogger().warn("FastAsyncWorldEdit is not enabled; WorldEdit commands of restricted players are cancelled");
        }
        raidDetector.start(executor);
    }

    @Override
    public void disable()
    {
        if (raidDetector != null)
        {
            raidDetector.close();
        }
        shadowActive = false;
        if (faweHook != null)
        {
            faweHook.unregister();
            faweHook = null;
        }
        if (quarantine != null)
        {
            quarantine.clear();
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
        if (feed != null)
        {
            feed.clear();
        }
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public int getTime()
    {
        return time;
    }

    // True while FAWE routes restricted edits through the shadow extent; otherwise commands are cancelled instead.
    public boolean shadowActive()
    {
        return shadowActive;
    }

    public Quarantine quarantine()
    {
        return quarantine;
    }

    public StaffFeed feed()
    {
        return feed;
    }

    public RaidDetector raidDetector()
    {
        return raidDetector;
    }

    public synchronized void toggle(boolean toggle)
    {
        quarantine.toggle(toggle);
        enabled = toggle;
        config.set("server.enabled", toggle);
        config.save();
    }

    public void setTime(int minutes)
    {
        time = minutes;
        config.set("server.wait_time", minutes);
        config.save();
    }

    public synchronized void activateRaid(Collection<UUID> contributors)
    {
        quarantine.admitRaid(contributors);
        enabled = true;
    }

    private int requireAtLeastOne(String key, int value)
    {
        if (value < 1)
        {
            throw new IllegalStateException("Invalid " + key + " '" + value + "'; the value must be at least 1");
        }
        return value;
    }
}
