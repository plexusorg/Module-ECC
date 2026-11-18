package dev.plex.ecc.safety;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lines chat safety holds, by id, each with what delivers it. A line stays until
 * a release delivers it, a ban drops it, or the module stops. A line held because ECC-Server did not answer is
 * {@code unanswered} until the server gives it a verdict.
 */
final class Held
{
    // Guarded by this. Insertion order is the order the lines were held, which a line that waited for its verdict can
    // reach after a later line.
    private final Map<String, Entry> lines = new LinkedHashMap<>();
    private boolean closed;

    // Register before sending the request: an action can arrive before its HTTP verdict.
    synchronized boolean add(Line line, Delivery delivery)
    {
        if (closed)
        {
            return false;
        }
        lines.put(line.id(), new Entry(line, delivery, false, true));
        return true;
    }

    // A verdict cannot recreate an entry that an earlier release or ban removed.
    synchronized boolean hold(String id, boolean answered)
    {
        Entry entry = lines.get(id);
        if (entry == null)
        {
            return false;
        }
        lines.put(id, new Entry(entry.line(), entry.delivery(), answered, false));
        return true;
    }

    @Nullable
    synchronized Entry deliver(String id)
    {
        return lines.remove(id);
    }

    synchronized void close()
    {
        closed = true;
        lines.clear();
    }

    // Removes and returns the player's lines with these ids, in the order they were written. Ids of other players'
    // lines, and ids not held, are skipped.
    synchronized List<Entry> release(UUID speaker, Collection<String> ids)
    {
        List<Entry> released = new ArrayList<>();
        lines.values().removeIf(entry ->
        {
            boolean match = speaker.equals(entry.line().speakerUuid()) && ids.contains(entry.line().id());
            if (match)
            {
                released.add(entry);
            }
            return match;
        });
        released.sort(Comparator.comparingLong(entry -> entry.line().written()));
        return released;
    }

    // Removes the line when it is still held and still unanswered: ECC-Server has now answered deliver.
    @Nullable
    synchronized Entry answeredDeliver(String id)
    {
        Entry entry = lines.get(id);
        if (entry == null || entry.answered() || entry.pending())
        {
            return null;
        }
        return lines.remove(id);
    }

    synchronized void answeredHold(String id)
    {
        lines.computeIfPresent(id, (key, entry) -> new Entry(entry.line(), entry.delivery(), true, false));
    }

    // Makes every held line unanswered: ECC-Server started again and holds none of them.
    synchronized void unanswerAll()
    {
        lines.replaceAll((id, entry) -> new Entry(entry.line(), entry.delivery(), false, false));
    }

    // Drops every line of the player; the number dropped.
    synchronized int drop(UUID speaker)
    {
        int before = lines.size();
        lines.values().removeIf(entry -> speaker.equals(entry.line().speakerUuid()));
        return before - lines.size();
    }

    // The lines still waiting for a verdict, in the order they were written.
    synchronized List<Line> unanswered()
    {
        List<Line> waiting = new ArrayList<>();
        for (Entry entry : lines.values())
        {
            if (!entry.answered() && !entry.pending())
            {
                waiting.add(entry.line());
            }
        }
        waiting.sort(Comparator.comparingLong(Line::written));
        return waiting;
    }

    synchronized int size()
    {
        return lines.size();
    }

    synchronized int unansweredCount()
    {
        return (int) lines.values().stream().filter(entry -> !entry.answered()).count();
    }

    record Entry(Line line, Delivery delivery, boolean answered, boolean pending)
    {
    }
}
