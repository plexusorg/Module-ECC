package dev.plex.ecc.safety;

/**
 * One sender's wait before it tries ECC-Server again: from {@link EccServer#FIRST_RETRY_MILLIS}, doubling up to
 * {@link EccServer#LAST_RETRY_MILLIS}. The wait ends early when any request finds the server answering again after it
 * did not, or when its sender is stopping.
 */
final class Backoff
{
    // Guarded by this.
    private long millis = EccServer.FIRST_RETRY_MILLIS;
    // Guarded by this. Set by wake() until the next pause.
    private boolean woken;

    // Waits the current time, or until woken; the next wait is twice as long.
    synchronized void pause() throws InterruptedException
    {
        long until = System.nanoTime() + millis * 1_000_000;
        long left = millis;
        while (!woken && left > 0)
        {
            wait(left);
            left = (until - System.nanoTime()) / 1_000_000;
        }
        woken = false;
        millis = Math.min(millis * 2, EccServer.LAST_RETRY_MILLIS);
    }

    synchronized void reset()
    {
        millis = EccServer.FIRST_RETRY_MILLIS;
    }

    // Ends the current wait, or the next one when none is under way.
    synchronized void wake()
    {
        woken = true;
        notifyAll();
    }
}
