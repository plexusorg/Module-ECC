package dev.plex.ecc.safety;

import dev.plex.api.storage.ModuleStorage;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The players ECC-Server has muted, and banned players until their kick. Capture reads the set in memory; the
 * database row of each player follows it, written by the action loop, so while ECC-Server is unreachable after a
 * restart the saved copy decides who is muted.
 */
final class MuteSet
{
    private final ModuleStorage storage;
    private final String table;
    private final Object lock = new Object();
    // Changed only while holding lock, together with dirty.
    private final Map<UUID, String> muted = new ConcurrentHashMap<>();
    // Guarded by lock. Players whose row may differ from the set.
    private final Set<UUID> dirty = new HashSet<>();

    MuteSet(ModuleStorage storage)
    {
        this.storage = storage;
        this.table = storage.table("muted");
    }

    // Applies the module's migrations and reads the saved set. Runs once, before capture starts.
    void load() throws SQLException
    {
        storage.migrations().run();
        List<Map.Entry<UUID, String>> rows = storage.jdbi().withHandle(handle -> handle
                .createQuery("SELECT player_uuid, player_name FROM " + table)
                .map((row, context) -> Map.entry(UUID.fromString(row.getString("player_uuid")),
                        row.getString("player_name")))
                .list());
        rows.forEach(row -> muted.put(row.getKey(), row.getValue()));
    }

    boolean contains(UUID uuid)
    {
        return muted.containsKey(uuid);
    }

    int size()
    {
        return muted.size();
    }

    void add(UUID uuid, String name)
    {
        synchronized (lock)
        {
            muted.put(uuid, name);
            dirty.add(uuid);
        }
    }

    void remove(UUID uuid)
    {
        synchronized (lock)
        {
            muted.remove(uuid);
            dirty.add(uuid);
        }
    }

    // Makes the set exactly the server's.
    void replace(Map<UUID, String> current)
    {
        synchronized (lock)
        {
            for (UUID uuid : muted.keySet())
            {
                if (!current.containsKey(uuid))
                {
                    muted.remove(uuid);
                    dirty.add(uuid);
                }
            }
            current.forEach((uuid, name) ->
            {
                if (!Objects.equals(muted.put(uuid, name), name))
                {
                    dirty.add(uuid);
                }
            });
        }
    }

    /**
     * Writes every changed player's row. Called by the action loop, and once at stop after the loop has ended.
     *
     * @throws RuntimeException when the database fails; the rows not yet written stay changed for the next flush
     */
    void flush()
    {
        Set<UUID> changed;
        synchronized (lock)
        {
            changed = Set.copyOf(dirty);
        }
        for (UUID uuid : changed)
        {
            String name;
            synchronized (lock)
            {
                dirty.remove(uuid);
                name = muted.get(uuid);
            }
            try
            {
                write(uuid, name);
            }
            catch (RuntimeException ex)
            {
                synchronized (lock)
                {
                    dirty.add(uuid);
                }
                throw ex;
            }
        }
    }

    // A null name deletes the player's row.
    private void write(UUID uuid, @Nullable String name)
    {
        storage.jdbi().useTransaction(handle ->
        {
            handle.createUpdate("DELETE FROM " + table + " WHERE player_uuid = :uuid")
                    .bind("uuid", uuid.toString())
                    .execute();
            if (name != null)
            {
                handle.createUpdate("INSERT INTO " + table + " (player_uuid, player_name) VALUES (:uuid, :name)")
                        .bind("uuid", uuid.toString())
                        .bind("name", name)
                        .execute();
            }
        });
    }
}
