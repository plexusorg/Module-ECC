package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One line, as ECC-Server's POST /line takes it; ECC-Server times it by its own clock on arrival and prepares its text
 * as the training pipeline did, so {@code text} is raw: the plain text of what was written, or a command's arguments
 * as typed. {@code speaker} is the account name, or for a Discord relay the Discord name as the game shows it, and
 * then {@code speakerUuid} is null. {@code shown}, on public chat and guild chat only, is the name as the console line
 * shows it, with rank tags and nickname. {@code target} is the recipient of a private message or mail, or the player
 * whose nickname is set when that is not the speaker. {@code written} orders the lines as they were written. A
 * {@code trusted} line was delivered at once, and ECC-Server keeps it as context only.
 */
public record Line(String id, long written, String channel, @Nullable UUID speakerUuid, String speaker,
                   @Nullable String shown, @Nullable UUID targetUuid, @Nullable String target, String text,
                   boolean trusted)
{
    private static final AtomicLong WRITTEN = new AtomicLong();

    /**
     * A new line with its id. Call it on the thread of the event that carries the line.
     */
    public static Line of(Player speaker, String channel, String text)
    {
        return create(channel, speaker.getUniqueId(), speaker.getName(), null, null, null, text, false);
    }

    public static Line shown(Player speaker, String channel, String shown, String text)
    {
        return create(channel, speaker.getUniqueId(), speaker.getName(), shown, null, null, text, false);
    }

    public static Line to(Player speaker, String channel, @Nullable UUID targetUuid, String target, String text)
    {
        return create(channel, speaker.getUniqueId(), speaker.getName(), null, targetUuid, target, text, false);
    }

    // A Discord message relayed into the game, which every player already saw.
    public static Line discord(String speaker, String text)
    {
        return create(ChatSafety.DISCORD, null, speaker, null, null, null, text, true);
    }

    private static Line create(String channel, @Nullable UUID speakerUuid, String speaker, @Nullable String shown,
                               @Nullable UUID targetUuid, @Nullable String target, String text, boolean trusted)
    {
        return new Line(UUID.randomUUID().toString(), WRITTEN.incrementAndGet(), channel, speakerUuid, speaker, shown,
                targetUuid, target, text, trusted);
    }

    Line asTrusted()
    {
        return new Line(id, written, channel, speakerUuid, speaker, shown, targetUuid, target, text, true);
    }

    JsonObject json()
    {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("channel", channel);
        // Gson writes a null value as JSON null.
        json.addProperty("speaker_uuid", speakerUuid == null ? null : speakerUuid.toString());
        json.addProperty("speaker", speaker);
        if (shown != null)
        {
            json.addProperty("shown", shown);
        }
        json.addProperty("target_uuid", targetUuid == null ? null : targetUuid.toString());
        json.addProperty("target", target);
        json.addProperty("text", text);
        json.addProperty("trusted", trusted);
        return json;
    }
}
