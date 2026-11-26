package dev.plex.listener;

import com.oasis.api.Cause;
import com.oasis.api.EditLogger;
import com.oasis.api.Oasis;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * The only class that uses the Oasis API. Create it only while Oasis is enabled. The module carries out a checked sign
 * or book itself, after the event Oasis logs from, so it logs what it wrote here.
 */
public final class OasisHook
{
    private final EditLogger logger;

    public OasisHook(Plugin plugin)
    {
        this.logger = Oasis.api(plugin).logger();
    }

    // The checked text written on a sign, as its author's edit. The sign's event wrote the blank side, so without
    // this row Oasis holds a blank sign where the world shows the text, and a rollback leaves the sign.
    public void signWritten(UUID author, BlockState before, BlockState after)
    {
        logger.by(author).block(Cause.SIGN, before, after);
    }

    // The checked book edit applied to its author's book. The book's event was cancelled, so Oasis logged nothing.
    public void bookWritten(UUID author, Location at, BookMeta book)
    {
        logger.by(author).book(at, book);
    }
}
