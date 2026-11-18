package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.plex.api.logging.LoggingApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The action loop: one thread that long-polls ECC-Server for actions, carries each out and acknowledges it. A release
 * delivers the held lines it names, in the order they were written; a warn shows the player the warning, unless they
 * are quarantined; a ban writes the Plex entry, kicks and drops the player's held lines; a notice is shown to staff.
 * Every action is safe to apply twice: a released line is no longer held, and a ban's Plex entry carries the action id
 * in its label. A failed action is acknowledged as failed and applied again on a backoff, before any later action for
 * the same player; other players' actions go on meanwhile. Each action carried out is shown to staff once.
 */
final class Actions
{
    // The gap between polls while the server still holds an action this loop has not finished, since a poll returns
    // at once then; and after a poll that came back empty at once.
    private static final long PACE_MILLIS = 2000;
    private static final long STOP_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final int DETAIL_LIMIT = 500;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final EccServer server;
    private final Held held;
    private final Bans bans;
    private final Alerts alerts;
    private final Component warning;
    // Whether a player is quarantined now: they are shown no warning.
    private final Predicate<UUID> quarantined;
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
    private long retryMillis = EccServer.FIRST_RETRY_MILLIS;
    private long retryAt = System.nanoTime();
    private volatile int unacknowledged;

    Actions(EccServer server, Held held, Bans bans, Alerts alerts, Component warning, Predicate<UUID> quarantined,
            LoggingApi logging, Logger logger)
    {
        this.server = server;
        this.held = held;
        this.bans = bans;
        this.alerts = alerts;
        this.warning = warning;
        this.quarantined = quarantined;
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
        Backoff backoff = server.backoff();
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                Next next = cycle();
                if (next == Next.BACKOFF)
                {
                    backoff.pause();
                    continue;
                }
                backoff.reset();
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
    }

    // One poll and the actions it returned.
    private Next cycle() throws InterruptedException
    {
        boolean due = System.nanoTime() - retryAt >= 0;
        long started = System.nanoTime();
        JsonArray polled;
        try
        {
            // The poll may be held for 30 seconds; a restarted server is seen by its boot id at once.
            server.checkBoot();
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
        boolean stillHeld = received.size() < polled.size();
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
            retried(next == Next.PACE);
        }
        // NOW means nothing is unfinished; the server may still hold a settled action, or answer at once.
        return next == Next.NOW && (stillHeld || fast && polled.isEmpty()) ? Next.PACE : next;
    }

    // Moves the retry clock after a cycle that retried what had failed.
    private void retried(boolean failed)
    {
        if (!failed)
        {
            retryMillis = EccServer.FIRST_RETRY_MILLIS;
            return;
        }
        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis);
        retryMillis = Math.min(retryMillis * 2, EccServer.LAST_RETRY_MILLIS);
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
                show(action, detail);
            }
            if (!acknowledge(action, ok, detail))
            {
                // Applied again once the server answers; every action is safe to apply twice.
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

    private String carryOut(Action action) throws Bans.Failed, InterruptedException
    {
        return switch (action.type())
        {
            case RELEASE ->
            {
                List<Held.Entry> released = held.release(action.uuid(), action.ids());
                released.forEach(entry -> entry.delivery().deliver());
                yield "delivered " + released.size() + " of " + action.ids().size() + " lines";
            }
            case WARN ->
            {
                Player player = Bukkit.getPlayer(action.uuid());
                if (player == null)
                {
                    yield "offline, not shown";
                }
                if (quarantined.test(action.uuid()))
                {
                    yield "quarantined, not shown";
                }
                player.sendMessage(warning);
                yield "shown";
            }
            case NOTICE -> "shown to staff";
            case BAN ->
            {
                String banned = bans.ban(action);
                yield banned + "; dropped " + held.drop(action.uuid()) + " held lines";
            }
        };
    }

    // An alert that cannot be shown is logged; the action it reports is still acknowledged as done.
    private void show(Action action, String detail)
    {
        try
        {
            alerts.show(action, detail);
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
        Type type = Type.of(string(json, "type"));
        UUID uuid = uuid(json);
        String account = string(json, "account");
        List<String> ids = ids(json);
        String text = string(json, "text");
        String invalidReason = invalid(type, uuid, ids, text);
        if (invalidReason == null)
        {
            return new Action(id, id.getAsString(), type, uuid, account, ids == null ? List.of() : ids, text);
        }
        reportInvalid(id, element, invalidReason);
        return null;
    }

    // Why an action lacks a field its type needs, or null when it has them all.
    @Nullable
    private static String invalid(@Nullable Type type, @Nullable UUID uuid, @Nullable List<String> ids,
                                  @Nullable String text)
    {
        if (type == Type.NOTICE)
        {
            return text == null ? "a notice needs its text" : null;
        }
        if (type == null || uuid == null)
        {
            return "type or uuid is missing or invalid";
        }
        if (type == Type.RELEASE && ids == null)
        {
            return "a release needs ids, a list of line ids";
        }
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

    // A list of strings, or null when the field is missing or holds anything else.
    @Nullable
    private static List<String> ids(JsonObject json)
    {
        if (!(json.get("ids") instanceof JsonArray array))
        {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement element : array)
        {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            {
                return null;
            }
            ids.add(element.getAsString());
        }
        return ids;
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

    // When the loop polls again: at once, after the pace gap, or after the growing backoff.
    private enum Next
    {
        NOW, PACE, BACKOFF
    }

    enum Type
    {
        RELEASE, WARN, BAN, NOTICE;

        @Nullable
        static Type of(@Nullable String value)
        {
            return switch (value)
            {
                case "release" -> RELEASE;
                case "warn" -> WARN;
                case "ban" -> BAN;
                case "notice" -> NOTICE;
                case null, default -> null;
            };
        }
    }

    /**
     * One action as ECC-Server sent it. {@code id} is kept as sent, for the acknowledgement. {@code uuid} is set on
     * every type but a notice, with {@code account}, the account name the module sent as the lines' speaker;
     * {@code ids} on releases, {@code text} on notices.
     */
    record Action(JsonElement id, String idText, Type type, @Nullable UUID uuid, @Nullable String account,
                  List<String> ids, @Nullable String text)
    {
    }
}
