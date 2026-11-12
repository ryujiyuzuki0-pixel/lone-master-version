package dev.pulse.client.util;

import net.minecraft.client.Minecraft;

/**
 * Shared switches for the hooks that answer reads of vanilla options (Custom Brightness, FOV Changer).
 * While the game writes options.txt, or while the client itself needs the player's real value, those hooks
 * must stay out of the way so the file and the vanilla sliders always hold the player's own values.
 */
public final class OptionGuard {
    /** True while Options#save runs. Reset every client tick as a safety net. */
    public static volatile boolean saving;
    /** True while {@link #rawFov} reads the real FOV option. */
    private static volatile boolean bypass;

    private OptionGuard() {}

    /** True when option reads must return the player's real value. */
    public static boolean passThrough() {
        return saving || bypass;
    }

    /** The FOV the player chose in the vanilla settings, ignoring the FOV Changer. */
    public static int rawFov(Minecraft mc) {
        bypass = true;
        try {
            return mc.options.fov().get();
        } finally {
            bypass = false;
        }
    }

    /** True while the vanilla video settings screen is open (it must show the player's real values). */
    public static boolean inVideoSettings(Minecraft mc) {
        return mc.screen != null && mc.screen.getClass().getName().contains("VideoSettingsScreen");
    }
}
