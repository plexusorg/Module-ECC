package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import dev.plex.ECCModule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Chat safety's part in the game: it captures what players say and forwards it to ECC-Server, keeps the mute set
 * ECC-Server decides, and carries out ECC-Server's actions. It exists only while its switch is on and its settings
 * are valid.
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

    private final EccServer server;
    private final MuteSet muted;
    private final Outbox outbox;
    private final Bans bans;
    private final Actions actions;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Logger logger;

    private ChatSafety(ECCModule module, SafetySettings settings)
    {
        this.logger = module.getLogger();
        this.server = new EccServer(settings.eccServer(), settings.token(), module.api().logging());
        this.muted = new MuteSet(module.api().storage().forModule(module));
        this.outbox = new Outbox(server, module.api().logging());
        this.bans = new Bans(module, muted);
        this.actions = new Actions(server, muted, bans, module.api().logging(), module.getLogger());
    }

    /**
     * Starts chat safety when its settings are valid. Otherwise logs each invalid setting and returns null.
     *
     * @throws IllegalStateException when its saved mute set cannot be read
     */
    @Nullable
    public static ChatSafety start(ECCModule module, ConfigurationSection section)
    {
        List<String> errors = new ArrayList<>();
        SafetySettings settings = SafetySettings.read(section, errors);
        if (settings == null)
        {
            errors.forEach(error -> module.api().logging().error("Invalid chat safety setting: {0}", MINI_MESSAGE.escapeTags(error)));
            module.api().logging().error("Chat safety stays off until its settings are fixed");
            return null;
        }
        ChatSafety safety = new ChatSafety(module, settings);
        try
        {
            // Lifecycle work before capture starts, so a saved mute applies from the first message.
            safety.muted.load();
        }
        catch (SQLException | RuntimeException ex)
        {
            safety.server.close();
            throw new IllegalStateException("Chat safety could not read its saved mute set", ex);
        }
        safety.outbox.start();
        safety.actions.start();
        module.api().logging().info("Chat safety is on with ECC-Server at {0}; {1} players are muted",
                MINI_MESSAGE.escapeTags(String.valueOf(settings.eccServer())),
                safety.muted.size());
        return safety;
    }

    public boolean isShadowMuted(UUID uuid)
    {
        return muted.contains(uuid);
    }

    /**
     * Forwards a message a player sent. {@code shadowed} means it reached only its sender (muted or quarantined);
     * a player with the staff permission is marked {@code bypass}.
     */
    public void capture(Player player, String channel, String conversation, @Nullable UUID targetUuid,
                        @Nullable String target, String text, boolean shadowed)
    {
        if (text.isBlank())
        {
            return;
        }
        JsonObject message = new JsonObject();
        message.addProperty("id", UUID.randomUUID().toString());
        message.addProperty("channel", channel);
        message.addProperty("conversation", conversation);
        message.addProperty("speaker_uuid", player.getUniqueId().toString());
        message.addProperty("speaker", player.getName());
        // Gson writes a null value as JSON null.
        message.addProperty("target_uuid", targetUuid == null ? null : targetUuid.toString());
        message.addProperty("target", target);
        message.addProperty("text", text);
        message.addProperty("shadowed", shadowed);
        message.addProperty("bypass", player.hasPermission(BYPASS_PERMISSION));
        outbox.add(player.getUniqueId(), message);
    }

    public Status status()
    {
        EccServer.State link = server.state();
        return new Status(link.reachable(), link.detail(), link.since(), outbox.size(), actions.unacknowledged(),
                muted.size());
    }

    // ECC-Server's GET /status, completed on the HTTP client's thread.
    public CompletableFuture<JsonObject> serverStatus()
    {
        return server.status();
    }

    // Stops the action loop first, so no ban starts while the waiting kicks are carried out, and kicks after the
    // sender has stopped, so a banned player stays muted while it drains.
    public void close()
    {
        actions.close();
        outbox.close();
        bans.close();
        try
        {
            // The players kicked above leave the saved set; the action loop has stopped, so this thread writes it.
            muted.flush();
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to save chat safety's mute set; ECC-Server's mute set replaces it at the next start",
                    ex);
        }
        server.close();
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

    /**
     * Chat safety as {@code /ecc status} shows it. {@code reachable} is null before the first contact with
     * ECC-Server. {@code unacknowledged} are actions received and not yet acknowledged as done.
     */
    public record Status(@Nullable Boolean reachable, String detail, Instant since, int buffered, int unacknowledged,
                         int muted)
    {
    }
}
