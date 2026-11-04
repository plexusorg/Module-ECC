package dev.plex.listener;

import dev.plex.ECCModule;
import dev.plex.ecc.nush.Quarantine.Kind;
import dev.plex.ecc.nush.Quarantine.LogEntry;
import dev.plex.ecc.nush.RaidDetector.Signal;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class CommandListener implements Listener
{
    // These commands change the world outside an edit session, so the shadow extent cannot fake them.
    private static final Set<String> BYPASS_COMMANDS =
            Set.of("regen", "butcher", "remove", "delchunks", "restore", "snapshot");

    private final ECCModule module;

    public CommandListener(ECCModule module)
    {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event)
    {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        module.raidDetector().record(Signal.COMMAND, uuid);
        if (event.isCancelled() || !module.isEnabled() || !module.quarantine().isRestricted(uuid))
        {
            return;
        }

        String message = event.getMessage();
        String label = message.replaceFirst("^/", "").replaceFirst("\\s.*", "").toLowerCase(Locale.ROOT);
        String baseLabel = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command == null)
        {
            command = Bukkit.getCommandMap().getCommand(baseLabel);
        }

        boolean blockedOnMute = isBlockedOnMute(label, baseLabel, command);
        // A leading slash survives the strip above only for WorldEdit's double slash commands.
        if (!blockedOnMute && !label.startsWith("/") && !isWorldEditCommand(command))
        {
            return;
        }

        if (blockedOnMute || !module.shadowActive() || BYPASS_COMMANDS.contains(baseLabel.replaceFirst("^/", "")))
        {
            event.setCancelled(true);
        }
        module.quarantine().record(uuid, new LogEntry(Kind.COMMAND, Instant.now(), message));
        module.feed().line(player, Kind.COMMAND, Component.text(player.getName() + ": " + message), message);
    }

    private boolean isBlockedOnMute(String label, String baseLabel, Command command)
    {
        for (String blocked : module.api().configuration().mainConfig().getStringList("block_on_mute"))
        {
            if (blocked.equalsIgnoreCase(label) || blocked.equalsIgnoreCase(baseLabel))
            {
                return true;
            }
            Command configured = Bukkit.getCommandMap().getCommand(blocked);
            if (command != null && command.equals(configured))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isWorldEditCommand(Command command)
    {
        if (!(command instanceof PluginIdentifiableCommand identifiable))
        {
            return false;
        }
        Plugin owner = identifiable.getPlugin();
        return owner.getName().equalsIgnoreCase("WorldEdit") || owner.getName().equalsIgnoreCase("FastAsyncWorldEdit");
    }
}
