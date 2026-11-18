package dev.plex.ecc.safety;

import dev.plex.ECCModule;
import dev.plex.api.player.PlexPlayerView;
import dev.plex.api.punishment.IndefiniteBanRequest;
import dev.plex.api.punishment.IndefiniteBanView;
import dev.plex.api.punishment.PunishmentsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Carries out ECC-Server's bans: the indefinite-ban entry written through Plex, then an immediate kick with Plex's
 * indefinite-ban screen, which the player's next login shows too. The reason is a fixed template from messages.yml.
 */
final class Bans
{
    private static final long WRITE_SECONDS = 30;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ECCModule module;
    private final Logger logger;
    private final String reason;
    private final Component screen;

    Bans(ECCModule module)
    {
        this.module = module;
        this.logger = module.getLogger();
        this.reason = module.messageString("chatSafetyBanReason");
        this.screen = module.api().messages().messageComponent("indefBanMessageReason",
                Placeholder.unparsed("type", "UUID"),
                Placeholder.parsed("appeal_url", module.api().configuration().mainConfig().getString("banning.ban_url", "")),
                Placeholder.unparsed("reason", reason));
    }

    /**
     * Writes the indefinite-ban entry {@code ecc-<action id>} unless it exists, then kicks the player if they are
     * online. The entry holds the UUID, the name Bukkit or Plex holds for that UUID, and the IP: Plex refuses every
     * login under a listed name, so a name another account owns is never written. Runs on the action loop's thread.
     *
     * @return what was done, for the acknowledgement
     * @throws Failed when the entry could not be written; the ban is applied again
     */
    String ban(Actions.Action action) throws Failed, InterruptedException
    {
        UUID uuid = action.uuid();
        // Bukkit's player lookup by UUID reads a concurrent map on Folia, so it is safe off a region thread.
        Player player = Bukkit.getPlayer(uuid);
        String label = "ecc-" + action.idText();
        PunishmentsApi bans = module.api().punishments();
        String written = "entry " + label + " exists";
        String name = action.account();
        if (bans.indefiniteBanByLabel(label).isEmpty())
        {
            Optional<PlexPlayerView> view = player == null ? view(uuid) : Optional.empty();
            name = name(uuid, player, view, name);
            IndefiniteBanRequest request = new IndefiniteBanRequest(label, reason, "",
                    name == null ? List.of() : List.of(name), List.of(uuid), ip(player, view));
            try
            {
                if (bans.createIndefiniteBan(request).get(WRITE_SECONDS, TimeUnit.SECONDS))
                {
                    written = "wrote entry " + label;
                }
            }
            catch (ExecutionException | TimeoutException | IllegalArgumentException ex)
            {
                logger.error("Unable to write the chat safety ban of {} ({}); it is applied again", name, uuid, ex);
                throw new Failed("unable to write entry " + label + ": " + ex);
            }
        }
        requireCovers(bans, label, uuid, name);
        if (player == null)
        {
            return written + "; offline";
        }
        module.ownTask(player.getScheduler().run(module.plugin(),
                task -> player.kick(screen, PlayerKickEvent.Cause.BANNED), null));
        return written + "; kicked";
    }

    // An existing entry with the label must be this player's, so a reused action id never passes as their ban.
    private void requireCovers(PunishmentsApi bans, String label, UUID uuid, String name) throws Failed
    {
        Optional<IndefiniteBanView> entry = bans.indefiniteBanByLabel(label);
        if (entry.isEmpty() || !entry.get().uuids().contains(uuid))
        {
            module.api().logging().error("The indefinite-ban entry {0} does not cover {1} ({2}), whom ECC-Server banned under that id",
                    MINI_MESSAGE.escapeTags(String.valueOf(label)),
                    MINI_MESSAGE.escapeTags(String.valueOf(name)), uuid);
            throw new Failed("entry " + label + " does not cover " + uuid);
        }
    }

    // Plex's record of an offline player, or empty when it has none or cannot be read.
    private Optional<PlexPlayerView> view(UUID uuid) throws InterruptedException
    {
        try
        {
            return module.api().players().player(uuid).get(WRITE_SECONDS, TimeUnit.SECONDS);
        }
        catch (ExecutionException | TimeoutException ex)
        {
            logger.warn("Unable to read Plex's record of {}; the chat safety ban takes the name and IP it can", uuid,
                    ex);
            return Optional.empty();
        }
    }

    // The name of the account with this UUID: the online player's, else Plex's record of it, else Bukkit's, else the
    // account name the module sent with the player's lines, which the action carries; null when none is known, and
    // then the entry lists no name.
    @Nullable
    private static String name(UUID uuid, @Nullable Player player, Optional<PlexPlayerView> view,
                               @Nullable String account)
    {
        if (player != null)
        {
            return player.getName();
        }
        String plex = view.map(PlexPlayerView::name).filter(name -> !name.isEmpty()).orElse(null);
        if (plex != null)
        {
            return plex;
        }
        String bukkit = Bukkit.getOfflinePlayer(uuid).getName();
        return bukkit != null ? bukkit : account;
    }

    // The online player's address, else the last IP Plex recorded for them, else none.
    private static List<String> ip(@Nullable Player player, Optional<PlexPlayerView> view)
    {
        InetSocketAddress address = player == null ? null : player.getAddress();
        if (address != null && address.getAddress() != null)
        {
            String host = address.getAddress().getHostAddress();
            int scope = host.indexOf('%');
            return List.of(scope < 0 ? host : host.substring(0, scope));
        }
        List<String> ips = view.map(PlexPlayerView::ips).orElse(List.of());
        return ips.isEmpty() ? List.of() : List.of(ips.getLast());
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
