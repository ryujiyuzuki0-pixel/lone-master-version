package dev.pulse.client.util;

/** Turns a held-down key into a single "just pressed" event per press. */
public final class KeyLatch {
    private boolean was;

    /** Feed the key's current state every tick; returns true only on the tick it goes down. */
    public boolean pressed(boolean down) {
        boolean r = down && !was;
        was = down;
        return r;
    }
}
