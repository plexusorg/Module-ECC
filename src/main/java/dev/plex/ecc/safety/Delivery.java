package dev.plex.ecc.safety;

/**
 * What delivers one line to the players who should see it: what the event would have done had it not been held.
 * Called from any thread; each delivery runs its work on the thread its channel needs.
 */
@FunctionalInterface
public interface Delivery
{
    void deliver();
}
