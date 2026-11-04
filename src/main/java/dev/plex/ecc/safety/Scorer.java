package dev.plex.ecc.safety;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The fast check: one bounded queue per player, served in turn, and one worker that sends each message to the model
 * host. Messages reach the host one at a time, and each conversation's messages in the order they were submitted.
 * While the host is down or not ready the worker keeps retrying the message it holds; the others wait in their
 * queues.
 */
final class Scorer
{
    private static final long IDLE_PROBE_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long FIRST_RETRY_MILLIS = 1000;
    private static final long LAST_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final ModelHost host;
    private final Logger logger;
    private final int queueSize;
    private final BiConsumer<Message, Verdict> finisher;
    private final Thread worker;
    private final Object lock = new Object();
    // Guarded by lock.
    private final Map<UUID, ArrayDeque<Message>> queues = new HashMap<>();
    private final ArrayDeque<UUID> turns = new ArrayDeque<>();
    // The same waiting messages by conversation, in the order they were submitted.
    private final Map<String, ArrayDeque<Message>> conversations = new HashMap<>();
    private int waiting;
    private @Nullable Message held;
    private boolean closed;
    // Written by the worker only.
    private volatile @Nullable Decision decision;
    private volatile HostState state = new HostState(null, "not contacted yet", Instant.now());

    Scorer(ModelHost host, Logger logger, int queueSize, BiConsumer<Message, Verdict> finisher)
    {
        this.host = host;
        this.logger = logger;
        this.queueSize = queueSize;
        this.finisher = finisher;
        this.worker = Thread.ofPlatform().daemon().name("Plex-ECC-Score").unstarted(this::run);
    }

    void start()
    {
        worker.start();
    }

    void submit(Message message)
    {
        Message dropped;
        synchronized (lock)
        {
            dropped = closed ? message : enqueue(message);
        }
        if (dropped != null)
        {
            finisher.accept(dropped, Verdict.unchecked(dropped == message ? Verdict.UNSCORED : Verdict.DROPPED));
        }
    }

    // Returns the player's oldest waiting message when the queue was full.
    @Nullable
    private Message enqueue(Message message)
    {
        ArrayDeque<Message> queue = queues.computeIfAbsent(message.speakerUuid(), uuid -> new ArrayDeque<>());
        Message dropped = null;
        if (queue.isEmpty())
        {
            turns.addLast(message.speakerUuid());
        }
        else if (queue.size() >= queueSize)
        {
            dropped = queue.pollFirst();
            unlist(dropped);
            waiting--;
        }
        queue.addLast(message);
        conversations.computeIfAbsent(message.conversation(), key -> new ArrayDeque<>()).addLast(message);
        waiting++;
        lock.notifyAll();
        return dropped;
    }

    private void unlist(Message message)
    {
        ArrayDeque<Message> conversation = conversations.get(message.conversation());
        conversation.remove(message);
        if (conversation.isEmpty())
        {
            conversations.remove(message.conversation());
        }
    }

    int backlog()
    {
        synchronized (lock)
        {
            return waiting + (held == null ? 0 : 1);
        }
    }

    HostState state()
    {
        return state;
    }

    @Nullable
    Decision decision()
    {
        return decision;
    }

    // Stops the worker and records every message it did not score as unscored.
    void close()
    {
        List<Message> unscored = new ArrayList<>();
        synchronized (lock)
        {
            closed = true;
            queues.values().forEach(unscored::addAll);
            queues.clear();
            turns.clear();
            conversations.clear();
            waiting = 0;
            lock.notifyAll();
        }
        worker.interrupt();
        try
        {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        host.close();
        unscored.forEach(message -> finisher.accept(message, Verdict.unchecked(Verdict.UNSCORED)));
    }

    private void run()
    {
        try
        {
            while (true)
            {
                Message next = take();
                if (next == null)
                {
                    probe();
                    continue;
                }
                finisher.accept(next, check(next));
                synchronized (lock)
                {
                    held = null;
                }
            }
        }
        catch (InterruptedException ex)
        {
            // close() interrupts the worker; the message it holds is recorded as unscored below.
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException ex)
        {
            logger.error("Chat safety scoring stopped after a failure", ex);
        }
        finally
        {
            Message unscored;
            synchronized (lock)
            {
                unscored = held;
                held = null;
            }
            if (unscored != null)
            {
                finisher.accept(unscored, Verdict.unchecked(Verdict.UNSCORED));
            }
        }
    }

    // The next message, taking players in turn, or null after an idle interval.
    @Nullable
    private Message take() throws InterruptedException
    {
        synchronized (lock)
        {
            if (!closed && turns.isEmpty())
            {
                lock.wait(IDLE_PROBE_MILLIS);
            }
            if (closed)
            {
                throw new InterruptedException();
            }
            UUID player = turns.pollFirst();
            if (player == null)
            {
                return null;
            }
            // The player whose turn it is has their conversation served, oldest waiting message first, so the host
            // receives each conversation in the order it was said.
            String conversation = queues.get(player).peekFirst().conversation();
            held = conversations.get(conversation).peekFirst();
            unlist(held);
            UUID author = held.speakerUuid();
            ArrayDeque<Message> queue = queues.get(author);
            queue.remove(held);
            if (queue.isEmpty())
            {
                queues.remove(author);
                turns.remove(author);
            }
            if (!author.equals(player))
            {
                turns.addFirst(player);
            }
            else if (!queue.isEmpty())
            {
                turns.addLast(player);
            }
            waiting--;
            return held;
        }
    }

    private Verdict check(Message message) throws InterruptedException
    {
        Verdict.Score score = score(message);
        if (score == null)
        {
            return Verdict.unchecked(Verdict.NO_SCORE);
        }
        Decision parameters = parametersFor(score.version());
        if (parameters == null)
        {
            return new Verdict(Verdict.NO_SCORE, score, null, null, null);
        }
        return Verdict.scored(score, parameters);
    }

    // Sends the message until the host scores it or answers that it has no score. A message the host could not take
    // was not added to its window, so sending it again is safe.
    @Nullable
    private Verdict.Score score(Message message) throws InterruptedException
    {
        long retry = FIRST_RETRY_MILLIS;
        while (true)
        {
            try
            {
                Verdict.Score score = host.score(message.conversation(), message.speaker(), message.text());
                up(score.version());
                return score;
            }
            catch (ModelHost.NoScore ex)
            {
                logger.warn("The model host gave message {} no score: {}", message.id(), ex.getMessage());
                return null;
            }
            catch (ModelHost.Unavailable ex)
            {
                down(ex.getMessage());
                Thread.sleep(retry);
                retry = Math.min(retry * 2, LAST_RETRY_MILLIS);
            }
        }
    }

    // The decision parameters of the bundle that produced a score, read again whenever the bundle version changes.
    // Null when the host replaced that bundle again before its parameters could be read.
    @Nullable
    private Decision parametersFor(String version) throws InterruptedException
    {
        Decision current = decision;
        long retry = FIRST_RETRY_MILLIS;
        while (current == null || !current.version().equals(version))
        {
            try
            {
                current = host.decision();
                decision = current;
                logger.info("Chat safety uses model bundle {}", current.version());
                if (!current.version().equals(version))
                {
                    logger.warn("Model bundle {} was replaced by {} before its decision parameters were read; the score"
                            + " is recorded without a decision", version, current.version());
                    return null;
                }
            }
            catch (ModelHost.Unavailable ex)
            {
                down(ex.getMessage());
                Thread.sleep(retry);
                retry = Math.min(retry * 2, LAST_RETRY_MILLIS);
            }
        }
        return current;
    }

    private void probe() throws InterruptedException
    {
        try
        {
            host.health();
            Decision current = host.decision();
            Decision previous = decision;
            if (previous == null || !current.version().equals(previous.version()))
            {
                decision = current;
                logger.info("Chat safety uses model bundle {}", current.version());
            }
            up(current.version());
        }
        catch (ModelHost.Unavailable ex)
        {
            down(ex.getMessage());
        }
    }

    private void up(String version)
    {
        HostState previous = state;
        boolean wasReady = Boolean.TRUE.equals(previous.ready());
        state = new HostState(true, "ready, bundle " + version, wasReady ? previous.since() : Instant.now());
        if (Boolean.FALSE.equals(previous.ready()))
        {
            logger.warn("Chat safety scoring resumed; {} messages wait to be scored", backlog());
        }
    }

    private void down(String reason)
    {
        HostState previous = state;
        boolean wasDown = Boolean.FALSE.equals(previous.ready());
        state = new HostState(false, reason, wasDown ? previous.since() : Instant.now());
        if (!wasDown)
        {
            logger.warn("Chat safety scoring paused: the model host {}. Messages wait in their queues and are scored"
                    + " in order when it answers", reason);
        }
    }

    /**
     * The model host as last seen. {@code ready} is null before the first contact.
     */
    record HostState(@Nullable Boolean ready, String detail, Instant since)
    {
    }
}
