package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import dev.plex.api.logging.LoggingApi;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Posts that wait for no answer: presence, and lines whose verdict is not used that did not reach ECC-Server when
 * they were sent. One sender thread posts them in order and, while ECC-Server is unreachable, keeps them and resends
 * in order. Past the limit the oldest is dropped.
 */
final class Outbox
{
    private static final int LIMIT = 5000;
    // How long stopping waits for the sender to deliver what is still waiting.
    private static final long DRAIN_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final EccServer server;
    private final LoggingApi logger;
    private final Thread sender;
    private final Backoff backoff;
    private final Object lock = new Object();
    // Guarded by lock.
    private final ArrayDeque<Post> waiting = new ArrayDeque<>();
    // Guarded by lock. Posts dropped since the last warning about it.
    private int dropped;
    private boolean closing;
    // Guarded by lock. Set when a post did not reach the server, until nothing is waiting.
    private boolean resending;

    Outbox(EccServer server, LoggingApi logger)
    {
        this.server = server;
        this.logger = logger;
        this.sender = Thread.ofPlatform().daemon().name("Plex-ECC-Sender").unstarted(this::run);
        this.backoff = server.backoff();
    }

    void start()
    {
        sender.start();
    }

    void add(String path, JsonObject body)
    {
        synchronized (lock)
        {
            if (closing)
            {
                return;
            }
            if (waiting.size() >= LIMIT)
            {
                waiting.pollFirst();
                dropped++;
            }
            waiting.addLast(new Post(path, body));
            lock.notifyAll();
        }
    }

    // True while a post that did not reach the server waits to be sent again: a later line goes behind it, so lines
    // reach the server in the order they were written.
    boolean resending()
    {
        synchronized (lock)
        {
            return resending;
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
        backoff.wake();
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
        int unsent;
        synchronized (lock)
        {
            unsent = waiting.size();
        }
        if (unsent > 0)
        {
            logger.warn("Chat safety stopped with {0} lines and presence changes not sent to ECC-Server", unsent);
        }
    }

    private void run()
    {
        try
        {
            Post next;
            while ((next = take()) != null && deliver(next))
            {
                synchronized (lock)
                {
                    waiting.remove(next);
                    if (waiting.isEmpty())
                    {
                        resending = false;
                    }
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

    // The oldest waiting post, left in the queue until it is delivered; null once stopping with nothing left.
    @Nullable
    private Post take() throws InterruptedException
    {
        synchronized (lock)
        {
            while (waiting.isEmpty() && !closing)
            {
                lock.wait();
            }
            return waiting.peekFirst();
        }
    }

    // Sends the post until the server accepts or refuses it. False when stopping while the server is unreachable.
    private boolean deliver(Post next) throws InterruptedException
    {
        backoff.reset();
        while (true)
        {
            try
            {
                server.post(next.path(), next.body());
                return true;
            }
            catch (EccServer.Rejected ex)
            {
                logger.error("ECC-Server refused {0} {1}, which is not sent again: it {2}",
                        next.path(), MINI_MESSAGE.escapeTags(next.body().toString()),
                        MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
                return true;
            }
            catch (EccServer.Unavailable ex)
            {
                synchronized (lock)
                {
                    resending = true;
                }
                // close() ends the wait at once; new posts do not.
                backoff.pause();
                synchronized (lock)
                {
                    if (closing)
                    {
                        return false;
                    }
                }
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
            logger.warn("Chat safety dropped the {0} oldest posts to ECC-Server, which had {1} waiting", count, LIMIT);
        }
    }

    // Compared by identity, so removing a delivered post removes exactly it.
    private record Post(String path, JsonObject body)
    {
        @Override
        public boolean equals(Object other)
        {
            return this == other;
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this);
        }
    }
}
