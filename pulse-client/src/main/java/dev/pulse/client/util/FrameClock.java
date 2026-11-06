package dev.pulse.client.util;

public final class FrameClock {
    private static long windowStart = System.nanoTime();
    private static int frames;
    private static float fps;

    private FrameClock() {}

    public static void tick() {
        frames++;
        long now = System.nanoTime();
        long dt = now - windowStart;
        if (dt >= 500_000_000L) {
            fps = frames * 1_000_000_000f / dt;
            frames = 0;
            windowStart = now;
        }
    }

    public static int fps() {
        return Math.round(fps);
    }
}
