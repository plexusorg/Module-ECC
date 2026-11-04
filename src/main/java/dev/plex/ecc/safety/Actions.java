package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The action loop: one thread that long-polls ECC-Server for actions, carries each out and acknowledges it. Actions
 * are applied idempotently by id: a mute or unmute sets the mute set, and a ban's Plex entry carries the action id in
 * its label. A failed action is acknowledged as failed and applied again after a backoff, before any later action for
 * the same player. The loop also replaces the mute set from the server at start and after every outage, and saves
 * the mute set's changes.
 */
final class Actions
{
    private static final long FIRST_RETRY_MILLIS = 1000;
    private static final long LAST_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long STOP_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final EccServer server;
    private final MuteSet muted;
    private final Bans bans;
    private final Logger logger;
    private final Thread loop;
    // Written by the loop only. Actions received and not yet acknowledged as done, by id, oldest first.
    private final Map<String, Action> unfinished = new LinkedHashMap<>();
    private volatile int unacknowledged;

    Actions(EccServer server, MuteSet muted, Bans bans, Logger logger)
    {
        this.server = server;
        this.muted = muted;
        this.bans = bans;
        this.logger = logger;
        this.loop = Thread.ofPlatform().daemon().name("Plex-ECC-Actions").unstarted(this::run);
    }

    void start()
    {
        loop.start();
    }

    // Actions received and not yet acknowledged as done.
    int unacknowledged()
    {
        return unacknowledged;
    }

    void close()
    {
        loop.interrupt();
        try
        {
            loop.join(STOP_MILLIS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void run()
    {
        long retry = FIRST_RETRY_MILLIS;
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                if (cycle())
                {
                    retry = FIRST_RETRY_MILLIS;
                    continue;
                }
                Thread.sleep(retry);
                retry = Math.min(retry * 2, LAST_RETRY_MILLIS);
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException ex)
        {
            logger.error("Chat safety stopped carrying out ECC-Server's actions after a failure", ex);
        }
        finally
        {
            save();
        }
    }

    // One poll and the actions it returned. False when anything failed, so the loop backs off before the next.
    private boolean cycle() throws InterruptedException
    {
        boolean clean;
        JsonArray polled;
        try
        {
            // Both run: a refused mute set does not stop saving or polling.
            clean = refreshMuted() & save();
            polled = server.actions();
        }
        catch (EccServer.Unavailable ex)
        {
            return false;
        }
        catch (EccServer.Rejected ex)
        {
            logger.error("Unable to read ECC-Server's actions: it {}", ex.getMessage());
            return false;
        }
        Map<String, Action> batch = new LinkedHashMap<>(unfinished);
        for (JsonElement element : polled)
        {
            Action action = parse(element);
            if (action == null)
            {
                clean = false;
                continue;
            }
            batch.putIfAbsent(action.idText(), action);
        }
        return apply(new ArrayList<>(batch.values())) && clean;
    }

    // Applies the actions in order. A player's later actions wait while an earlier one of theirs is unfinished.
    private boolean apply(List<Action> batch) throws InterruptedException
    {
        Set<UUID> blocked = new HashSet<>();
        int done = 0;
        unacknowledged = batch.size();
        for (Action action : batch)
        {
            if (blocked.contains(action.uuid()))
            {
                unfinished.putIfAbsent(action.idText(), action);
                continue;
            }
            boolean ok;
            String detail;
            try
            {
                detail = carryOut(action);
                ok = true;
            }
            catch (Bans.Failed ex)
            {
                detail = ex.getMessage();
                ok = false;
            }
            catch (RuntimeException ex)
            {
                logger.error("Unable to carry out ECC-Server's action {}; it is applied again", action.idText(), ex);
                detail = String.valueOf(ex);
                ok = false;
            }
            if (!acknowledge(action, ok, detail))
            {
                // Applied again once the server answers; every action is idempotent.
                unfinished.putIfAbsent(action.idText(), action);
                unacknowledged = batch.size() - done;
                return false;
            }
            if (ok)
            {
                unfinished.remove(action.idText());
                done++;
                unacknowledged = batch.size() - done;
            }
            else
            {
                unfinished.put(action.idText(), action);
                blocked.add(action.uuid());
            }
        }
        unacknowledged = unfinished.size();
        return unfinished.isEmpty();
    }

    private String carryOut(Action action) throws Bans.Failed, InterruptedException
    {
        return switch (action.type())
        {
            case MUTE ->
            {
                muted.add(action.uuid(), action.name());
                muted.flush();
                yield "muted";
            }
            case UNMUTE ->
            {
                muted.remove(action.uuid());
                muted.flush();
                yield "unmuted";
            }
            case BAN -> bans.ban(action);
        };
    }

    private boolean acknowledge(Action action, boolean ok, String detail) throws InterruptedException
    {
        try
        {
            server.ack(action.id(), ok, detail);
            return true;
        }
        catch (EccServer.Unavailable ex)
        {
            return false;
        }
        catch (EccServer.Rejected ex)
        {
            logger.error("ECC-Server refused the acknowledgement of action {}: it {}", action.idText(), ex.getMessage());
            return false;
        }
    }

    // Saves the mute set's changes, such as a kicked player leaving it.
    private boolean save()
    {
        try
        {
            muted.flush();
            return true;
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to save chat safety's mute set; it is saved again shortly", ex);
            return false;
        }
    }

    // Replaces the mute set from GET /muted when the link asks for it: at start, and after every outage. False when
    // the server refused or returned an invalid set; actions are still carried out then.
    private boolean refreshMuted() throws EccServer.Unavailable, InterruptedException
    {
        if (!server.takeRefresh())
        {
            return true;
        }
        try
        {
            muted.replace(mutedPlayers(server.muted()));
            return true;
        }
        catch (EccServer.Unavailable ex)
        {
            server.refreshFailed();
            throw ex;
        }
        catch (EccServer.Rejected ex)
        {
            server.refreshFailed();
            logger.error("Unable to read ECC-Server's mute set: it {}", ex.getMessage());
            return false;
        }
    }

    private static Map<UUID, String> mutedPlayers(JsonArray players) throws EccServer.Rejected
    {
        Map<UUID, String> current = new HashMap<>();
        for (JsonElement element : players)
        {
            if (!(element instanceof JsonObject player))
            {
                throw new EccServer.Rejected("returned a mute set entry that is not an object: " + element);
            }
            UUID uuid = uuid(player);
            String name = string(player, "name");
            if (uuid == null || name == null)
            {
                throw new EccServer.Rejected("returned a mute set entry without a valid uuid and name: " + player);
            }
            current.put(uuid, name);
        }
        return current;
    }

    // The action, or null after reporting why it cannot be carried out. It is never retried, since it cannot change.
    @Nullable
    private Action parse(JsonElement element) throws InterruptedException
    {
        JsonObject json = element instanceof JsonObject object ? object : new JsonObject();
        JsonElement id = json.get("id");
        if (id == null || !id.isJsonPrimitive() || id.getAsString().isEmpty())
        {
            logger.error("ECC-Server sent an action without an id, which cannot be carried out: {}", element);
            return null;
        }
        String invalid = null;
        Type type = Type.of(string(json, "type"));
        UUID uuid = uuid(json);
        String name = string(json, "name");
        String role = string(json, "role");
        Instant kickAt = null;
        if (type == null || uuid == null || name == null)
        {
            invalid = "type, uuid or name is missing or invalid";
        }
        else if (type == Type.BAN)
        {
            kickAt = instant(string(json, "kick_at"));
            if (kickAt == null || !Bans.OFFENDER.equals(role) && !Bans.AT_RISK.equals(role))
            {
                invalid = "a ban needs role offender or at_risk and an RFC 3339 kick_at";
            }
        }
        if (invalid == null)
        {
            return new Action(id, id.getAsString(), type, uuid, name, role, kickAt);
        }
        logger.error("ECC-Server sent action {}, which cannot be carried out: {}", element, invalid);
        try
        {
            server.ack(id, false, "invalid action: " + invalid);
        }
        catch (EccServer.Unavailable | EccServer.Rejected ex)
        {
            // Sent again with the action; the error above names it.
        }
        return null;
    }

    @Nullable
    private static String string(JsonObject json, String key)
    {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    @Nullable
    private static UUID uuid(JsonObject json)
    {
        String value = string(json, "uuid");
        try
        {
            return value == null ? null : UUID.fromString(value);
        }
        catch (IllegalArgumentException ex)
        {
            return null;
        }
    }

    @Nullable
    private static Instant instant(@Nullable String value)
    {
        try
        {
            return value == null ? null : Instant.parse(value);
        }
        catch (DateTimeParseException ex)
        {
            return null;
        }
    }

    enum Type
    {
        MUTE, UNMUTE, BAN;

        @Nullable
        static Type of(@Nullable String value)
        {
            return switch (value)
            {
                case "mute" -> MUTE;
                case "unmute" -> UNMUTE;
                case "ban" -> BAN;
                case null, default -> null;
            };
        }
    }

    /**
     * One action as ECC-Server sent it. {@code id} is kept as sent, for the acknowledgement. {@code role} and
     * {@code kickAt} are set on bans only.
     */
    record Action(JsonElement id, String idText, Type type, UUID uuid, String name, @Nullable String role,
                  @Nullable Instant kickAt)
    {
    }
}
