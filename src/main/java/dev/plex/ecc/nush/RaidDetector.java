package dev.plex.ecc.nush;

import dev.plex.ECCModule;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RaidDetector
{
    public enum Signal
    {
        JOIN("joins", 10, 10),
        CHAT("chat messages", 5, 30),
        COMMAND("commands", 2, 40);

        private final String label;
        private final int seconds;
        private final int minimum;

        Signal(String label, int seconds, int minimum)
        {
            this.label = label;
            this.seconds = seconds;
            this.minimum = minimum;
        }
    }

    private static final int STARTUP_SECONDS = 60;
    private static final int QUIET_SECONDS = 120;

    private final ECCModule module;
    private final StaffFeed feed;
    private final Map<Signal, TrafficWindow> traffic = new EnumMap<>(Signal.class);
    private final long started = System.nanoTime();
    // Share the module monitor with manual toggles. Never acquire it while holding the quarantine monitor.
    private ScheduledFuture<?> timer;
    private boolean closed;
    private boolean raidActive;
    private long lastElevated;
    private long lastSample = -1;

    public RaidDetector(ECCModule module, StaffFeed feed)
    {
        this.module = module;
        this.feed = feed;
        for (Signal signal : Signal.values())
        {
            traffic.put(signal, new TrafficWindow(signal.seconds, signal.minimum));
        }
    }

    public void start(ScheduledExecutorService executor)
    {
        timer = executor.scheduleAtFixedRate(() ->
        {
            try
            {
                tick();
            }
            catch (RuntimeException failure)
            {
                close();
                module.getLogger().error("NUSH raid monitoring stopped after a failure", failure);
                throw failure;
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void record(Signal signal, UUID uuid)
    {
        synchronized (module)
        {
            if (closed || module.quarantine().isExempt(uuid))
            {
                return;
            }
            long now = seconds();
            if (signal == Signal.JOIN && now < STARTUP_SECONDS)
            {
                return;
            }
            traffic.get(signal).record(now, uuid);
            evaluate(now);
        }
    }

    public String status()
    {
        synchronized (module)
        {
            if (closed)
            {
                return "stopped";
            }
            if (raidActive)
            {
                return "raid active";
            }
            return seconds() < STARTUP_SECONDS ? "monitoring (startup join grace)" : "monitoring";
        }
    }

    public boolean raidActive()
    {
        synchronized (module)
        {
            return raidActive;
        }
    }

    public void close()
    {
        synchronized (module)
        {
            closed = true;
            if (timer != null)
            {
                timer.cancel(false);
            }
        }
    }

    private long seconds()
    {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
    }

    private void tick()
    {
        synchronized (module)
        {
            long now = seconds();
            if (closed || now == lastSample)
            {
                return;
            }
            lastSample = now;
            evaluate(now);
            if (raidActive)
            {
                return;
            }
            for (Map.Entry<Signal, TrafficWindow> entry : traffic.entrySet())
            {
                if (entry.getKey() != Signal.JOIN || now >= STARTUP_SECONDS + entry.getKey().seconds)
                {
                    entry.getValue().learn(now);
                }
            }
        }
    }

    private void evaluate(long now)
    {
        Signal spike = null;
        boolean elevated = false;
        Set<UUID> contributors = new HashSet<>();
        for (Map.Entry<Signal, TrafficWindow> entry : traffic.entrySet())
        {
            TrafficWindow window = entry.getValue();
            contributors.addAll(window.pollContributors(now, !module.isEnabled()));
            if (spike == null && window.count(now) >= window.trigger())
            {
                spike = entry.getKey();
            }
            elevated |= window.count(now) >= window.recovery();
        }

        boolean starting = spike != null && (!raidActive || !module.isEnabled());
        if (spike != null)
        {
            module.activateRaid(contributors);
        }
        if (starting)
        {
            raidActive = true;
            traffic.values().forEach(TrafficWindow::freeze);
            TrafficWindow window = traffic.get(spike);
            feed.alert(module.messageComponent("raidStarted",
                    Placeholder.unparsed("signal", spike.label),
                    Placeholder.unparsed("count", String.valueOf(window.count(now))),
                    Placeholder.unparsed("seconds", String.valueOf(spike.seconds)),
                    Placeholder.unparsed("limit", String.valueOf(window.trigger()))));
            module.api().logging().warn("Raid detected: {0} {1} in {2} seconds, limit {3}, baseline {4}. NUSH is enabled",
                    window.count(now), spike.label, spike.seconds, window.trigger(), window.baseline());
        }

        if (raidActive)
        {
            if (elevated)
            {
                lastElevated = now;
            }
            else if (now - lastElevated >= QUIET_SECONDS)
            {
                raidActive = false;
                feed.alert(module.messageComponent("raidQuiet",
                        Placeholder.unparsed("seconds", String.valueOf(QUIET_SECONDS))));
                module.api().logging().info("Raid traffic stayed below recovery limits for {0} seconds; NUSH state is unchanged",
                        QUIET_SECONDS);
            }
        }
    }
}
