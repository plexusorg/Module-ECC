package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One captured message. Its verdict completes once: when it is scored, reuses another message's verdict, or is
 * recorded without a check.
 */
final class Message
{
    private final String id = UUID.randomUUID().toString();
    private final Instant time;
    private final UUID speakerUuid;
    private final String speaker;
    private final String channel;
    private final String conversation;
    private final @Nullable UUID targetUuid;
    private final @Nullable String target;
    private final String text;
    private final boolean shadowed;
    private final CompletableFuture<Verdict> verdict = new CompletableFuture<>();
    // Guarded by this message's monitor. Its conversation up to and including it, as it stood when it was said. It is
    // dropped once the message is finished, so finished messages never hold one another.
    private @Nullable List<Message> context;

    Message(Instant time, UUID speakerUuid, String speaker, String channel, String conversation,
            @Nullable UUID targetUuid, @Nullable String target, String text, boolean shadowed)
    {
        this.time = time;
        this.speakerUuid = speakerUuid;
        this.speaker = speaker;
        this.channel = channel;
        this.conversation = conversation;
        this.targetUuid = targetUuid;
        this.target = target;
        this.text = text;
        this.shadowed = shadowed;
    }

    String id()
    {
        return id;
    }

    Instant time()
    {
        return time;
    }

    UUID speakerUuid()
    {
        return speakerUuid;
    }

    String speaker()
    {
        return speaker;
    }

    String channel()
    {
        return channel;
    }

    String conversation()
    {
        return conversation;
    }

    synchronized void context(List<Message> lines)
    {
        context = lines;
    }

    // The message's context, or null when it has none or it was already taken.
    @Nullable
    synchronized List<Message> takeContext()
    {
        List<Message> lines = context;
        context = null;
        return lines;
    }

    String text()
    {
        return text;
    }

    CompletableFuture<Verdict> verdict()
    {
        return verdict;
    }

    // The message and, once known, its verdict: the shape of a record line and of a pending state transcript entry.
    JsonObject toJson()
    {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("ts", time.toString());
        json.addProperty("channel", channel);
        json.addProperty("conversation", conversation);
        json.addProperty("speaker", speaker);
        json.addProperty("speaker_uuid", speakerUuid.toString());
        if (target != null)
        {
            json.addProperty("target", target);
        }
        if (targetUuid != null)
        {
            json.addProperty("target_uuid", targetUuid.toString());
        }
        json.addProperty("text", text);
        json.addProperty("shadowed", shadowed);
        Verdict done = verdict.getNow(null);
        if (done != null)
        {
            done.addTo(json);
        }
        return json;
    }
}
