package dev.plex.listener;

import dev.plex.ECCModule;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Runs a checked command line again as its author. {@code Player#performCommand} does not fire
 * {@code PlayerCommandPreprocessEvent}, so the check does not see the command a second time.
 */
final class Commands
{
    private Commands()
    {
    }

    // Runs the command on the author's own thread; when the author has left, runs {@code otherwise} on the server
    // thread instead.
    static void perform(ECCModule module, Player player, String command, Runnable otherwise)
    {
        ScheduledTask task = player.getScheduler().run(module.plugin(), scheduled -> player.performCommand(command),
                () -> Bukkit.getGlobalRegionScheduler().execute(module.plugin(), otherwise));
        if (task == null)
        {
            Bukkit.getGlobalRegionScheduler().execute(module.plugin(), otherwise);
            return;
        }
        module.ownTask(task);
    }
}
