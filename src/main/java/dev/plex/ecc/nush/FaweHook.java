package dev.plex.ecc.nush;

import com.fastasyncworldedit.core.configuration.Settings;
import com.sk89q.worldedit.EditSession.Stage;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.World;
import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine.Kind;
import dev.plex.ecc.nush.Quarantine.LogEntry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public class FaweHook
{
    private final ECCModule module;

    public FaweHook(ECCModule module)
    {
        this.module = module;
    }

    public boolean register()
    {
        // FAWE only keeps a foreign extent when its class name contains an entry of extent.allowed-plugins.
        String extentName = ShadowExtent.class.getName();
        String lowerName = extentName.toLowerCase(Locale.ROOT);
        boolean allowed = Settings.settings().EXTENT.ALLOWED_PLUGINS.stream()
                .anyMatch(entry -> lowerName.contains(entry.toLowerCase(Locale.ROOT)));
        if (!allowed)
        {
            module.getLogger().error("FastAsyncWorldEdit drops the NUSH extent, so WorldEdit commands of restricted players are"
                    + " cancelled instead. Add the line '  - {}' below extent.allowed-plugins in"
                    + " plugins/FastAsyncWorldEdit/config.yml and restart the server", extentName);
            return false;
        }
        WorldEdit.getInstance().getEventBus().register(this);
        return true;
    }

    public void unregister()
    {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event)
    {
        Actor actor = event.getActor();
        if (!module.isEnabled() || event.getStage() != Stage.BEFORE_CHANGE || actor == null)
        {
            return;
        }
        UUID uuid = actor.getUniqueId();
        if (!module.quarantine().isRestricted(uuid))
        {
            return;
        }

        event.setExtent(new ShadowExtent(event.getExtent()));

        World world = event.getWorld();
        String text = world == null ? "edit session" : "edit session in " + world.getName();
        module.quarantine().record(uuid, new LogEntry(Kind.COMMAND, Instant.now(), text));
        Player player = Bukkit.getPlayer(uuid);
        if (player != null)
        {
            module.feed().line(player, Kind.COMMAND, Component.text(player.getName() + ": " + text), text);
        }
    }
}
