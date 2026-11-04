package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The slow check of each pending player: Kimi on Bedrock reports evidence on the triggering conversation, and
 * {@link Evidence} decides every participant's outcome. It runs as soon as a player triggers, again on each new
 * shadowed line of a player it is not sure about yet, and for every pending player when chat safety starts. A failed
 * call is retried with backoff until Bedrock answers; the shadow mute never lifts on a failure.
 *
 * <p>The transcript is the pending state's conversation up to and including the trigger, followed by the player's own
 * later lines. The views are the whole transcript and the transcript cut at each decided player's trigger. Thresholds
 * are those of the bundle the trigger was scored with; the system prompt is the current bundle's, which is the same
 * bundle unless the host has replaced it since, and every call records which bundle's prompt it used.</p>
 */
final class SlowCheck
{
    private static final int WORKERS = 4;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration LAST_RETRY = Duration.ofMinutes(10);
    private static final Duration PROMPT_WAIT = Duration.ofSeconds(5);
    private static final long TICK_SECONDS = 15;
    private static final String HELD_MILLIS = "held_ms";
    private static final Map<String, String> CHANNELS = Map.of(ChatSafety.CHAT, "public chat", ChatSafety.ME,
            "public chat", ChatSafety.NICK, "public chat", ChatSafety.MSG, "private messages", ChatSafety.MAIL, "mail",
            ChatSafety.SIGN, "signs placed in the world", ChatSafety.BOOK, "a written book", ChatSafety.ANVIL,
            "item names");

    private final Pending pending;
    private final RecordLog record;
    private final Bedrock bedrock;
    private final Outcomes outcomes;
    private final Supplier<Decision> decisions;
    private final Logger logger;
    private final long capMillis;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(WORKERS,
            Thread.ofPlatform().daemon().name("Plex-ECC-Slow-", 0).factory());
    private final Object lock = new Object();
    // Guarded by lock.
    private final Map<UUID, Case> cases = new HashMap<>();
    private @Nullable Instant failingSince;
    private String failure = "";
    private @Nullable Instant answeredSince;
    private boolean keyRejected;
    private boolean closed;

    SlowCheck(Pending pending, RecordLog record, Bedrock bedrock, Outcomes outcomes, Supplier<Decision> decisions,
              Logger logger, Duration cap)
    {
        this.pending = pending;
        this.record = record;
        this.bedrock = bedrock;
        this.outcomes = outcomes;
        this.decisions = decisions;
        this.logger = logger;
        this.capMillis = cap.toMillis();
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Checks a pending player: at their trigger, at start, and on each new shadowed line. A check already running for
     * them runs once more when it ends, with the newer lines.
     */
    void check(UUID uuid, String name)
    {
        Case pendingCase;
        synchronized (lock)
        {
            if (closed || !pending.contains(uuid))
            {
                return;
            }
            pendingCase = cases.computeIfAbsent(uuid, key -> new Case(uuid, name));
            if (pendingCase.running)
            {
                pendingCase.rerun = true;
                return;
            }
            pendingCase.running = true;
        }
        submit(pendingCase, Duration.ZERO);
    }

    private void submit(Case pendingCase, Duration delay)
    {
        try
        {
            executor.schedule(() -> run(pendingCase), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException ex)
        {
            logger.debug("Chat safety is closing; the slow check of {} resumes at the next start", pendingCase.name);
        }
    }

    private void run(Case pendingCase)
    {
        Result result;
        try
        {
            result = judge(pendingCase);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return;
        }
        catch (RuntimeException ex)
        {
            logger.error("The slow check of {} failed; retrying", pendingCase.name, ex);
            result = Result.RETRY;
        }
        synchronized (lock)
        {
            if (closed)
            {
                return;
            }
            switch (result)
            {
                case DONE -> cases.remove(pendingCase.uuid);
                case WAIT ->
                {
                    pendingCase.runMillis = 0;
                    submit(pendingCase, PROMPT_WAIT);
                }
                case RETRY ->
                {
                    // The retry reads the latest lines, so a rerun asked for meanwhile is part of it.
                    pendingCase.failing = true;
                    pendingCase.rerun = false;
                    // A failed run was not real verdict time.
                    pendingCase.runMillis = 0;
                    Duration wait = pendingCase.retry;
                    pendingCase.retry = wait.multipliedBy(2).compareTo(LAST_RETRY) > 0 ? LAST_RETRY : wait.multipliedBy(2);
                    submit(pendingCase, wait);
                }
                case HELD -> held(pendingCase);
            }
        }
    }

    // Guarded by lock.
    private void held(Case pendingCase)
    {
        pendingCase.failing = false;
        pendingCase.retry = FIRST_RETRY;
        pendingCase.heldMillis += pendingCase.runMillis;
        pendingCase.runMillis = 0;
        if (!pendingCase.held)
        {
            pendingCase.held = true;
            pendingCase.lastTick = System.currentTimeMillis();
        }
        // The cap ends the pending state even while the player keeps sending lines.
        if (pendingCase.heldMillis >= capMillis)
        {
            lift(pendingCase);
            return;
        }
        if (pendingCase.rerun)
        {
            pendingCase.rerun = false;
            submit(pendingCase, Duration.ZERO);
            return;
        }
        pendingCase.running = false;
    }

    // Ends a held player's pending state after the cap: real verdict time, not counting time while Bedrock failed.
    // Time while a re-check runs counts only once it ends with a verdict, since a call is known to fail only when it
    // ends.
    private void tick()
    {
        synchronized (lock)
        {
            long now = System.currentTimeMillis();
            for (Case pendingCase : List.copyOf(cases.values()))
            {
                if (pendingCase.held && !pendingCase.failing && failingSince == null)
                {
                    if (pendingCase.running)
                    {
                        pendingCase.runMillis += now - pendingCase.lastTick;
                    }
                    else
                    {
                        pendingCase.heldMillis += now - pendingCase.lastTick;
                    }
                }
                pendingCase.lastTick = now;
                if (pendingCase.held && !pendingCase.running && pendingCase.heldMillis >= capMillis)
                {
                    lift(pendingCase);
                }
            }
        }
    }

    // Guarded by lock.
    private void lift(Case pendingCase)
    {
        cases.remove(pendingCase.uuid);
        pending.remove(pendingCase.uuid);
        outcomes.action(pendingCase.uuid, pendingCase.name, "lifted", Evidence.HELD, "cap",
                pendingCase.heldMillis / 1000 + " seconds not sure");
    }

    private Result judge(Case pendingCase) throws InterruptedException
    {
        JsonObject state = pending.snapshot(pendingCase.uuid);
        if (state == null)
        {
            return Result.DONE;
        }
        Decision current = decisions.get();
        if (current == null)
        {
            return Result.WAIT;
        }
        synchronized (lock)
        {
            if (!pendingCase.loaded)
            {
                pendingCase.loaded = true;
                pendingCase.heldMillis = state.has(HELD_MILLIS) ? state.get(HELD_MILLIS).getAsLong() : 0;
            }
        }
        Transcript transcript = Transcript.of(state);
        JsonObject parameters = state.getAsJsonObject("decision");
        Check check = new Check(pendingCase, transcript, parameters.get("version").getAsString(), current);
        Map<String, Integer> decided = decided(check);
        Map<Integer, Map<String, Evidence.Kept>> views = views(check, decided);
        if (views == null)
        {
            return Result.RETRY;
        }
        Map<String, Evidence.Outcome> verdicts = decide(check, decided, views,
                parameters.get("clearing").getAsDouble(), parameters.get("corroboration").getAsDouble());
        return apply(check, verdicts);
    }

    // Each judged speaker this check decides, with their trigger's index or null; skipped speakers are recorded.
    private Map<String, Integer> decided(Check check)
    {
        Transcript transcript = check.transcript();
        Map<String, Integer> decided = new LinkedHashMap<>();
        for (String speaker : Evidence.speakers(transcript.lines()))
        {
            UUID uuid = transcript.uuids().get(speaker);
            String skip = null;
            if (uuid.equals(check.pendingCase().uuid))
            {
                decided.put(speaker, transcript.trigger());
                continue;
            }
            if (pending.contains(uuid))
            {
                skip = "pending";
            }
            else if (transcript.bypass().contains(speaker))
            {
                skip = "bypass";
            }
            if (skip == null)
            {
                decided.put(speaker, transcript.triggerOf().get(speaker));
            }
            else
            {
                verdict(check, speaker, null, new Evidence.Outcome("skipped", skip), Map.of());
            }
        }
        return decided;
    }

    // The validated evidence of every view, keyed by the view's last index; null when a call failed.
    @Nullable
    private Map<Integer, Map<String, Evidence.Kept>> views(Check check, Map<String, Integer> decided)
            throws InterruptedException
    {
        List<Evidence.Line> lines = check.transcript().lines();
        Set<Integer> cuts = new TreeSet<>();
        cuts.add(lines.size() - 1);
        decided.values().stream().filter(cut -> cut != null).forEach(cuts::add);
        Map<Integer, Map<String, Evidence.Kept>> views = new TreeMap<>();
        for (int cut : cuts)
        {
            List<Evidence.Line> view = lines.subList(0, cut + 1);
            String user = Evidence.userMessage(check.transcript().channel(), view);
            String key = check.current().version() + '\u0000' + user;
            Bedrock.Call call = check.pendingCase().answers.get(key);
            if (call == null)
            {
                call = bedrock.ask(check.current().slowCheckSystem(), user, Evidence.speakers(view));
                observe(call);
                check.tokensIn += call.tokensIn();
                check.tokensOut += call.tokensOut();
                recordCall(check, cut, call);
                if (!call.ok())
                {
                    return null;
                }
                check.pendingCase().answers.put(key, call);
            }
            views.put(cut, Evidence.validate(call.answer(), lines, cut, check.transcript().triggerOf()));
        }
        return views;
    }

    private Map<String, Evidence.Outcome> decide(Check check, Map<String, Integer> decided,
                                                 Map<Integer, Map<String, Evidence.Kept>> views, double clearing,
                                                 double corroboration)
    {
        List<Evidence.Line> lines = check.transcript().lines();
        Map<String, Evidence.Kept> full = views.get(lines.size() - 1);
        Map<String, Evidence.Outcome> verdicts = new LinkedHashMap<>();
        decided.forEach((speaker, trigger) ->
        {
            Evidence.Outcome outcome = trigger == null
                    ? Evidence.decideUntriggered(speaker, full, lines, corroboration)
                    : Evidence.decideTriggered(speaker, trigger, full, views.get(trigger), lines, clearing);
            verdicts.put(speaker, outcome);
            verdict(check, speaker, trigger, outcome, views);
        });
        return verdicts;
    }

    // Bans first, the other participants before the pending player, then ends the pending state.
    private Result apply(Check check, Map<String, Evidence.Outcome> verdicts) throws InterruptedException
    {
        Transcript transcript = check.transcript();
        UUID player = check.pendingCase().uuid;
        String self = null;
        boolean banned = true;
        for (Map.Entry<String, Evidence.Outcome> entry : verdicts.entrySet())
        {
            UUID uuid = transcript.uuids().get(entry.getKey());
            if (uuid.equals(player))
            {
                self = entry.getKey();
            }
            // A participant who triggered while this check ran is decided by their own check instead.
            else if (bans(entry.getValue()) && !pending.contains(uuid))
            {
                banned &= outcomes.ban(uuid, entry.getKey(), entry.getValue().role(), entry.getValue().cause());
            }
        }
        Evidence.Outcome outcome = verdicts.get(self);
        if (bans(outcome))
        {
            banned &= outcomes.ban(player, self, outcome.role(), outcome.cause());
        }
        if (!banned)
        {
            return Result.RETRY;
        }
        if (Evidence.HELD.equals(outcome.role()))
        {
            long held;
            synchronized (lock)
            {
                held = check.pendingCase().heldMillis;
            }
            pending.set(player, HELD_MILLIS, held);
            return Result.HELD;
        }
        pending.remove(player);
        if (Evidence.NOT_INVOLVED.equals(outcome.role()))
        {
            outcomes.action(player, self, "lifted", outcome.role(), outcome.cause(), null);
        }
        return Result.DONE;
    }

    private static boolean bans(Evidence.Outcome outcome)
    {
        return Evidence.OFFENDER.equals(outcome.role()) || Evidence.AT_RISK.equals(outcome.role());
    }

    // Tracks whether Bedrock answers, warning once when it starts failing and once when it answers again.
    private void observe(Bedrock.Call call)
    {
        synchronized (lock)
        {
            if (call.reached())
            {
                if (failingSince != null)
                {
                    logger.warn("Bedrock answers again; the slow check resumes");
                }
                failingSince = null;
                keyRejected = false;
                if (answeredSince == null)
                {
                    answeredSince = Instant.now();
                }
                return;
            }
            answeredSince = null;
            failure = call.errors().isEmpty() ? "" : call.errors().getLast();
            if (call.rejectedKey() && !keyRejected)
            {
                keyRejected = true;
                logger.error("Bedrock refused the request: AWS_BEARER_TOKEN_BEDROCK in chat safety's .env is expired"
                        + " or invalid, or its account has no access to the model ({}). Slow checks retry until a valid"
                        + " key is set and the module is reloaded", failure);
            }
            if (failingSince == null)
            {
                failingSince = Instant.now();
                logger.warn("Bedrock is failing ({}); pending players stay shadow-muted and their slow checks retry",
                        failure);
            }
        }
    }

    private void recordCall(Check check, int cut, Bedrock.Call call)
    {
        JsonObject line = base(check, "slow_call");
        line.addProperty("view_end", cut + 1);
        line.addProperty("whole", cut == check.transcript().lines().size() - 1);
        line.addProperty("ok", call.ok());
        line.addProperty("attempts", call.attempts());
        JsonArray errors = new JsonArray();
        call.errors().forEach(errors::add);
        line.add("errors", errors);
        line.addProperty("tokens_in", call.tokensIn());
        line.addProperty("tokens_out", call.tokensOut());
        line.addProperty("raw", call.raw());
        record.append(line);
    }

    private void verdict(Check check, String speaker, @Nullable Integer trigger, Evidence.Outcome outcome,
                         Map<Integer, Map<String, Evidence.Kept>> views)
    {
        Transcript transcript = check.transcript();
        JsonObject line = base(check, "slow_verdict");
        line.addProperty("speaker", speaker);
        line.addProperty("speaker_uuid", transcript.uuids().get(speaker).toString());
        if (trigger != null)
        {
            line.addProperty("trigger_line", trigger + 1);
        }
        line.addProperty("outcome", outcome.role());
        line.addProperty("cause", outcome.cause());
        JsonObject evidence = new JsonObject();
        views.forEach((cut, view) ->
        {
            Evidence.Kept kept = view.get(speaker);
            if (kept == null)
            {
                return;
            }
            JsonObject json = new JsonObject();
            JsonArray quotes = new JsonArray();
            for (Evidence.Quote quote : kept.quotes())
            {
                JsonObject item = new JsonObject();
                item.addProperty("line", quote.index() + 1);
                item.addProperty("tag", quote.tag());
                item.addProperty("text", transcript.lines().get(quote.index()).text());
                quotes.add(item);
            }
            json.add("quotes", quotes);
            json.addProperty("dropped", kept.dropped());
            json.addProperty("given", kept.given());
            JsonArray ages = new JsonArray();
            kept.ages().forEach(ages::add);
            json.add("ages", ages);
            evidence.add(String.valueOf(cut + 1), json);
        });
        line.add("evidence", evidence);
        line.addProperty("tokens_in", check.tokensIn);
        line.addProperty("tokens_out", check.tokensOut);
        record.append(line);
    }

    private static JsonObject base(Check check, String type)
    {
        JsonObject line = new JsonObject();
        line.addProperty("type", type);
        line.addProperty("ts", Instant.now().toString());
        line.addProperty("player", check.pendingCase().name);
        line.addProperty("player_uuid", check.pendingCase().uuid.toString());
        line.addProperty("bundle", check.bundle());
        line.addProperty("prompt_bundle", check.current().version());
        line.addProperty("lines", check.transcript().lines().size());
        return line;
    }

    Status status()
    {
        synchronized (lock)
        {
            List<String> checking = new ArrayList<>();
            List<String> held = new ArrayList<>();
            cases.values().forEach(pendingCase -> (pendingCase.held ? held : checking).add(pendingCase.name));
            checking.sort(String.CASE_INSENSITIVE_ORDER);
            held.sort(String.CASE_INSENSITIVE_ORDER);
            Boolean answering = failingSince != null ? Boolean.FALSE : answeredSince != null ? Boolean.TRUE : null;
            Instant since = failingSince != null ? failingSince : answeredSince;
            return new Status(answering, failure, since, checking, held);
        }
    }

    void close()
    {
        synchronized (lock)
        {
            closed = true;
        }
        executor.shutdownNow();
        try
        {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        bedrock.close();
    }

    /**
     * The slow check as {@code /ecc status} shows it. {@code answering} is null before the first call; {@code since}
     * is when Bedrock started answering or failing.
     */
    record Status(@Nullable Boolean answering, String failure, @Nullable Instant since, List<String> checking,
                  List<String> held)
    {
    }

    private enum Result
    {
        DONE, HELD, RETRY, WAIT
    }

    // One pending player's slow check. Fields other than answers are guarded by SlowCheck's lock; answers is used
    // only by the one run in progress.
    private static final class Case
    {
        private final UUID uuid;
        private final String name;
        // Calls that answered, by bundle and user message, so a re-run does not ask an unchanged view again.
        private final Map<String, Bedrock.Call> answers = new HashMap<>();
        private boolean running;
        private boolean rerun;
        private boolean held;
        private boolean failing;
        private boolean loaded;
        private long heldMillis;
        // Held time while the current re-check runs; added to heldMillis when it ends with a verdict.
        private long runMillis;
        private long lastTick;
        private Duration retry = FIRST_RETRY;

        private Case(UUID uuid, String name)
        {
            this.uuid = uuid;
            this.name = name;
        }
    }

    // One run of a check: what it judges and what its calls cost.
    private static final class Check
    {
        private final Case pendingCase;
        private final Transcript transcript;
        private final String bundle;
        private final Decision current;
        private long tokensIn;
        private long tokensOut;

        private Check(Case pendingCase, Transcript transcript, String bundle, Decision current)
        {
            this.pendingCase = pendingCase;
            this.transcript = transcript;
            this.bundle = bundle;
            this.current = current;
        }

        private Case pendingCase()
        {
            return pendingCase;
        }

        private Transcript transcript()
        {
            return transcript;
        }

        private String bundle()
        {
            return bundle;
        }

        private Decision current()
        {
            return current;
        }
    }

    /**
     * A pending state's transcript: the conversation up to and including the trigger, then the player's later lines.
     * {@code triggerOf} holds each speaker's first triggering line and the pending player's own trigger.
     */
    private record Transcript(List<Evidence.Line> lines, Map<String, UUID> uuids, Set<String> bypass,
                              Map<String, Integer> triggerOf, int trigger, String channel)
    {
        static Transcript of(JsonObject state)
        {
            JsonObject trigger = state.getAsJsonObject("trigger");
            String triggerId = trigger.get("id").getAsString();
            List<JsonObject> messages = new ArrayList<>();
            for (JsonElement line : state.getAsJsonArray("conversation"))
            {
                messages.add(line.getAsJsonObject());
                if (triggerId.equals(line.getAsJsonObject().get("id").getAsString()))
                {
                    break;
                }
            }
            if (messages.isEmpty() || !triggerId.equals(messages.getLast().get("id").getAsString()))
            {
                messages.add(trigger);
            }
            int triggerIndex = messages.size() - 1;
            state.getAsJsonArray("later").forEach(line -> messages.add(line.getAsJsonObject()));
            List<Evidence.Line> lines = new ArrayList<>();
            Map<String, UUID> uuids = new HashMap<>();
            Set<String> bypass = new HashSet<>();
            Map<String, Integer> triggerOf = new HashMap<>();
            for (int i = 0; i < messages.size(); i++)
            {
                JsonObject message = messages.get(i);
                String speaker = message.get("speaker").getAsString();
                lines.add(new Evidence.Line(speaker, message.get("text").getAsString(),
                        message.has("p_ban") ? message.get("p_ban").getAsDouble() : null));
                uuids.putIfAbsent(speaker, UUID.fromString(message.get("speaker_uuid").getAsString()));
                String decision = message.has("decision") ? message.get("decision").getAsString() : "";
                if (Verdict.BYPASS.equals(decision))
                {
                    bypass.add(speaker);
                }
                if (Verdict.TRIGGER.equals(decision))
                {
                    triggerOf.putIfAbsent(speaker, i);
                }
            }
            triggerOf.put(trigger.get("speaker").getAsString(), triggerIndex);
            return new Transcript(List.copyOf(lines), uuids, bypass, triggerOf, triggerIndex,
                    channel(trigger.get("channel").getAsString()));
        }

        // The channel as the user message names it; a module chat channel such as guilds:chat is "guilds chat".
        private static String channel(String channel)
        {
            String known = CHANNELS.get(channel);
            if (known != null)
            {
                return known;
            }
            int separator = channel.indexOf(':');
            return (separator < 0 ? channel : channel.substring(0, separator)) + " chat";
        }
    }
}
