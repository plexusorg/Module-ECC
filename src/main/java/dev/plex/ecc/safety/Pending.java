package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.plex.api.storage.ModuleStorage;
import org.apache.logging.log4j.Logger;
import org.jdbi.v3.core.JdbiException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Players the fast check triggered on, waiting for the slow check. The database row is authoritative; this owner
 * keeps every row in memory, applies the shadow mute from it, and writes each change back on the I/O executor.
 *
 * <p>A row's {@code state} JSON holds what the slow check needs: the trigger message, the decision parameters and
 * bundle version it was judged by, the triggering conversation's transcript and the player's own recent messages
 * (each with author, time, order and score), and the player's shadowed messages since the trigger.</p>
 */
final class Pending
{
    private static final long RETRY_SECONDS = 30;

    private final ModuleStorage storage;
    private final String table;
    private final ScheduledExecutorService io;
    private final Logger logger;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    Pending(ModuleStorage storage, ScheduledExecutorService io, Logger logger)
    {
        this.storage = storage;
        this.table = storage.table("pending");
        this.io = io;
        this.logger = logger;
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

    // Records a trigger. Returns false when the player is already pending.
    boolean add(UUID uuid, String name, Instant triggeredAt, JsonObject state)
    {
        Entry entry = new Entry(uuid, name, triggeredAt, state);
        if (entries.putIfAbsent(uuid, entry) != null)
        {
            return false;
        }
        persist(entry, true);
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
            entry.state().getAsJsonArray("later").add(message);
        }
        persist(entry, false);
    }

    private void persist(Entry entry, boolean insert)
    {
        io.execute(() -> write(entry, insert));
    }

    private void write(Entry entry, boolean insert)
    {
        if (entries.get(entry.uuid()) != entry)
        {
            return;
        }
        String state;
        synchronized (entry)
        {
            state = entry.state().toString();
        }
        try
        {
            storage.jdbi().useTransaction(handle ->
            {
                int updated = insert ? 0 : handle.createUpdate("UPDATE " + table + " SET state = :state WHERE player_uuid = :uuid")
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
        catch (JdbiException ex)
        {
            if (io.isShutdown())
            {
                logger.error("Unable to save the chat safety pending state of {} before shutdown", entry.uuid(), ex);
                return;
            }
            logger.error("Unable to save the chat safety pending state of {}; retrying in {} seconds", entry.uuid(),
                    RETRY_SECONDS, ex);
            io.schedule(() -> write(entry, false), RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static JsonObject parse(String uuid, String state)
    {
        try
        {
            JsonObject json = JsonParser.parseString(state).getAsJsonObject();
            if (json.get("later") instanceof JsonArray)
            {
                return json;
            }
        }
        catch (JsonParseException | IllegalStateException ex)
        {
            throw new IllegalStateException("The chat safety pending state of " + uuid + " is not a JSON object", ex);
        }
        throw new IllegalStateException("The chat safety pending state of " + uuid + " has no 'later' list");
    }

    /**
     * One pending player. Mutate {@code state} only while holding the entry's monitor.
     */
    record Entry(UUID uuid, String name, Instant triggeredAt, JsonObject state)
    {
    }
}
