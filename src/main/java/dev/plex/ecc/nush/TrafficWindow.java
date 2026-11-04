package dev.plex.ecc.nush;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TrafficWindow
{
    private static final int HISTORY_SIZE = 300;
    private static final int LEARNING_DELAY_SECONDS = 60;

    private final int seconds;
    private final int minimum;
    private final long[] bucketTimes;
    private final int[] buckets;
    private final double[] history = new double[HISTORY_SIZE];
    private final Deque<Sample> pending = new ArrayDeque<>();
    private final Map<UUID, Long> contributors = new HashMap<>();
    private final Set<UUID> changedContributors = new HashSet<>();
    private long lastPruned = -1;
    private boolean aboveLimit;
    private int historySize;
    private int historyIndex;
    private double baseline;
    private double noise = 1;

    TrafficWindow(int seconds, int minimum)
    {
        this.seconds = seconds;
        this.minimum = minimum;
        // Retain one extra bucket to sample the last complete window while new events arrive.
        bucketTimes = new long[seconds + 1];
        Arrays.fill(bucketTimes, -1);
        buckets = new int[seconds + 1];
    }

    void record(long now, UUID uuid)
    {
        contributors.put(uuid, now);
        changedContributors.add(uuid);
        int index = (int) (now % buckets.length);
        if (bucketTimes[index] != now)
        {
            bucketTimes[index] = now;
            buckets[index] = 0;
        }
        if (buckets[index] < Integer.MAX_VALUE)
        {
            buckets[index]++;
        }
    }

    Set<UUID> pollContributors(long now, boolean reactivating)
    {
        if (lastPruned != now)
        {
            contributors.values().removeIf(time -> time <= now - seconds);
            changedContributors.retainAll(contributors.keySet());
            lastPruned = now;
        }
        boolean triggering = count(now) >= trigger();
        Set<UUID> selected = Set.of();
        if (triggering)
        {
            // Revisit the full window only on a crossing or reactivation, not on every spam event.
            selected = Set.copyOf(!aboveLimit || reactivating ? contributors.keySet() : changedContributors);
        }
        changedContributors.clear();
        aboveLimit = triggering;
        return selected;
    }

    long count(long now)
    {
        long count = 0;
        for (int index = 0; index < buckets.length; index++)
        {
            if (bucketTimes[index] > now - seconds && bucketTimes[index] <= now)
            {
                count += buckets[index];
            }
        }
        return count;
    }

    long trigger()
    {
        return (long) Math.ceil(Math.max(minimum, Math.max(5 * baseline, baseline + 6 * noise)));
    }

    long recovery()
    {
        return (long) Math.ceil(Math.max(minimum / 2.0, Math.max(2 * baseline, baseline + 3 * noise)));
    }

    double baseline()
    {
        return baseline;
    }

    void freeze()
    {
        // Discard the lead-up to a detected burst, not the pre-raid history.
        pending.clear();
    }

    void learn(long now)
    {
        pending.addLast(new Sample(now, count(now - 1)));
        boolean changed = false;
        while (!pending.isEmpty() && pending.peekFirst().time() <= now - LEARNING_DELAY_SECONDS)
        {
            history[historyIndex] = pending.removeFirst().count();
            historyIndex = (historyIndex + 1) % HISTORY_SIZE;
            historySize = Math.min(historySize + 1, HISTORY_SIZE);
            changed = true;
        }
        if (changed)
        {
            double[] sorted = Arrays.copyOf(history, historySize);
            Arrays.sort(sorted);
            baseline = median(sorted);
            for (int index = 0; index < sorted.length; index++)
            {
                sorted[index] = Math.abs(sorted[index] - baseline);
            }
            Arrays.sort(sorted);
            // MAD resists isolated outliers. The count-noise floor also covers sparse, constant samples.
            noise = Math.max(1, Math.max(Math.sqrt(baseline), 1.4826 * median(sorted)));
        }
    }

    private static double median(double[] sorted)
    {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    private record Sample(long time, long count)
    {
    }
}
