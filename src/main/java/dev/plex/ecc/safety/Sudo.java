package dev.plex.ecc.safety;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The lines another player forces with /sudo, which belong to the player who ran it, as the training pipeline credited
 * them. EssentialsX's {@code /sudo <player> c:<text>} calls {@code Player#chat} for each player it names while the
 * command runs, so Paper fires that player's chat event on the server thread, in the same tick; {@code /sudo <player>
 * <command>} schedules {@code Player#chat("/" + command)} for the next tick, which fires that player's command
 * preprocess event with exactly that text. Plex and its modules have no /sudo of their own.
 */
final class Sudo implements Listener
{
    private static final Set<String> COMMANDS = Set.of("sudo", "esudo");
    // How many ticks after the /sudo its forced command may come: EssentialsX runs it on the next tick.
    private static final int COMMAND_TICKS = 20;

    // Guarded by this. The /sudo commands of the last COMMAND_TICKS ticks, oldest first.
    private final Deque<Forced> forced = new ArrayDeque<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSudo(PlayerCommandPreprocessEvent event)
    {
        // Bukkit splits a command's arguments at each space; EssentialsX joins those after the player's name again.
        String[] parts = event.getMessage().split(" ", 3);
        if (parts.length < 3 || !isSudo(parts[0]))
        {
            return;
        }
        String what = parts[2];
        boolean prefixed = what.regionMatches(true, 0, "c:", 0, 2);
        String text = prefixed ? what.substring(2) : "/" + what;
        // Player#chat runs a text that starts with a slash as a command.
        String command = text.startsWith("/") ? text : null;
        synchronized (this)
        {
            prune();
            forced.addLast(new Forced(event.getPlayer().getUniqueId(), command, Bukkit.getCurrentTick()));
        }
    }

    /**
     * Who a chat line belongs to: the player whose /sudo forced it while it ran, else the speaker. Call it for a chat
     * event fired on the server thread, which is how a plugin's {@code Player#chat} fires it.
     */
    Player chatAuthor(Player speaker)
    {
        return author(speaker, null);
    }

    /**
     * Who a command belongs to: the player whose /sudo forced it, else the player who ran it.
     */
    Player commandAuthor(PlayerCommandPreprocessEvent event)
    {
        return author(event.getPlayer(), event.getMessage());
    }

    private Player author(Player speaker, @Nullable String command)
    {
        int tick = Bukkit.getCurrentTick();
        UUID issuer = null;
        synchronized (this)
        {
            prune();
            for (Forced entry : forced)
            {
                boolean match = command == null ? entry.command() == null && entry.tick() == tick
                        : command.equals(entry.command());
                if (match && !entry.issuer().equals(speaker.getUniqueId()))
                {
                    issuer = entry.issuer();
                }
            }
        }
        // An issuer who left in the tick between keeps nothing to be credited to.
        Player author = issuer == null ? null : Bukkit.getPlayer(issuer);
        return author != null ? author : speaker;
    }

    private void prune()
    {
        int tick = Bukkit.getCurrentTick();
        while (!forced.isEmpty() && tick - forced.peekFirst().tick() > COMMAND_TICKS)
        {
            forced.pollFirst();
        }
    }

    // /sudo or /esudo by any label: typed after any plugin: prefix, or a command Bukkit knows by that name.
    private static boolean isSudo(String label)
    {
        String name = label.substring(1).toLowerCase(Locale.ROOT);
        if (COMMANDS.contains(name.substring(name.lastIndexOf(':') + 1)))
        {
            return true;
        }
        Command command = Bukkit.getCommandMap().getCommand(label.substring(1));
        return command != null && COMMANDS.contains(command.getName().toLowerCase(Locale.ROOT));
    }

    // One /sudo: who ran it, the command it forces (null for a chat line), and the tick it ran in.
    private record Forced(UUID issuer, @Nullable String command, int tick)
    {
    }
}
