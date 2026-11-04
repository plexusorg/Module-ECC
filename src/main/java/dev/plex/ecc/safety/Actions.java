package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.plex.api.logging.LoggingApi;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * its label. A failed action is acknowledged as failed and applied again on a backoff, before any later action for
 * the same player; other players' actions go on meanwhile. Each action carried out is shown to staff once. The loop
 * also replaces the mute set from the server at start and after every outage, and saves the mute set's changes.
 */
final class Actions
{
    private static final long FIRST_RETRY_MILLIS = 1000;
    private static final long LAST_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(30);
    // The gap between polls while the server still holds an action this loop has not finished, since a poll returns
    // at once then; and after a poll that came back empty at once.
    private static final long PACE_MILLIS = 2000;
    private static final long STOP_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final int DETAIL_LIMIT = 500;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final EccServer server;
    private final MuteSet muted;
    private final Bans bans;
    private final Alerts alerts;
    private final LoggingApi logging;
    private final Logger logger;
    private final Thread loop;
    // The rest is used by the loop only. Actions received and not yet finished, by id, oldest first.
    private final Map<String, Action> unfinished = new LinkedHashMap<>();
    // Ids of unfinished actions already carried out and shown, so applying one again does not show it again.
    private final Set<String> shown = new HashSet<>();
    // Ids this loop is done with although the server may still hold them: actions that cannot be carried out, and
    // done actions whose acknowledgement the server refused.
    private final Set<String> settled = new HashSet<>();
    // One retry clock for everything that failed: unfinished actions, the mute set's refresh and its saving.
    private long retryMillis = FIRST_RETRY_MILLIS;
    private long retryAt = System.nanoTime();
    private volatile int unacknowledged;

    Actions(EccServer server, MuteSet muted, Bans bans, Alerts alerts, LoggingApi logging, Logger logger)
    {
        this.server = server;
        this.muted = muted;
        this.bans = bans;
        this.alerts = alerts;
        this.logging = logging;
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
        long backoff = FIRST_RETRY_MILLIS;
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                Next next = cycle();
                if (next == Next.BACKOFF)
                {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, LAST_RETRY_MILLIS);
                    continue;
                }
                backoff = FIRST_RETRY_MILLIS;
                if (next == Next.PACE)
                {
                    Thread.sleep(PACE_MILLIS);
                }
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

    // One poll and the actions it returned.
    private Next cycle() throws InterruptedException
    {
        boolean due = System.nanoTime() - retryAt >= 0;
        boolean failed = false;
        long started = System.nanoTime();
        JsonArray polled;
        try
        {
            if (due)
            {
                failed = !refreshMuted();
            }
            polled = server.actions();
        }
        catch (EccServer.Unavailable ex)
        {
            return Next.BACKOFF;
        }
        catch (EccServer.Rejected ex)
        {
            logging.error("Unable to read ECC-Server's actions: it {0}", MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
            return Next.BACKOFF;
        }
        boolean fast = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < PACE_MILLIS / 2;
        // Unfinished actions not yet due to be retried still hold back their player's later actions.
        Set<UUID> blocked = new HashSet<>();
        Map<String, Action> batch = new LinkedHashMap<>();
        for (Action action : unfinished.values())
        {
            if (due)
            {
                batch.put(action.idText(), action);
            }
            else
            {
                blocked.add(action.uuid());
            }
        }
        List<Action> received = parse(polled);
        // The server still holds an action this loop is done with.
        boolean held = received.size() < polled.size();
        for (Action action : received)
        {
            if (!unfinished.containsKey(action.idText()))
            {
                batch.putIfAbsent(action.idText(), action);
            }
        }
        Next next = apply(new ArrayList<>(batch.values()), blocked, due ? 0 : unfinished.size());
        if (due)
        {
            failed |= !save();
            retried(failed || next == Next.PACE);
        }
        // NOW means nothing is unfinished; the server may still hold a settled action, or answer at once.
        return next == Next.NOW && (held || fast && polled.isEmpty()) ? Next.PACE : next;
    }

    // Moves the retry clock after a cycle that retried what had failed.
    private void retried(boolean failed)
    {
        if (!failed)
        {
            retryMillis = FIRST_RETRY_MILLIS;
            return;
        }
        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis);
        retryMillis = Math.min(retryMillis * 2, LAST_RETRY_MILLIS);
    }

    // Applies the actions in order, skipping a blocked player's. PACE when any stays unfinished; BACKOFF when an
    // acknowledgement could not reach the server.
    private Next apply(List<Action> batch, Set<UUID> blocked, int waiting) throws InterruptedException
    {
        int done = 0;
        boolean failed = false;
        unacknowledged = waiting + batch.size();
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
            if (ok && shown.add(action.idText()))
            {
                show(action);
            }
            if (!acknowledge(action, ok, detail))
            {
                // Applied again once the server answers; every action is idempotent.
                unfinished.putIfAbsent(action.idText(), action);
                unacknowledged = waiting + batch.size() - done;
                return Next.BACKOFF;
            }
            if (ok)
            {
                unfinished.remove(action.idText());
                shown.remove(action.idText());
                done++;
            }
            else
            {
                unfinished.put(action.idText(), action);
                blocked.add(action.uuid());
                failed = true;
            }
        }
        unacknowledged = unfinished.size();
        return failed || !unfinished.isEmpty() ? Next.PACE : Next.NOW;
    }

    // A mute or unmute takes effect in memory at once; the mute set is saved once the cycle's actions are applied.
    private String carryOut(Action action) throws Bans.Failed, InterruptedException
    {
        return switch (action.type())
        {
            case MUTE ->
            {
                muted.add(action.uuid(), action.name());
                yield "muted";
            }
            case UNMUTE ->
            {
                muted.remove(action.uuid());
                yield "unmuted";
            }
            case BAN -> bans.ban(action);
            case NOTICE -> "shown";
        };
    }

    // An alert that cannot be shown is logged; the action it reports is still acknowledged as done.
    private void show(Action action)
    {
        try
        {
            alerts.show(action);
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to show the chat safety alert of action {}", action.idText(), ex);
        }
    }

    // False only when the server is unreachable. A refused acknowledgement is final: sending it again gets the same
    // answer.
    private boolean acknowledge(Action action, boolean ok, String detail) throws InterruptedException
    {
        try
        {
            server.ack(action.id(), ok, detail.length() > DETAIL_LIMIT ? detail.substring(0, DETAIL_LIMIT) : detail);
        }
        catch (EccServer.Unavailable ex)
        {
            return false;
        }
        catch (EccServer.Rejected ex)
        {
            logging.error("ECC-Server refused the acknowledgement of action {0}: it {1}", MINI_MESSAGE.escapeTags(String.valueOf(action.idText())),
                    MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
            if (ok)
            {
                settled.add(action.idText());
            }
        }
        return true;
    }

    // Saves the mute set's changes, such as a mute or a kicked player leaving it.
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
            logging.error("Unable to read ECC-Server's mute set: it {0}", MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
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

    // The polled actions to carry out, in the server's order.
    private List<Action> parse(JsonArray polled) throws InterruptedException
    {
        List<Action> received = new ArrayList<>();
        for (JsonElement element : polled)
        {
            Action action = parse(element);
            if (action != null && !settled.contains(action.idText()))
            {
                received.add(action);
            }
        }
        return received;
    }

    // The action, or null when it cannot be carried out. Such an action is reported and acknowledged as failed once,
    // since it cannot change.
    @Nullable
    private Action parse(JsonElement element) throws InterruptedException
    {
        JsonObject json = element instanceof JsonObject object ? object : new JsonObject();
        JsonElement id = json.get("id");
        if (id == null || !id.isJsonPrimitive() || id.getAsString().isEmpty())
        {
            if (settled.add(String.valueOf(element)))
            {
                logging.error("ECC-Server sent an action without an id, which cannot be carried out: {0}",
                        MINI_MESSAGE.escapeTags(String.valueOf(element)));
            }
            return null;
        }
        String invalidReason = null;
        Type type = Type.of(string(json, "type"));
        UUID uuid = uuid(json);
        String name = string(json, "name");
        String role = string(json, "role");
        Instant kickAt = null;
        if (type == null || uuid == null || name == null)
        {
            invalidReason = "type, uuid or name is missing or invalid";
        }
        else if (type == Type.BAN)
        {
            kickAt = instant(string(json, "kick_at"));
            if (kickAt == null || !Bans.OFFENDER.equals(role) && !Bans.AT_RISK.equals(role))
            {
                invalidReason = "a ban needs role offender or at_risk and an RFC 3339 kick_at";
            }
        }
        if (invalidReason == null)
        {
            return new Action(id, id.getAsString(), type, uuid, name, role, kickAt, why(json));
        }
        reportInvalid(id, element, invalidReason);
        return null;
    }

    // Logs an action that cannot be carried out and acknowledges it as failed, until the acknowledgement is delivered.
    private void reportInvalid(JsonElement id, JsonElement element, String reason) throws InterruptedException
    {
        if (settled.contains(id.getAsString()))
        {
            return;
        }
        logging.error("ECC-Server sent action {0}, which cannot be carried out: {1}", MINI_MESSAGE.escapeTags(String.valueOf(element)),
                MINI_MESSAGE.escapeTags(String.valueOf(reason)));
        try
        {
            server.ack(id, false, "invalid action: " + reason);
        }
        catch (EccServer.Unavailable ex)
        {
            // Acknowledged at the next poll that returns it.
            return;
        }
        catch (EccServer.Rejected ex)
        {
            logging.error("ECC-Server refused the acknowledgement of invalid action {0}: it {1}", MINI_MESSAGE.escapeTags(String.valueOf(id)),
                    MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
        }
        settled.add(id.getAsString());
    }

    // Why the action was made, for staff only: a field that is missing is shown as unknown, never refused.
    private static Why why(JsonObject json)
    {
        JsonObject why = json.get("why") instanceof JsonObject object ? object : new JsonObject();
        return new Why(string(why, "channel"), string(why, "text"), string(why, "cause"), string(why, "kind"));
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

    // When the loop polls again: at once, after the pace gap, or after the growing backoff.
    private enum Next
    {
        NOW, PACE, BACKOFF
    }

    // A notice is only shown to staff.
    enum Type
    {
        MUTE, UNMUTE, BAN, NOTICE;

        @Nullable
        static Type of(@Nullable String value)
        {
            return switch (value)
            {
                case "mute" -> MUTE;
                case "unmute" -> UNMUTE;
                case "ban" -> BAN;
                case "notice" -> NOTICE;
                case null, default -> null;
            };
        }
    }

    /**
     * One action as ECC-Server sent it. {@code id} is kept as sent, for the acknowledgement. {@code role} and
     * {@code kickAt} are set on bans only.
     */
    record Action(JsonElement id, String idText, Type type, UUID uuid, String name, @Nullable String role,
                  @Nullable Instant kickAt, Why why)
    {
    }

    /**
     * An action's {@code why}: a mute's {@code channel} and {@code text}, an unmute's or a ban's {@code cause}, a
     * notice's {@code kind}.
     */
    record Why(@Nullable String channel, @Nullable String text, @Nullable String cause, @Nullable String kind)
    {
    }
}
