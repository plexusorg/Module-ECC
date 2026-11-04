package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plex.ECCModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chat safety: capture, the flood and duplicate rule, the fast check, and the trigger. It exists only while its
 * switch is on and its settings are valid.
 */
public final class ChatSafety
{
    public static final String BYPASS_PERMISSION = "plex.ecc.chat.bypass";
    public static final String CHAT = "chat";
    public static final String ME = "me";
    public static final String NICK = "nick";
    public static final String MSG = "msg";
    public static final String MAIL = "mail";
    public static final String SIGN = "sign";
    public static final String BOOK = "book";
    public static final String ANVIL = "anvil";
    // Public chat, /me and /nick are one conversation.
    public static final String PUBLIC_CONVERSATION = CHAT;

    private static final long REPEAT_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final int FLOOD_PLAYERS = 3;
    // Transcript kept for a pending state: more than the classifier's largest window (29 earlier lines).
    private static final int HISTORY_LINES = 40;
    private static final int HISTORY_KEYS = 1024;

    private final ECCModule module;
    private final RecordLog record;
    private final Pending pending;
    private final Scorer scorer;
    private final Object lock = new Object();
    // Guarded by lock. Both maps are in time order of their last update, oldest first.
    private final Map<String, Repeat> repeats = new LinkedHashMap<>();
    private final Map<String, Flood> floods = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Message>> conversations = bounded();
    private final Map<UUID, ArrayDeque<Message>> players = bounded();
    private volatile boolean closed;

    private ChatSafety(ECCModule module, SafetySettings settings)
    {
        this.module = module;
        this.record = new RecordLog(module.getDataFolder().toPath().resolve("chat-safety"), settings.server(),
                settings.recordRetentionDays(), module.getLogger());
        this.pending = new Pending(module.api().storage().forModule(module), module.getLogger());
        this.scorer = new Scorer(new ModelHost(settings.modelHost()), module.getLogger(), settings.queueSize(),
                this::finish);
    }

    /**
     * Starts chat safety when its settings are valid. Otherwise logs each invalid setting and returns null.
     *
     * @throws IllegalStateException when its record folder or pending state cannot be opened
     */
    @Nullable
    public static ChatSafety start(ECCModule module, ConfigurationSection section)
    {
        List<String> errors = new ArrayList<>();
        SafetySettings settings = SafetySettings.read(section, errors);
        if (settings == null)
        {
            errors.forEach(error -> module.getLogger().error("Invalid chat safety setting: {}", error));
            module.getLogger().error("Chat safety stays off until its settings are fixed");
            return null;
        }
        ChatSafety safety = new ChatSafety(module, settings);
        try
        {
            // Lifecycle work before capture starts: the schema must exist and every pending player must be known,
            // so a pending player is shadow-muted from the first message.
            safety.pending.load();
            safety.record.start();
        }
        catch (SQLException | IOException | RuntimeException ex)
        {
            safety.close();
            throw new IllegalStateException("Chat safety could not open its pending state or record", ex);
        }
        safety.scorer.start();
        module.getLogger().info("Chat safety is on; {} players are pending", safety.pending.entries().size());
        return safety;
    }

    public boolean isShadowMuted(UUID uuid)
    {
        return pending.contains(uuid);
    }

    /**
     * Captures a message a player sent. A shadowed message reached only its sender; it is recorded, kept for a
     * pending player's slow check, and not scored.
     */
    public void capture(Player player, String channel, String conversation, @Nullable UUID targetUuid,
                        @Nullable String target, String text, boolean shadowed)
    {
        if (closed || text.isBlank())
        {
            return;
        }
        UUID uuid = player.getUniqueId();
        Message message = new Message(Instant.now(), uuid, player.getName(), channel, conversation, targetUuid, target,
                text, shadowed);
        if (shadowed)
        {
            finish(message, Verdict.unchecked(Verdict.SHADOWED));
            pending.addLater(uuid, message.toJson());
            return;
        }
        if (player.hasPermission(BYPASS_PERMISSION))
        {
            remember(message);
            finish(message, Verdict.unchecked(Verdict.BYPASS));
            return;
        }
        if (module.raidDetector().raidActive() && !module.quarantine().isExempt(uuid))
        {
            remember(message);
            finish(message, Verdict.unchecked(Verdict.RAID));
            return;
        }
        admit(message);
    }

    // The flood and duplicate rule, then the player's queue.
    private void admit(Message message)
    {
        long now = message.time().toEpochMilli();
        Message original;
        String rule;
        synchronized (lock)
        {
            remember(message);
            expire(now);
            String repeatKey = message.speakerUuid() + "\u001f" + message.conversation();
            Repeat repeat = repeats.remove(repeatKey);
            if (repeat != null && repeat.message().text().equals(message.text()))
            {
                // A player's exact repeat within 60 seconds of their previous copy reuses that copy's verdict.
                repeats.put(repeatKey, new Repeat(repeat.message(), now));
                original = repeat.message();
                rule = "repeat";
            }
            else
            {
                repeats.put(repeatKey, new Repeat(message, now));
                original = flood(message, now);
                rule = "flood";
            }
        }
        if (original == null)
        {
            scorer.submit(message);
            return;
        }
        original.verdict().thenAccept(verdict -> finish(message, verdict.reusedBy(rule, original.id())));
    }

    // When three or more players send the same text within 60 seconds of its first copy, later copies reuse the first
    // copy's verdict. Returns that first copy, or null when this message is checked itself.
    @Nullable
    private Message flood(Message message, long now)
    {
        String key = DuplicateKey.of(message.text());
        if (key.isEmpty())
        {
            return null;
        }
        Flood flood = floods.get(key);
        if (flood == null)
        {
            floods.put(key, new Flood(message, now, new HashSet<>(Set.of(message.speakerUuid()))));
            return null;
        }
        flood.players().add(message.speakerUuid());
        boolean reuse = flood.players().size() >= FLOOD_PLAYERS
                && !flood.first().speakerUuid().equals(message.speakerUuid());
        return reuse ? flood.first() : null;
    }

    private void expire(long now)
    {
        Iterator<Repeat> oldRepeats = repeats.values().iterator();
        while (oldRepeats.hasNext() && now - oldRepeats.next().time() > REPEAT_MILLIS)
        {
            oldRepeats.remove();
        }
        Iterator<Flood> oldFloods = floods.values().iterator();
        while (oldFloods.hasNext() && now - oldFloods.next().time() > REPEAT_MILLIS)
        {
            oldFloods.remove();
        }
    }

    // Keeps the message in its conversation's and its author's transcript for a later pending state.
    private void remember(Message message)
    {
        synchronized (lock)
        {
            append(conversations.computeIfAbsent(message.conversation(), key -> new ArrayDeque<>()), message);
            append(players.computeIfAbsent(message.speakerUuid(), key -> new ArrayDeque<>()), message);
        }
    }

    private static void append(ArrayDeque<Message> lines, Message message)
    {
        lines.addLast(message);
        if (lines.size() > HISTORY_LINES)
        {
            lines.pollFirst();
        }
    }

    private void finish(Message message, Verdict verdict)
    {
        if (!message.verdict().complete(verdict))
        {
            return;
        }
        JsonObject line = message.toJson();
        line.addProperty("type", "message");
        record.append(line);
        if (verdict.triggered())
        {
            trigger(message, verdict);
        }
    }

    // Shadow-mutes the player on every captured channel and persists what the slow check needs.
    private void trigger(Message message, Verdict verdict)
    {
        Decision parameters = verdict.parameters();
        Verdict.Score score = verdict.score();
        if (closed || parameters == null || score == null || pending.contains(message.speakerUuid()))
        {
            return;
        }
        Instant now = Instant.now();
        JsonObject state = new JsonObject();
        state.addProperty("player", message.speaker());
        state.addProperty("player_uuid", message.speakerUuid().toString());
        state.addProperty("triggered_at", now.toString());
        state.add("trigger", message.toJson());
        state.add("decision", parameters.toJson());
        synchronized (lock)
        {
            state.add("conversation", transcript(conversations.get(message.conversation())));
            state.add("player_messages", transcript(players.get(message.speakerUuid())));
        }
        state.add("later", new JsonArray());
        if (!pending.add(message.speakerUuid(), message.speaker(), now, state))
        {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("type", "decision");
        line.addProperty("ts", now.toString());
        line.addProperty("decision", Verdict.TRIGGER);
        line.addProperty("speaker", message.speaker());
        line.addProperty("speaker_uuid", message.speakerUuid().toString());
        line.addProperty("message_id", message.id());
        line.addProperty("p_ban", score.pBan());
        line.addProperty("version", score.version());
        line.addProperty("trigger_threshold", parameters.trigger());
        record.append(line);
    }

    private static JsonArray transcript(@Nullable ArrayDeque<Message> lines)
    {
        JsonArray transcript = new JsonArray();
        if (lines != null)
        {
            lines.forEach(line -> transcript.add(line.toJson()));
        }
        return transcript;
    }

    public Status status()
    {
        Scorer.HostState host = scorer.state();
        Decision decision = scorer.decision();
        return new Status(host.ready(), host.detail(), host.since(), decision == null ? null : decision.version(),
                scorer.backlog(), pending.entries().stream().map(Pending.Entry::name)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }

    public void close()
    {
        closed = true;
        // The scorer records every message it did not score before the record and pending state close.
        scorer.close();
        record.close();
        pending.close();
    }

    // Private messages and mail between two players are one conversation.
    public static String privateConversation(UUID sender, @Nullable UUID recipient, String recipientName)
    {
        String other = recipient == null ? recipientName.toLowerCase(Locale.ROOT) : recipient.toString();
        String self = sender.toString();
        return MSG + "/" + (self.compareTo(other) < 0 ? self + "|" + other : other + "|" + self);
    }

    // Signs, books and item names have no shared conversation; each player's are their own.
    public static String ownConversation(String channel, UUID player)
    {
        return channel + "/" + player;
    }

    private static <K> Map<K, ArrayDeque<Message>> bounded()
    {
        return new LinkedHashMap<>(16, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, ArrayDeque<Message>> eldest)
            {
                return size() > HISTORY_KEYS;
            }
        };
    }

    /**
     * Chat safety as {@code /ecc status} shows it. {@code hostReady} is null before the first contact with the host.
     */
    public record Status(@Nullable Boolean hostReady, String hostDetail, Instant hostSince, @Nullable String bundle,
                         int backlog, List<String> pending)
    {
    }

    private record Repeat(Message message, long time)
    {
    }

    private record Flood(Message first, long time, Set<UUID> players)
    {
    }
}
