package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Base for modules that watch one condition and flash a message above the hotbar. The message shows when the
 * condition starts and then repeats at the chosen interval for as long as it holds.
 */
public abstract class AlertModule extends Module {
    private final SliderSetting repeat = add(new SliderSetting("Repeat every", 5, 120, 5, 30, "s"));
    private int ticks;
    private boolean wasActive;
    private long lastShown;

    protected AlertModule(String id, String name, String description, Category category) {
        super(id, name, description, category, false, 0f, 0f, false);
    }

    /** Returns the message to show, or null when everything is fine. */
    protected abstract String check(Minecraft mc);

    @Override
    public final void tick(Minecraft mc) {
        if (mc.player == null || mc.gui == null || ++ticks % 10 != 0) return;
        String msg = check(mc);
        if (msg == null) {
            wasActive = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (!wasActive || now - lastShown >= repeat.value * 1000) {
            mc.gui.setOverlayMessage(Component.literal(msg), false);
            lastShown = now;
        }
        wasActive = true;
    }
}
