package dev.plex.ecc.nush;

import dev.plex.ECCModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Quarantine
{
    public enum Kind
    {
        CHAT, COMMAND
    }

    public record LogEntry(Kind kind, Instant time, String text)
    {
    }

    private static final String TRUSTED_KEY = "staff_trusted";
    private static final int TRUST_CACHE_LIMIT = 4096;
    private static final long TRUST_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ECCModule module;
    private final StaffFeed feed;
    private final ScheduledExecutorService executor;
    private final int logSize;
    private final Map<UUID, Restriction> restrictions = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTrust> pendingTrust = new ConcurrentHashMap<>();
    // Share positive, negative, and in-flight reads across login threads. Local writes update cached decisions.
    private final Map<UUID, TrustRead> trustReads = new LinkedHashMap<>();
    private final Set<UUID> trustUpdates = new HashSet<>();
    private final long recentJoinMillis;
    private boolean active;
    private boolean sweepExistingSessions;
    private boolean closed;

    public Quarantine(ECCModule module, StaffFeed feed, ScheduledExecutorService executor, int logSize, int recentJoinMinutes)
    {
        this.module = module;
        this.feed = feed;
        this.executor = executor;
        this.logSize = logSize;
        this.recentJoinMillis = TimeUnit.MINUTES.toMillis(recentJoinMinutes);
    }

    public synchronized void joined(Player player, long joinedAt, boolean existingSession)
    {
        if (closed || sessions.containsKey(player.getUniqueId()))
        {
            return;
        }
        Session session = new Session(player.getUniqueId(), player.getName(),
                joinedAt, player.hasPermission("plex.nush.bypass"), !existingSession && consumePending(player.getUniqueId()));
        session.trustLoaded = !existingSession;
        session.admissionRequired = active && (!existingSession || sweepExistingSessions)
                && session.joinedAt >= System.currentTimeMillis() - recentJoinMillis;
        sessions.put(session.uuid, session);
        if (restrictions.containsKey(session.uuid))
        {
            resume(session.uuid);
            return;
        }
        if (session.admissionRequired && session.trustLoaded && !session.exempt())
        {
            restrict(session);
        }
    }

    public synchronized void loadSessionTrust(UUID uuid)
    {
        Session session = sessions.get(uuid);
        if (closed || session == null || session.trustLoaded)
        {
            return;
        }
        readTrust(uuid).whenComplete((trusted, failure) ->
        {
            synchronized (this)
            {
                if (failure != null)
                {
                    module.getLogger().error("Unable to read the NUSH staff trust of {}", uuid, failure);
                }
                if (!closed && sessions.get(uuid) == session && !session.trustLoaded && session.trustRevision == 0)
                {
                    session.trusted = failure == null && trusted;
                    session.trustLoaded = true;
                    if (session.admissionRequired && !session.exempt())
                    {
                        restrict(session);
                    }
                }
            }
        });
    }

    public synchronized void toggle(boolean enabled)
    {
        if (closed || active && enabled)
        {
            return;
        }
        active = enabled;
        sweepExistingSessions = enabled;
        if (!enabled)
        {
            clearRestrictions();
            sessions.values().forEach(session -> session.admissionRequired = false);
            return;
        }
        long cutoff = System.currentTimeMillis() - recentJoinMillis;
        for (Session session : sessions.values())
        {
            session.admissionRequired = session.joinedAt >= cutoff;
            if (session.admissionRequired && session.trustLoaded && !session.exempt())
            {
                restrict(session);
            }
        }
    }

    public synchronized void admitRaid(Collection<UUID> contributors)
    {
        if (closed)
        {
            return;
        }
        active = true;
        for (UUID uuid : contributors)
        {
            Session session = sessions.get(uuid);
            if (session == null || session.admissionRequired || session.exempt())
            {
                continue;
            }
            // Keep a completed online wait valid for this session, even while traffic remains elevated.
            session.admissionRequired = true;
            if (session.trustLoaded)
            {
                restrict(session);
            }
        }
    }

    private void restrict(Session session)
    {
        Restriction restriction = restrictions.get(session.uuid);
        if (restriction == null)
        {
            restriction = new Restriction(session.uuid, session.name, logSize);
            restriction.remainingNanos = TimeUnit.MINUTES.toNanos(module.getTime());
            restrictions.put(session.uuid, restriction);
            feed.alert(module.messageComponent("newPlayerMarked", Placeholder.unparsed("player", session.name),
                    Placeholder.unparsed("minutes", String.valueOf(module.getTime()))));
        }
        resume(session.uuid);
    }

    public synchronized void pause(UUID uuid)
    {
        sessions.remove(uuid);
        pendingTrust.remove(uuid);
        Restriction restriction = restrictions.get(uuid);
        if (restriction != null && restriction.expiry != null)
        {
            restriction.remainingNanos = Math.max(0, restriction.expiry.getDelay(TimeUnit.NANOSECONDS));
            restriction.cancelExpiry();
        }
    }

    public synchronized void resume(UUID uuid)
    {
        Restriction restriction = restrictions.get(uuid);
        if (restriction == null || restriction.expiry != null)
        {
            return;
        }
        long generation = ++restriction.timerGeneration;
        restriction.expiry = executor.schedule(() -> expire(restriction, generation),
                restriction.remainingNanos, TimeUnit.NANOSECONDS);
    }

    private synchronized void expire(Restriction restriction, long generation)
    {
        // Cancellation alone cannot stop a callback that has already started.
        if (restrictions.get(restriction.uuid()) == restriction && restriction.timerGeneration == generation)
        {
            release(restriction.uuid());
            feed.alert(module.messageComponent("quarantineExpired", Placeholder.unparsed("player", restriction.name())));
        }
    }

    public synchronized CompletableFuture<Void> verify(UUID uuid, String byName)
    {
        if (closed || !trustUpdates.add(uuid))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("NUSH is closed or a trust update is already in progress"));
        }
        return module.api().players().moduleData(module, uuid).set(TRUSTED_KEY, true).thenRun(() ->
        {
            synchronized (this)
            {
                if (closed)
                {
                    throw new IllegalStateException("NUSH was unloaded during the trust update");
                }
                Session session = sessions.get(uuid);
                if (session != null)
                {
                    session.trusted = true;
                    session.trustRevision++;
                    session.trustLoaded = true;
                }
                TrustRead cached = trustReads.get(uuid);
                if (cached != null)
                {
                    cached.updated = true;
                }
                pendingTrust.computeIfPresent(uuid, (key, pending) -> new PendingTrust(true, pending.time()));
                String name = restrictions.containsKey(uuid) ? restrictions.get(uuid).name() : nameOf(uuid);
                release(uuid);
                feed.alert(module.messageComponent("playerAllowed", Placeholder.unparsed("player", name),
                        Placeholder.unparsed("admin", byName)));
            }
        }).whenComplete((ignored, failure) ->
        {
            synchronized (this)
            {
                trustUpdates.remove(uuid);
            }
        });
    }

    public synchronized CompletableFuture<Void> revoke(UUID uuid)
    {
        if (closed || !trustUpdates.add(uuid))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("NUSH is closed or a trust update is already in progress"));
        }
        return module.api().players().moduleData(module, uuid).remove(TRUSTED_KEY).thenRun(() ->
        {
            synchronized (this)
            {
                if (closed)
                {
                    throw new IllegalStateException("NUSH was unloaded during the trust update");
                }
                TrustRead cached = trustReads.get(uuid);
                if (cached != null)
                {
                    cached.updated = false;
                }
                pendingTrust.computeIfPresent(uuid, (key, pending) -> new PendingTrust(false, pending.time()));
                Session session = sessions.get(uuid);
                if (session != null)
                {
                    session.trusted = false;
                    session.trustRevision++;
                    session.trustLoaded = true;
                    release(uuid);
                    restrict(session);
                }
            }
        }).whenComplete((ignored, failure) ->
        {
            synchronized (this)
            {
                trustUpdates.remove(uuid);
            }
        });
    }

    private void release(UUID uuid)
    {
        Restriction restriction = restrictions.remove(uuid);
        if (restriction != null)
        {
            restriction.cancelExpiry();
        }
    }

    private synchronized CompletableFuture<Boolean> readTrust(UUID uuid)
    {
        if (closed)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("NUSH is closed"));
        }
        long now = System.nanoTime();
        TrustRead cached = trustReads.get(uuid);
        if (cached != null)
        {
            if (!cached.future.isDone() || now - cached.started < TRUST_CACHE_NANOS)
            {
                return cached.updated == null ? cached.future : CompletableFuture.completedFuture(cached.updated);
            }
            trustReads.remove(uuid);
        }
        if (trustReads.size() >= TRUST_CACHE_LIMIT)
        {
            Iterator<TrustRead> entries = trustReads.values().iterator();
            while (entries.hasNext())
            {
                if (entries.next().future.isDone())
                {
                    entries.remove();
                    break;
                }
            }
            if (trustReads.size() >= TRUST_CACHE_LIMIT)
            {
                return CompletableFuture.failedFuture(new IllegalStateException("NUSH trust read capacity reached"));
            }
        }
        TrustRead read = new TrustRead(module.api().players().moduleData(module, uuid).getBoolean(TRUSTED_KEY, false), now);
        trustReads.put(uuid, read);
        read.future.whenComplete((trusted, failure) ->
        {
            if (failure != null)
            {
                synchronized (this)
                {
                    trustReads.remove(uuid, read);
                }
            }
        });
        return read.future;
    }

    public synchronized boolean isExempt(UUID uuid)
    {
        Session session = sessions.get(uuid);
        return session == null || session.exempt();
    }

    public boolean isRestricted(UUID uuid)
    {
        return restrictions.containsKey(uuid);
    }

    @Nullable
    public Restriction entry(UUID uuid)
    {
        return restrictions.get(uuid);
    }

    public Collection<Restriction> entries()
    {
        return Collections.unmodifiableCollection(restrictions.values());
    }

    public void record(UUID uuid, LogEntry logEntry)
    {
        Restriction restriction = entry(uuid);
        if (restriction != null)
        {
            restriction.record(logEntry);
        }
    }

    public void prepareLogin(UUID uuid)
    {
        PendingTrust pending = new PendingTrust(false, System.currentTimeMillis());
        synchronized (this)
        {
            pendingTrust.values().removeIf(value -> pending.time() - value.time() > TimeUnit.MINUTES.toMillis(5));
            if (closed)
            {
                return;
            }
            pendingTrust.put(uuid, pending);
        }
        try
        {
            boolean trusted = readTrust(uuid).join();
            synchronized (this)
            {
                // A completed staff mutation takes precedence over this earlier read.
                if (pendingTrust.get(uuid) == pending)
                {
                    pendingTrust.put(uuid, new PendingTrust(trusted, pending.time()));
                }
            }
        }
        catch (CompletionException failure)
        {
            module.getLogger().error("Unable to read the NUSH staff trust of {}", uuid, failure);
        }
    }

    public void clearPending(UUID uuid)
    {
        pendingTrust.remove(uuid);
    }

    private boolean consumePending(UUID uuid)
    {
        PendingTrust pending = pendingTrust.remove(uuid);
        return pending != null && pending.trusted();
    }

    public int kick(Component message)
    {
        int kicked = 0;
        for (Restriction restriction : restrictions.values())
        {
            Player player = Bukkit.getPlayer(restriction.uuid());
            if (player == null)
            {
                continue;
            }
            player.getScheduler().run(module.plugin(), task -> player.kick(message), null);
            kicked++;
        }
        return kicked;
    }

    public synchronized void clear()
    {
        closed = true;
        clearRestrictions();
        pendingTrust.clear();
        trustReads.clear();
        sessions.clear();
    }

    private void clearRestrictions()
    {
        restrictions.values().forEach(Restriction::cancelExpiry);
        restrictions.clear();
    }

    private static final class TrustRead
    {
        private final CompletableFuture<Boolean> future;
        private final long started;
        // Retain an in-flight read after a staff write so capacity eviction cannot duplicate its I/O.
        private Boolean updated;

        private TrustRead(CompletableFuture<Boolean> future, long started)
        {
            this.future = future;
            this.started = started;
        }
    }

    private record PendingTrust(boolean trusted, long time)
    {
    }

    private static final class Session
    {
        private final UUID uuid;
        private final String name;
        private final long joinedAt;
        private final boolean bypass;
        private boolean trusted;
        private long trustRevision;
        private boolean trustLoaded;
        private boolean admissionRequired;

        private Session(UUID uuid, String name, long joinedAt, boolean bypass, boolean trusted)
        {
            this.uuid = uuid;
            this.name = name;
            this.joinedAt = joinedAt;
            this.bypass = bypass;
            this.trusted = trusted;
        }

        private boolean exempt()
        {
            return trusted || bypass;
        }
    }

    private String nameOf(UUID uuid)
    {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? uuid.toString() : player.getName();
    }

    public static final class Restriction
    {
        private final UUID uuid;
        private final String name;
        private final int logSize;
        private final Deque<LogEntry> log = new ArrayDeque<>();
        private volatile ScheduledFuture<?> expiry;
        private volatile long remainingNanos;
        // Access timer generations under the quarantine owner's monitor.
        private long timerGeneration;
        private int messages;
        private int blockedCommands;

        private Restriction(UUID uuid, String name, int logSize)
        {
            this.uuid = uuid;
            this.name = name;
            this.logSize = logSize;
        }

        public UUID uuid()
        {
            return uuid;
        }

        public String name()
        {
            return name;
        }

        public long remainingSeconds()
        {
            ScheduledFuture<?> task = expiry;
            long nanos = task == null ? remainingNanos : Math.max(0, task.getDelay(TimeUnit.NANOSECONDS));
            return TimeUnit.NANOSECONDS.toSeconds(nanos);
        }

        public List<LogEntry> log()
        {
            synchronized (log)
            {
                return new ArrayList<>(log);
            }
        }

        public int messages()
        {
            synchronized (log)
            {
                return messages;
            }
        }

        public int blockedCommands()
        {
            synchronized (log)
            {
                return blockedCommands;
            }
        }

        private void record(LogEntry entry)
        {
            synchronized (log)
            {
                log.addLast(entry);
                if (log.size() > logSize)
                {
                    log.pollFirst();
                }
                if (entry.kind() == Kind.CHAT)
                {
                    messages++;
                }
                else
                {
                    blockedCommands++;
                }
            }
        }

        private void cancelExpiry()
        {
            timerGeneration++;
            ScheduledFuture<?> task = expiry;
            if (task != null)
            {
                task.cancel(false);
            }
            expiry = null;
        }
    }
}
