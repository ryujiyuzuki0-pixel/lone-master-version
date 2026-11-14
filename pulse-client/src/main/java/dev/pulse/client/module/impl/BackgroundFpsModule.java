package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Caps the frame rate while the game window is not focused, so alt-tabbed Minecraft stops heating the GPU. */
public final class BackgroundFpsModule extends Module {
    private final SliderSetting limit = add(new SliderSetting("Background FPS", 5, 60, 5, 15, ""));
    private final OptionHold<Integer> hold = new OptionHold<>(Options::framerateLimit);

    public BackgroundFpsModule() {
        super("backgroundfps", "Background FPS", "Lowers the frame rate while the window is in the background.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.isWindowActive()) hold.release(mc);
        else hold.apply(mc, (int) limit.value);
    }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
