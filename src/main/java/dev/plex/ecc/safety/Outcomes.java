package dev.plex.ecc.safety;

import com.google.gson.JsonObject;
import dev.plex.ECCModule;
import dev.plex.api.player.PlexPlayerView;
import dev.plex.api.punishment.IndefiniteBanRequest;
import dev.plex.api.punishment.PunishmentsApi;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The outcomes of verdicts that ban: the indefinite-ban entry written through Plex, then the kick after a random delay.
 * A player stays shadow-muted from the verdict until the kick. The screens and reasons are fixed templates from
 * messages.yml; the offender is kicked with Plex's own indefinite-ban screen, which their next login shows too.
 */
final class Outcomes
{
    private static final long WRITE_SECONDS = 30;
    private static final long TICK_MILLIS = 50;

    private final ECCModule module;
    private final Logger logger;
    private final RecordLog record;
    private final Duration kickMin;
    private final Duration kickMax;
    private final String offenderReason;
    private final String atRiskReason;
    private final String welfareScreen;
    private final Component offenderKick;
    private final Component welfareKick;
    private final Object lock = new Object();
    // Players banned this run who are still online: the kick screen each is waiting for.
    private final Map<UUID, Component> awaitingKick = new ConcurrentHashMap<>();

    Outcomes(ECCModule module, RecordLog record, Duration kickMin, Duration kickMax)
    {
        this.module = module;
        this.logger = module.getLogger();
        this.record = record;
        this.kickMin = kickMin;
        this.kickMax = kickMax;
        this.offenderReason = module.messageString("chatSafetyOffenderReason");
        this.atRiskReason = module.messageString("chatSafetyAtRiskReason");
        this.welfareScreen = module.messageString("chatSafetyWelfareScreen");
        this.welfareKick = module.messageComponent("chatSafetyWelfareScreen");
        this.offenderKick = module.api().messages().messageComponent("indefBanMessageReason",
                Placeholder.unparsed("type", "UUID"),
                Placeholder.parsed("appeal_url", module.api().configuration().mainConfig().getString("banning.ban_url", "")),
                Placeholder.unparsed("reason", offenderReason));
    }

    boolean awaitingKick(UUID uuid)
    {
        return awaitingKick.containsKey(uuid);
    }

    /**
     * Writes the player's indefinite-ban entry (UUID, name and IP) for an offender or at-risk verdict, then schedules
     * their kick. A player whose UUID is already indefinitely banned gets no second entry. Returns false when the entry
     * could not be written, so the verdict must be applied again.
     */
    boolean ban(UUID uuid, String name, String role, String cause) throws InterruptedException
    {
        boolean atRisk = Evidence.AT_RISK.equals(role);
        Component screen = atRisk ? welfareKick : offenderKick;
        synchronized (lock)
        {
            PunishmentsApi bans = module.api().punishments();
            // Bukkit's player lookup by UUID reads a concurrent map on Folia, so it is safe off a region thread.
            Player player = Bukkit.getPlayer(uuid);
            if (bans.indefiniteBanByUuid(uuid).isPresent())
            {
                action(uuid, name, "already_banned", role, cause, null);
                kick(uuid, name, player, screen);
                return true;
            }
            String label = "ecc-" + uuid + "-" + Instant.now().toEpochMilli();
            IndefiniteBanRequest request = new IndefiniteBanRequest(label, atRisk ? atRiskReason : offenderReason,
                    atRisk ? welfareScreen : "", List.of(name), List.of(uuid), ip(uuid, player));
            boolean created;
            try
            {
                created = bans.createIndefiniteBan(request).get(WRITE_SECONDS, TimeUnit.SECONDS);
            }
            catch (ExecutionException | TimeoutException | IllegalArgumentException ex)
            {
                logger.error("Unable to write the chat safety ban of {} ({}); the verdict is applied again", name, uuid,
                        ex);
                action(uuid, name, "ban_failed", role, cause, String.valueOf(ex));
                return false;
            }
            if (!created)
            {
                // The label holds the time in milliseconds, so the next attempt uses a new one.
                logger.error("An indefinite ban labeled {} already exists; the chat safety ban of {} is applied again",
                        label, name);
                action(uuid, name, "ban_failed", role, cause, "label exists: " + label);
                return false;
            }
            action(uuid, name, "banned", role, cause, label);
            kick(uuid, name, player, screen);
            return true;
        }
    }

    // The online player's address, else the last IP Plex recorded for them, else none.
    private List<String> ip(UUID uuid, @Nullable Player player) throws InterruptedException
    {
        InetSocketAddress address = player == null ? null : player.getAddress();
        if (address != null && address.getAddress() != null)
        {
            String host = address.getAddress().getHostAddress();
            int scope = host.indexOf('%');
            return List.of(scope < 0 ? host : host.substring(0, scope));
        }
        try
        {
            Optional<PlexPlayerView> view = module.api().players().player(uuid).get(WRITE_SECONDS, TimeUnit.SECONDS);
            List<String> ips = view.map(PlexPlayerView::ips).orElse(List.of());
            return ips.isEmpty() ? List.of() : List.of(ips.getLast());
        }
        catch (ExecutionException | TimeoutException ex)
        {
            logger.warn("Unable to read the last IP of {}; the chat safety ban covers their UUID and name only", uuid,
                    ex);
            return List.of();
        }
    }

    // Kicks an online player after a random delay on their own scheduler; they stay shadow-muted until then.
    private void kick(UUID uuid, String name, @Nullable Player player, Component screen)
    {
        if (player == null || awaitingKick.putIfAbsent(uuid, screen) != null)
        {
            return;
        }
        long delay = ThreadLocalRandom.current().nextLong(kickMin.toMillis(), kickMax.toMillis() + 1);
        ScheduledTask task = player.getScheduler().runDelayed(module.plugin(), scheduled ->
        {
            awaitingKick.remove(uuid);
            player.kick(screen, PlayerKickEvent.Cause.BANNED);
        }, () -> awaitingKick.remove(uuid), Math.max(1, delay / TICK_MILLIS));
        if (task == null)
        {
            awaitingKick.remove(uuid);
            return;
        }
        module.ownTask(task);
        action(uuid, name, "kick_scheduled", null, null, delay / 1000 + " seconds");
    }

    void action(UUID uuid, String name, String action, @Nullable String role, @Nullable String cause,
                @Nullable String detail)
    {
        JsonObject line = new JsonObject();
        line.addProperty("type", "slow_action");
        line.addProperty("ts", Instant.now().toString());
        line.addProperty("player", name);
        line.addProperty("player_uuid", uuid.toString());
        line.addProperty("action", action);
        if (role != null)
        {
            line.addProperty("role", role);
            line.addProperty("cause", cause);
        }
        if (detail != null)
        {
            line.addProperty("detail", detail);
        }
        record.append(line);
    }

    // Kicks every banned player still waiting for their kick, since the delayed kicks end with the module.
    void close()
    {
        awaitingKick.forEach((uuid, screen) ->
        {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null)
            {
                module.kickPlayerOnShutdown(player, screen);
            }
        });
        awaitingKick.clear();
    }
}
