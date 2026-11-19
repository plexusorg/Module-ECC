package dev.plex.listener;

import dev.plex.ecc.safety.ChatSafety;
import dev.plex.ecc.safety.Line;
import dev.plex.ecc.safety.LogReading;
import dev.plex.ecc.safety.SafetySettings;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.ListenerPriority;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePostBroadcastEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.util.LangUtil;
import github.scarsz.discordsrv.util.MessageUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.apache.logging.log4j.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DiscordSRV, which mirrors public chat to Discord and Discord's messages into the game. The Discord messages go to
 * ECC-Server as context. DiscordSRV sends to Discord only a line that public chat delivered when its chat event
 * ended, and a line chat safety delivers later, after a release or a late verdict, is handed to it as a chat line then.
 * By default DiscordSRV reads each line at Bukkit's legacy chat event, which Paper fires before the chat event where
 * chat safety decides; its copy of such a line waits here until that chat event ends.
 */
public final class DiscordSrvHook implements Listener
{
    // How much longer than the verdict timeout DiscordSRV's copy of a line waits for the line's chat event to end.
    private static final long GRACE_MILLIS = 5000;

    private final DiscordSRV discord;
    private final ChatSafety safety;
    private final Logger logger;
    private final long waitMillis;
    private volatile boolean unreadWarned;
    // The outcome of the legacy chat event now on this thread, completed when Paper's chat event after it ends.
    private final ThreadLocal<CompletableFuture<Boolean>> current = new ThreadLocal<>();
    // Each legacy chat event's outcome, until DiscordSRV asks for it. Weak, so an event DiscordSRV skips is dropped.
    private final Map<Event, CompletableFuture<Boolean>> outcomes = Collections.synchronizedMap(new WeakHashMap<>());

    // Call only while DiscordSRV is enabled.
    public DiscordSrvHook(Logger logger, ChatSafety safety)
    {
        this.discord = DiscordSRV.getPlugin();
        this.safety = safety;
        this.logger = logger;
        this.waitMillis = SafetySettings.VERDICT_TIMEOUT.toMillis() + GRACE_MILLIS;
    }

    public void subscribe()
    {
        DiscordSRV.api.subscribe(this);
    }

    public void unsubscribe()
    {
        DiscordSRV.api.unsubscribe(this);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onLegacyChat(AsyncPlayerChatEvent event)
    {
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        outcomes.put(event, outcome);
        current.set(outcome);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event)
    {
        CompletableFuture<Boolean> outcome = current.get();
        if (outcome != null)
        {
            current.remove();
            outcome.complete(!event.isCancelled());
        }
    }

    // Runs on DiscordSRV's task thread, which may wait.
    @Subscribe(priority = ListenerPriority.HIGHEST)
    @SuppressWarnings("deprecation")
    public void onPreProcess(GameChatMessagePreProcessEvent event)
    {
        Event trigger = event.getTriggeringBukkitEvent();
        if (trigger instanceof AsyncChatEvent chat && chat.isCancelled())
        {
            event.setCancelled(true);
        }
        else if (trigger instanceof AsyncPlayerChatEvent legacy && !delivered(outcomes.remove(legacy)))
        {
            event.setCancelled(true);
        }
    }

    /**
     * Hands DiscordSRV a public chat line delivered late, as its chat listener hands it one delivered at once.
     */
    public void forward(Player player, String text)
    {
        if (!discord.isEnabled())
        {
            return;
        }
        try
        {
            discord.processChatMessage(player, text, discord.getOptionalChannel("global"), false, null);
        }
        catch (RuntimeException ex)
        {
            logger.error("DiscordSRV failed to send {}'s released chat line to Discord", player.getName(), ex);
        }
    }

    /**
     * A Discord message DiscordSRV has shown in game goes to ECC-Server as context, with the speaker and text that
     * DiscordSRV's console line for it gives, the line the training pipeline read:
     * {@code "Chat: " + strip(toLegacy(message).replace("»", ">"))}, logged on the JDA thread that fires this event
     * (DiscordSRV#broadcastMessageToMinecraftServer). Training read only lines logged on a JDA thread.
     */
    @Subscribe(priority = ListenerPriority.MONITOR)
    public void onDiscordLine(DiscordGuildMessagePostBroadcastEvent event)
    {
        if (!Thread.currentThread().getName().startsWith("JDA"))
        {
            return;
        }
        String console = "[DiscordSRV] " + LangUtil.InternalMessage.CHAT + ": "
                + MessageUtil.strip(MessageUtil.toLegacy(event.getMessage()).replace("»", ">"));
        LogReading.Discord line = LogReading.discord(console);
        if (line == null)
        {
            if (!unreadWarned)
            {
                unreadWarned = true;
                logger.warn("DiscordSRV's chat line has a format the training pipeline did not read, so Discord lines"
                        + " are not sent to ECC-Server: {}", console);
            }
            return;
        }
        safety.send(Line.discord(line.speaker(), line.text()));
    }

    // A line whose chat event did not end in time, or that no chat event followed, is not known to be delivered.
    private boolean delivered(CompletableFuture<Boolean> outcome)
    {
        if (outcome == null)
        {
            return false;
        }
        try
        {
            return outcome.get(waitMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        catch (ExecutionException | TimeoutException ex)
        {
            return false;
        }
    }
}
