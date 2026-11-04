package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Captured messages on their way to ECC-Server. Capture only appends here, so chat never waits for the server; one
 * sender thread posts the messages in capture order and, while the server is unreachable, keeps them and resends in
 * order. Each player may have a bounded number waiting; past it, their oldest is dropped.
 */
final class Outbox
{
    private static final int PLAYER_LIMIT = 500;
    private static final long FIRST_RETRY_MILLIS = 1000;
    private static final long LAST_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30);
    // How long stopping waits for the sender to deliver what is still waiting.
    private static final long DRAIN_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneOffset.UTC);

    private final EccServer server;
    private final Logger logger;
    private final Thread sender;
    private final Object lock = new Object();
    // Guarded by lock. Every waiting message in capture order, and the same messages by player.
    private final LinkedHashSet<Waiting> waiting = new LinkedHashSet<>();
    private final Map<UUID, ArrayDeque<Waiting>> players = new HashMap<>();
    // Guarded by lock. Taken from waiting and not yet accepted by the server.
    private @Nullable Waiting sending;
    // Guarded by lock. Messages dropped since the last warning about it.
    private int dropped;
    private boolean closing;

    Outbox(EccServer server, Logger logger)
    {
        this.server = server;
        this.logger = logger;
        this.sender = Thread.ofPlatform().daemon().name("Plex-ECC-Sender").unstarted(this::run);
    }

    void start()
    {
        sender.start();
    }

    // Stamps the message with the capture time, in the same order as the messages are sent.
    void add(UUID speaker, JsonObject message)
    {
        synchronized (lock)
        {
            if (closing)
            {
                return;
            }
            message.addProperty("time", TIME.format(Instant.now()));
            Waiting entry = new Waiting(speaker, message);
            ArrayDeque<Waiting> own = players.computeIfAbsent(speaker, uuid -> new ArrayDeque<>());
            if (own.size() >= PLAYER_LIMIT)
            {
                waiting.remove(own.pollFirst());
                dropped++;
            }
            own.addLast(entry);
            waiting.add(entry);
            lock.notifyAll();
        }
    }

    int size()
    {
        synchronized (lock)
        {
            return waiting.size() + (sending == null ? 0 : 1);
        }
    }

    // Gives the sender a few seconds to deliver what is waiting while the server answers, then stops it.
    void close()
    {
        synchronized (lock)
        {
            closing = true;
            lock.notifyAll();
        }
        try
        {
            sender.join(DRAIN_MILLIS);
            sender.interrupt();
            sender.join(DRAIN_MILLIS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        reportDropped();
        int unsent = size();
        if (unsent > 0)
        {
            logger.warn("Chat safety stopped with {} chat messages not sent to ECC-Server", unsent);
        }
    }

    private void run()
    {
        try
        {
            Waiting next;
            while ((next = take()) != null && deliver(next))
            {
                synchronized (lock)
                {
                    sending = null;
                }
                reportDropped();
            }
        }
        catch (InterruptedException ex)
        {
            // close() stops the sender; it counts what was not sent.
            Thread.currentThread().interrupt();
        }
    }

    // The oldest waiting message, or null once stopping with nothing left.
    @Nullable
    private Waiting take() throws InterruptedException
    {
        synchronized (lock)
        {
            while (waiting.isEmpty() && !closing)
            {
                lock.wait();
            }
            if (waiting.isEmpty())
            {
                return null;
            }
            Waiting next = waiting.removeFirst();
            ArrayDeque<Waiting> own = players.get(next.speaker());
            own.pollFirst();
            if (own.isEmpty())
            {
                players.remove(next.speaker());
            }
            sending = next;
            return next;
        }
    }

    // Sends the message until the server accepts or refuses it. False when stopping while the server is unreachable.
    private boolean deliver(Waiting next) throws InterruptedException
    {
        long retry = FIRST_RETRY_MILLIS;
        while (true)
        {
            try
            {
                server.postMessage(next.message());
                return true;
            }
            catch (EccServer.Rejected ex)
            {
                logger.error("ECC-Server refused chat message {}, which is not sent again: it {}",
                        next.message().get("id").getAsString(), ex.getMessage());
                return true;
            }
            catch (EccServer.Unavailable ex)
            {
                synchronized (lock)
                {
                    if (closing)
                    {
                        return false;
                    }
                }
                Thread.sleep(retry);
                retry = Math.min(retry * 2, LAST_RETRY_MILLIS);
            }
        }
    }

    private void reportDropped()
    {
        int count;
        synchronized (lock)
        {
            count = dropped;
            dropped = 0;
        }
        if (count > 0)
        {
            logger.warn("Chat safety dropped {} chat messages while ECC-Server was unreachable, each the oldest of a"
                    + " player who had {} waiting", count, PLAYER_LIMIT);
        }
    }

    // Compared by identity, so the buffer removes exactly the dropped message.
    private static final class Waiting
    {
        private final UUID speaker;
        private final JsonObject message;

        private Waiting(UUID speaker, JsonObject message)
        {
            this.speaker = speaker;
            this.message = message;
        }

        private UUID speaker()
        {
            return speaker;
        }

        private JsonObject message()
        {
            return message;
        }
    }
}
