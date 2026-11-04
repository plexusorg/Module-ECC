package dev.plex.listener;

import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine;
import dev.plex.ecc.nush.RaidDetector.Signal;
import dev.plex.ecc.nush.StaffFeed;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class JoinListener implements Listener
{
    private final ECCModule module;

    public JoinListener(ECCModule module)
    {
        this.module = module;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (player.hasPermission(StaffFeed.VIEW_PERMISSION))
        {
            module.feed().addStaff(uuid);
        }

        Quarantine quarantine = module.quarantine();
        quarantine.joined(player, System.currentTimeMillis(), false);
        module.raidDetector().record(Signal.JOIN, uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent event)
    {
        if (!module.isEnabled() || !module.quarantine().isRestricted(event.getPlayer().getUniqueId()))
        {
            return;
        }
        Component message = event.joinMessage();
        event.joinMessage(null);
        if (message != null)
        {
            module.feed().alert(message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event)
    {
        module.quarantine().pause(event.getPlayer().getUniqueId());
        module.feed().removeStaff(event.getPlayer().getUniqueId());
        if (!module.isEnabled() || !module.quarantine().isRestricted(event.getPlayer().getUniqueId()))
        {
            return;
        }
        Component message = event.quitMessage();
        event.quitMessage(null);
        if (message != null)
        {
            module.feed().alert(message);
        }
    }
}
