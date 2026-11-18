package dev.plex.ecc.safety;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plex.ECCModule;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat safety's part in the game. Every line a player writes is sent to ECC-Server, which answers deliver, hold or reject
 * before anyone else sees the line. Chat safety discards a rejected line and asks its author to shorten it.
 * A line the server holds, or does not answer about in time, is kept with what
 * delivers it until the server releases it or bans its author; the author is told once. A trusted player, one in a
 * group of {@code trusted_groups} or with {@link #BYPASS_PERMISSION}, is delivered at once without waiting for the
 * server, and their line is sent marked trusted, which the server keeps as context only. It exists only while its
 * switch is on and its settings are valid.
 */
public final class ChatSafety implements Listener
{
    public static final String BYPASS_PERMISSION = "plex.ecc.chat.bypass";
    public static final String ALERTS_PERMISSION = "plex.ecc.alerts";
    // The channels the models were trained on, as ECC-Server's bundle names them.
    public static final String CHAT = "chat";
    public static final String GUILD = "guild";
    public static final String MSG = "msg";
    public static final String MAIL = "mail";
    public static final String ME = "me";
    public static final String NICK = "nick";
    public static final String SIGN = "sign";
    public static final String BOOK = "book";
    public static final String ITEM = "item";
    public static final String TAG = "tag";
    // A quarantined player's public chat, which only its author sees.
    public static final String MUTED = "muted";
    public static final String DISCORD = "discord";
    private static final Key GUILD_CHAT = Key.key("guilds", "chat");
    private static final String LINE = "/line";
    private static final String PRESENCE = "/presence";
    private static final Component SHORTEN_NOTICE = Component.text("Shorten this text, then try again.");
    // How much longer than the verdict timeout a waiting thread allows the HTTP client to report it.
    private static final long GRACE_MILLIS = 250;
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(10);

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ECCModule module;
    private final Logger logger;
    private final SafetySettings settings;
    private final EccServer server;
    private final Held held = new Held();
    private final Outbox outbox;
    private final Alerts alerts;
    private final Actions actions;
    private final Thread asker;
    private final Backoff askBackoff;
    // Set when ECC-Server answers with a new boot id, until the asker has told it again what it needs.
    private final AtomicBoolean restarted = new AtomicBoolean();
    private final Sudo sudo = new Sudo();
    private final Component notice;
    // Each player's last line from a main-thread channel, so their lines are delivered in the order written.
    private final Map<UUID, CompletableFuture<Void>> order = new ConcurrentHashMap<>();
    private volatile boolean closing;

    private ChatSafety(ECCModule module, SafetySettings settings)
    {
        this.module = module;
        this.logger = module.getLogger();
        this.settings = settings;
        this.server = new EccServer(settings.eccServer(), settings.token(), module.api().logging());
        this.outbox = new Outbox(server, module.api().logging());
        this.alerts = new Alerts(module);
        this.notice = MINI_MESSAGE.deserialize(settings.notice());
        Bans bans = new Bans(module);
        // A quarantined player's chat reaches no one else, which a warning would tell them.
        this.actions = new Actions(server, held, bans, alerts, MINI_MESSAGE.deserialize(settings.warning()),
                uuid -> module.isEnabled() && module.quarantine().isRestricted(uuid), module.api().logging(), logger);
        this.asker = Thread.ofPlatform().daemon().name("Plex-ECC-Asker").unstarted(this::askAgain);
        this.askBackoff = server.backoff();
        server.onRestart(() ->
        {
            restarted.set(true);
            askBackoff.wake();
        });
    }

    /**
     * Starts chat safety when its settings are valid. Otherwise logs each invalid setting and returns null.
     */
    @Nullable
    public static ChatSafety start(ECCModule module, ConfigurationSection section)
    {
        List<String> errors = new ArrayList<>();
        SafetySettings settings = SafetySettings.read(section, errors);
        if (settings == null)
        {
            errors.forEach(error -> module.api().logging().error("Invalid chat safety setting: {0}",
                    MINI_MESSAGE.escapeTags(error)));
            module.api().logging().error("Chat safety stays off until its settings are fixed");
            return null;
        }
        ChatSafety safety = new ChatSafety(module, settings);
        // ECC-Server is told a new stream, the allowlist of links and who is online by announce(), when it first answers
        // with a boot id it has not acknowledged a start for.
        safety.outbox.start();
        safety.alerts.start();
        safety.actions.start();
        safety.asker.start();
        module.registerListener(safety);
        module.registerListener(safety.sudo);
        module.api().logging().info("Chat safety is on with ECC-Server at {0}; a line waits up to {1} ms for its"
                        + " verdict", MINI_MESSAGE.escapeTags(String.valueOf(settings.eccServer())),
                settings.verdictTimeout().toMillis());
        return safety;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event)
    {
        presence("join", event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event)
    {
        presence("leave", event.getPlayer());
    }

    /**
     * For a line on a thread that may wait, such as the async chat thread: true when it may be delivered now. False
     * means the line is held: the caller cancels its delivery, and {@code late} delivers it if it is released.
     */
    public boolean await(Player player, Line line, Delivery late)
    {
        if (trusted(player))
        {
            send(line.asTrusted());
            return true;
        }
        if (!held.add(line, late))
        {
            return false;
        }
        long timeout = settings.verdictTimeout().toMillis();
        EccServer.Verdict verdict;
        try
        {
            verdict = request(line).get(timeout + GRACE_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            verdict = EccServer.Verdict.NONE;
        }
        catch (ExecutionException | TimeoutException ex)
        {
            verdict = EccServer.Verdict.NONE;
        }
        if (verdict == EccServer.Verdict.DELIVER)
        {
            return held.deliver(line.id()) != null && !closing;
        }
        if (verdict == EccServer.Verdict.REJECT)
        {
            reject(player, line);
        }
        else
        {
            hold(player, line, verdict == EccServer.Verdict.HOLD);
        }
        return false;
    }

    /**
     * For a line on a thread that must not wait, such as the server thread: true when the event may go on, which is
     * only for a trusted player. Otherwise the caller cancels the event, and {@code delivery}
     * does what the event would have done once the line may be delivered, now or after a release. A player's lines
     * are delivered in the order they were written.
     */
    public boolean check(Player player, Line line, Delivery delivery)
    {
        if (trusted(player))
        {
            send(line.asTrusted());
            return true;
        }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Delivery orderedDelivery = () -> ready.thenRun(() ->
        {
            if (!closing)
            {
                delivery.deliver();
            }
        }).exceptionally(ex ->
        {
            logger.error("Unable to deliver released chat line {}", line.id(), ex);
            return null;
        });
        if (!held.add(line, orderedDelivery))
        {
            return false;
        }
        CompletableFuture<EccServer.Verdict> verdict = request(line);
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> step = order.compute(uuid, (key, previous) ->
                (previous == null ? verdict : previous.thenCombine(verdict, (done, answer) -> answer))
                        .thenAccept(answer ->
                        {
                            if (answer == EccServer.Verdict.DELIVER)
                            {
                                Held.Entry entry = held.deliver(line.id());
                                if (entry != null)
                                {
                                    entry.delivery().deliver();
                                }
                            }
                            else if (answer == EccServer.Verdict.REJECT)
                            {
                                reject(player, line);
                            }
                            else
                            {
                                hold(player, line, answer == EccServer.Verdict.HOLD);
                            }
                        })
                        .whenComplete((done, failure) -> ready.complete(null))
                        .exceptionally(ex ->
                        {
                            logger.error("Unable to deliver or hold chat line {}", line.id(), ex);
                            return null;
                        }));
        step.whenComplete((done, failure) -> order.remove(uuid, step));
        return false;
    }

    /**
     * Sends a line the module delivers or drops by itself, whose verdict it does not use: a trusted player's and a
     * Discord relay's, which the server keeps as context, and a quarantined player's, which only its author sees. It
     * goes out at once, as a checked line does, so the server receives lines in the order they were written; a line
     * that does not reach the server, and every line after it until the outbox has sent it, is sent again in order.
     */
    public void send(Line line)
    {
        JsonObject json = line.json();
        if (outbox.resending())
        {
            outbox.add(LINE, json);
            return;
        }
        server.send(json).thenAccept(sent ->
        {
            if (!sent)
            {
                outbox.add(LINE, json);
            }
        });
    }

    // A module chat channel's line goes to ECC-Server under the vocabulary's name for it; a channel the vocabulary
    // does not have keeps its key, which ECC-Server refuses.
    public static String moduleChannel(Key channel)
    {
        return GUILD_CHAT.equals(channel) ? GUILD : channel.asString();
    }

    /**
     * Who a chat line fired on the server thread belongs to: the player whose /sudo forced it, else the speaker.
     */
    public Player chatAuthor(Player speaker)
    {
        return sudo.chatAuthor(speaker);
    }

    /**
     * Who a command belongs to: the player whose /sudo forced it, else the player who ran it.
     */
    public Player commandAuthor(PlayerCommandPreprocessEvent event)
    {
        return sudo.commandAuthor(event);
    }

    // Turns a staff member's alerts off or back on until they leave; true when they are now off. Call it on the
    // player's own thread once they hold ALERTS_PERMISSION.
    public boolean toggleAlerts(Player player)
    {
        return alerts.toggle(player);
    }

    public Status status()
    {
        EccServer.State state = server.state();
        return new Status(!Boolean.FALSE.equals(state.reachable()), state.since(), held.size(),
                held.unansweredCount(), actions.unacknowledged());
    }

    // Stops the action loop first, so no release runs while the rest stops. Held lines are not kept.
    public void close()
    {
        closing = true;
        actions.close();
        asker.interrupt();
        outbox.close();
        int left = held.size();
        if (left > 0)
        {
            module.api().logging().warn("Chat safety stopped with {0} held lines, which are not delivered", left);
        }
        held.close();
        server.close();
    }

    private CompletableFuture<EccServer.Verdict> request(Line line)
    {
        try
        {
            return server.line(line.json(), settings.verdictTimeout());
        }
        catch (RuntimeException ex)
        {
            logger.error("Unable to submit chat line {}; it stays held for another attempt", line.id(), ex);
            return CompletableFuture.completedFuture(EccServer.Verdict.NONE);
        }
    }

    private void reject(@Nullable Player player, Line line)
    {
        if (held.deliver(line.id()) != null && player != null && !closing)
        {
            player.sendMessage(SHORTEN_NOTICE);
        }
    }

    private void hold(Player player, Line line, boolean answered)
    {
        if (closing || !held.hold(line.id(), answered))
        {
            return;
        }
        player.sendMessage(notice);
        alerts.held(line, answered);
    }

    // In a trusted group or holding the bypass permission. LuckPerms sets the permission group.<name> for each group a
    // player is in, directly or by inheritance. Both are read as set, since an unset permission is true for operators.
    // LuckPerms' permission map is safe to read from any thread.
    private boolean trusted(Player player)
    {
        if (granted(player, BYPASS_PERMISSION))
        {
            return true;
        }
        for (String group : settings.trustedGroups())
        {
            if (granted(player, "group." + group))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean granted(Player player, String permission)
    {
        return player.isPermissionSet(permission) && player.hasPermission(permission);
    }

    private void presence(String kind, @Nullable Player player)
    {
        outbox.add(PRESENCE, presenceBody(kind, player));
    }

    private JsonObject presenceBody(String kind, @Nullable Player player)
    {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        if ("start".equals(kind))
        {
            JsonArray links = new JsonArray();
            settings.allowedLinks().forEach(links::add);
            body.add("allowed_links", links);
        }
        if (player != null)
        {
            body.addProperty("uuid", player.getUniqueId().toString());
            body.addProperty("name", player.getName());
        }
        return body;
    }

    // Tells ECC-Server, on a boot id it has not acknowledged a start for, what it needs: a new stream with the allowlist of
    // links, then a join for every player online. False when it did not answer; the held lines then wait, as they must follow.
    private boolean announce() throws InterruptedException
    {
        List<JsonObject> posts = new ArrayList<>();
        JsonObject start = presenceBody("start", null);
        posts.add(start);
        for (String name : module.api().players().onlineNames())
        {
            Player player = Bukkit.getPlayerExact(name);
            if (player != null)
            {
                posts.add(presenceBody("join", player));
            }
        }
        for (JsonObject post : posts)
        {
            try
            {
                if (post == start)
                {
                    server.start(post);
                }
                else
                {
                    server.post(PRESENCE, post);
                }
            }
            catch (EccServer.Unavailable ex)
            {
                return false;
            }
            catch (EccServer.Rejected ex)
            {
                module.api().logging().error("ECC-Server refused {0} after it started again: it {1}",
                        MINI_MESSAGE.escapeTags(post.toString()), MINI_MESSAGE.escapeTags(String.valueOf(ex.getMessage())));
            }
        }
        return true;
    }

    // Asks ECC-Server again about each line held because it did not answer, oldest first, until it answers: a
    // deliver delivers the line late, a hold keeps it held. The same id is sent, so the server answers with the
    // verdict it gave if it saw the line before. While the server does not answer, the wait between tries grows, and
    // ends as soon as any request finds the server answering again. When ECC-Server has started again, it is first
    // told the stream and who is online, and then asked about every held line, since it knows none of them.
    private void askAgain()
    {
        Backoff backoff = askBackoff;
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                backoff.pause();
                if (restarted.getAndSet(false))
                {
                    if (!announce())
                    {
                        restarted.set(true);
                        continue;
                    }
                    held.unanswerAll();
                }
                boolean failed = false;
                for (Line line : held.unanswered())
                {
                    EccServer.Verdict verdict = server.line(line.json(), RETRY_TIMEOUT).get();
                    if (verdict == EccServer.Verdict.NONE)
                    {
                        failed = true;
                        break;
                    }
                    if (verdict == EccServer.Verdict.HOLD)
                    {
                        held.answeredHold(line.id());
                        continue;
                    }
                    if (verdict == EccServer.Verdict.REJECT)
                    {
                        reject(Bukkit.getPlayer(line.speakerUuid()), line);
                        continue;
                    }
                    Held.Entry entry = held.answeredDeliver(line.id());
                    if (entry != null)
                    {
                        entry.delivery().deliver();
                    }
                }
                if (!failed)
                {
                    backoff.reset();
                }
            }
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | RuntimeException ex)
        {
            logger.error("Chat safety stopped asking ECC-Server again about unanswered lines after a failure", ex);
        }
    }

    public Duration verdictTimeout()
    {
        return settings.verdictTimeout();
    }

    public Component itemNameChecked()
    {
        return MINI_MESSAGE.deserialize(settings.itemNameChecked());
    }

    /**
     * The module's side of {@code /ecc status}: whether ECC-Server answered last and since when, lines held, held
     * lines still waiting for any answer, and actions received and not yet acknowledged as done.
     */
    public record Status(boolean answering, Instant since, int held, int unanswered, int unacknowledged)
    {
    }
}
