package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;

/** While on, modules that could show where you are or which server you play on hide that information. */
public final class StreamerModeModule extends Module {
    private static volatile boolean on;

    public StreamerModeModule() {
        super("streamermode", "Streamer Mode", "Hides your server address and the Nether conversion readout.", Category.UTILITY, false, 0f, 0f, false);
    }

    public static boolean active() {
        return on;
    }

    @Override public void onEnable() { on = true; }

    @Override public void onDisable() { on = false; }
}
