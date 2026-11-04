package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.plex.api.storage.ModuleStorage;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Players the fast check triggered on, waiting for the slow check. The database row is authoritative; this owner
 * keeps every row in memory, applies the shadow mute from it, and writes each change back on its own writer thread.
 *
 * <p>A row's {@code state} JSON holds what the slow check needs: the trigger message, the decision parameters and
 * bundle version it was judged by, the triggering conversation's transcript and the player's own recent messages
 * (each with author, time, order and score), and the player's latest shadowed messages since the trigger in
 * {@code later}, with {@code later_dropped} counting older ones that no longer fit.</p>
 */
final class Pending
{
    private static final long RETRY_SECONDS = 30;
    private static final int LATER_LIMIT = 200;

    private final ModuleStorage storage;
    private final String table;
    private final Logger logger;
    private final ScheduledThreadPoolExecutor writer =
            new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("Plex-ECC-Pending").factory());
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    Pending(ModuleStorage storage, Logger logger)
    {
        this.storage = storage;
        this.table = storage.table("pending");
        this.logger = logger;
        writer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    // Applies the module's migrations and loads every pending player. Runs once, before chat safety captures anything.
    void load() throws SQLException
    {
        storage.migrations().run();
        List<Entry> rows = storage.jdbi().withHandle(handle -> handle
                .createQuery("SELECT player_uuid, player_name, triggered_at, state FROM " + table)
                .map((row, context) -> new Entry(UUID.fromString(row.getString("player_uuid")),
                        row.getString("player_name"), Instant.ofEpochMilli(row.getLong("triggered_at")),
                        parse(row.getString("player_uuid"), row.getString("state"))))
                .list());
        rows.forEach(entry -> entries.put(entry.uuid(), entry));
    }

    boolean contains(UUID uuid)
    {
        return entries.containsKey(uuid);
    }

    Collection<Entry> entries()
    {
        return entries.values();
    }

    // A copy of the player's state, or null when they are not pending.
    @Nullable
    JsonObject snapshot(UUID uuid)
    {
        Entry entry = entries.get(uuid);
        if (entry == null)
        {
            return null;
        }
        synchronized (entry)
        {
            return entry.state().deepCopy();
        }
    }

    // Sets one number in the player's state.
    void set(UUID uuid, String key, long value)
    {
        Entry entry = entries.get(uuid);
        if (entry == null)
        {
            return;
        }
        synchronized (entry)
        {
            entry.state().addProperty(key, value);
        }
        persist(entry);
    }

    // Ends the player's pending state, which lifts the shadow mute, and deletes their row.
    void remove(UUID uuid)
    {
        Entry entry = entries.remove(uuid);
        if (entry == null)
        {
            return;
        }
        entry.markRemoved();
        try
        {
            writer.execute(() -> delete(entry));
        }
        catch (RejectedExecutionException ex)
        {
            logger.error("Chat safety stopped before it could delete the pending state of {}; it returns at the next"
                    + " start", entry.uuid());
        }
    }

    private void delete(Entry entry)
    {
        try
        {
            storage.jdbi().useHandle(handle -> handle.createUpdate("DELETE FROM " + table + " WHERE player_uuid = :uuid")
                    .bind("uuid", entry.uuid().toString())
                    .execute());
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to delete the chat safety pending state of {}; retrying in {} seconds", entry.uuid(),
                    RETRY_SECONDS, ex);
            writer.schedule(() -> delete(entry), RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    // Records a trigger. Returns false when the player is already pending.
    boolean add(UUID uuid, String name, Instant triggeredAt, JsonObject state)
    {
        Entry entry = new Entry(uuid, name, triggeredAt, state);
        if (entries.putIfAbsent(uuid, entry) != null)
        {
            return false;
        }
        persist(entry);
        return true;
    }

    // Adds a shadowed message the player sent after the trigger.
    void addLater(UUID uuid, JsonObject message)
    {
        Entry entry = entries.get(uuid);
        if (entry == null)
        {
            return;
        }
        synchronized (entry)
        {
            JsonArray later = entry.state().getAsJsonArray("later");
            later.add(message);
            if (later.size() > LATER_LIMIT)
            {
                later.remove(0);
                int dropped = entry.state().has("later_dropped") ? entry.state().get("later_dropped").getAsInt() : 0;
                entry.state().addProperty("later_dropped", dropped + 1);
            }
        }
        persist(entry);
    }

    // Finishes queued writes, then names every player whose latest state could not be saved.
    void close()
    {
        writer.shutdown();
        try
        {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS))
            {
                writer.shutdownNow();
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        for (Entry entry : entries.values())
        {
            if (entry.unsaved())
            {
                logger.error("The chat safety pending state of {} ({}) was not saved; the database holds an older"
                        + " state or none", entry.name(), entry.uuid());
            }
        }
    }

    // Queues one write of the entry's latest state; changes made before it runs share that write.
    private void persist(Entry entry)
    {
        if (!entry.markUnsaved())
        {
            return;
        }
        try
        {
            writer.execute(() -> write(entry));
        }
        catch (RejectedExecutionException ex)
        {
            logger.error("Chat safety stopped before it could save the pending state of {}", entry.uuid());
        }
    }

    private void write(Entry entry)
    {
        String state = entry.takeState();
        if (state == null)
        {
            return;
        }
        try
        {
            storage.jdbi().useTransaction(handle ->
            {
                int updated = handle.createUpdate("UPDATE " + table + " SET state = :state WHERE player_uuid = :uuid")
                        .bind("state", state)
                        .bind("uuid", entry.uuid().toString())
                        .execute();
                if (updated == 0)
                {
                    handle.createUpdate("INSERT INTO " + table + " (player_uuid, player_name, triggered_at, state)"
                                    + " VALUES (:uuid, :name, :triggered, :state)")
                            .bind("uuid", entry.uuid().toString())
                            .bind("name", entry.name())
                            .bind("triggered", entry.triggeredAt().toEpochMilli())
                            .bind("state", state)
                            .execute();
                }
            });
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to save the chat safety pending state of {}; retrying in {} seconds", entry.uuid(),
                    RETRY_SECONDS, ex);
            if (entry.markUnsaved())
            {
                writer.schedule(() -> write(entry), RETRY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    private static JsonObject parse(String uuid, String state)
    {
        try
        {
            if (JsonParser.parseString(state) instanceof JsonObject json && json.get("later") instanceof JsonArray)
            {
                return json;
            }
        }
        catch (JsonParseException ex)
        {
            throw new IllegalStateException("The chat safety pending state of " + uuid + " is not valid JSON", ex);
        }
        throw new IllegalStateException("The chat safety pending state of " + uuid + " is not an object with a 'later' list");
    }

    /**
     * One pending player. Mutate {@code state} only while holding the entry's monitor.
     */
    static final class Entry
    {
        private final UUID uuid;
        private final String name;
        private final Instant triggeredAt;
        private final JsonObject state;
        // Guarded by this entry's monitor: a write of the latest state is queued or waiting to be retried.
        private boolean unsaved;
        // Guarded by this entry's monitor: the row is deleted, so no write may insert it again.
        private boolean removed;

        private Entry(UUID uuid, String name, Instant triggeredAt, JsonObject state)
        {
            this.uuid = uuid;
            this.name = name;
            this.triggeredAt = triggeredAt;
            this.state = state;
        }

        UUID uuid()
        {
            return uuid;
        }

        String name()
        {
            return name;
        }

        Instant triggeredAt()
        {
            return triggeredAt;
        }

        JsonObject state()
        {
            return state;
        }

        // Returns true when no write was queued yet, so the caller queues one.
        private synchronized boolean markUnsaved()
        {
            boolean queue = !unsaved;
            unsaved = true;
            return queue;
        }

        // The state to write, or null once the row is deleted.
        @Nullable
        private synchronized String takeState()
        {
            unsaved = false;
            return removed ? null : state.toString();
        }

        private synchronized boolean unsaved()
        {
            return unsaved && !removed;
        }

        private synchronized void markRemoved()
        {
            removed = true;
        }
    }
}
