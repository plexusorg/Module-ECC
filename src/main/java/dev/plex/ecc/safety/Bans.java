package dev.plex.ecc.safety;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Carries out ECC-Server's bans: the indefinite-ban entry written through Plex, then the kick at the time the server
 * chose. The player is muted from the ban until the kick. The screens and reasons are fixed templates from
 * messages.yml; the offender is kicked with Plex's own indefinite-ban screen, which their next login shows too.
 */
final class Bans
{
    static final String OFFENDER = "offender";
    static final String AT_RISK = "at_risk";
    private static final long WRITE_SECONDS = 30;
    private static final long TICK_MILLIS = 50;

    private final ECCModule module;
    private final Logger logger;
    private final MuteSet muted;
    private final String offenderReason;
    private final String atRiskReason;
    private final String welfareScreen;
    private final Component offenderKick;
    private final Component welfareKick;
    // Banned players who are online: the kick screen each is waiting for.
    private final Map<UUID, Component> awaitingKick = new ConcurrentHashMap<>();

    Bans(ECCModule module, MuteSet muted)
    {
        this.module = module;
        this.logger = module.getLogger();
        this.muted = muted;
        this.offenderReason = module.messageString("chatSafetyOffenderReason");
        this.atRiskReason = module.messageString("chatSafetyAtRiskReason");
        this.welfareScreen = module.messageString("chatSafetyWelfareScreen");
        this.welfareKick = module.messageComponent("chatSafetyWelfareScreen");
        this.offenderKick = module.api().messages().messageComponent("indefBanMessageReason",
                Placeholder.unparsed("type", "UUID"),
                Placeholder.parsed("appeal_url", module.api().configuration().mainConfig().getString("banning.ban_url", "")),
                Placeholder.unparsed("reason", offenderReason));
    }

    /**
     * Writes the indefinite-ban entry {@code ecc-<action id>} (UUID, name and IP) unless it exists, then schedules the
     * kick. Runs on the action loop's thread.
     *
     * @return what was done, for the acknowledgement
     * @throws Failed when the entry could not be written; the ban is applied again
     */
    String ban(Actions.Action action) throws Failed, InterruptedException
    {
        UUID uuid = action.uuid();
        boolean atRisk = AT_RISK.equals(action.role());
        muted.add(uuid, action.name());
        // Bukkit's player lookup by UUID reads a concurrent map on Folia, so it is safe off a region thread.
        Player player = Bukkit.getPlayer(uuid);
        String label = "ecc-" + action.idText();
        PunishmentsApi bans = module.api().punishments();
        String written = "entry " + label + " exists";
        if (bans.indefiniteBanByLabel(label).isEmpty())
        {
            IndefiniteBanRequest request = new IndefiniteBanRequest(label, atRisk ? atRiskReason : offenderReason,
                    atRisk ? welfareScreen : "", List.of(action.name()), List.of(uuid), ip(uuid, player));
            try
            {
                if (bans.createIndefiniteBan(request).get(WRITE_SECONDS, TimeUnit.SECONDS))
                {
                    written = "wrote entry " + label;
                }
            }
            catch (ExecutionException | TimeoutException | IllegalArgumentException ex)
            {
                logger.error("Unable to write the chat safety ban of {} ({}); it is applied again", action.name(), uuid,
                        ex);
                throw new Failed("unable to write entry " + label + ": " + ex);
            }
        }
        return written + "; " + kick(uuid, player, atRisk ? welfareKick : offenderKick, action.kickAt());
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

    // Kicks an online player at the given time on their own scheduler; they stay muted until then.
    private String kick(UUID uuid, @Nullable Player player, Component screen, Instant at)
    {
        if (player == null)
        {
            muted.remove(uuid);
            return "offline, not kicked";
        }
        if (awaitingKick.putIfAbsent(uuid, screen) != null)
        {
            return "kick already scheduled";
        }
        long delay = Math.max(1, Math.ceilDiv(Duration.between(Instant.now(), at).toMillis(), TICK_MILLIS));
        ScheduledTask task = player.getScheduler().runDelayed(module.plugin(), scheduled ->
        {
            player.kick(screen, PlayerKickEvent.Cause.BANNED);
            kicked(uuid);
        }, () -> kicked(uuid), delay);
        if (task == null)
        {
            kicked(uuid);
            return "offline, not kicked";
        }
        module.ownTask(task);
        return "kick at " + at;
    }

    private void kicked(UUID uuid)
    {
        awaitingKick.remove(uuid);
        muted.remove(uuid);
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

    /**
     * The ban entry could not be written.
     */
    static final class Failed extends Exception
    {
        Failed(String reason)
        {
            super(reason);
        }
    }
}
