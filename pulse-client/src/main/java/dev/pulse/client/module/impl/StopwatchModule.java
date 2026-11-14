package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.util.KeyLatch;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** A stopwatch on your HUD with start/stop and reset keys, for timing runs and builds. */
public final class StopwatchModule extends LineModule {
    private final KeybindSetting toggleKey = add(new KeybindSetting("Start / stop key", GLFW.GLFW_KEY_KP_0));
    private final KeybindSetting resetKey = add(new KeybindSetting("Reset key", GLFW.GLFW_KEY_KP_DECIMAL));
    private final KeyLatch toggleLatch = new KeyLatch(), resetLatch = new KeyLatch();
    private long accumulatedMs, startedAt;
    private boolean running;

    public StopwatchModule() {
        super("stopwatch", "Stopwatch", "A timer you control with keys.", Category.HUD, 0.992f, 0.86f);
    }

    @Override protected String label() { return "TIMER"; }

    private long elapsed() {
        return accumulatedMs + (running ? System.currentTimeMillis() - startedAt : 0);
    }

    @Override
    protected String value(Minecraft mc) {
        long ms = elapsed();
        return String.format(Locale.ROOT, "%02d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10);
    }

    @Override
    protected int valueColor(Minecraft mc) {
        return running ? 0xFF4ADE80 : textColor();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.screen != null) return;
        if (toggleLatch.pressed(toggleKey.isDown(mc))) {
            if (running) accumulatedMs += System.currentTimeMillis() - startedAt;
            else startedAt = System.currentTimeMillis();
            running = !running;
        }
        if (resetLatch.pressed(resetKey.isDown(mc))) {
            accumulatedMs = 0;
            startedAt = System.currentTimeMillis();
        }
    }
}
