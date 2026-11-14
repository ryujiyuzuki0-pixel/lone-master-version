package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.KeyLatch;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One key to cap the frame rate (to save power or heat) and the same key to put your own limit back. */
public final class FpsCapKeyModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Cap key", GLFW.GLFW_KEY_F10));
    private final SliderSetting cap = add(new SliderSetting("Capped FPS", 20, 240, 10, 60, ""));
    private final OptionHold<Integer> hold = new OptionHold<>(Options::framerateLimit);
    private final KeyLatch latch = new KeyLatch();

    public FpsCapKeyModule() {
        super("fpscapkey", "FPS Cap Key", "Cap the frame rate with a key and uncap it again.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!latch.pressed(mc.screen == null && key.isDown(mc))) return;
        if (hold.held()) {
            hold.release(mc);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("FPS cap off"), false);
        } else {
            hold.apply(mc, (int) cap.value);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("FPS capped at " + (int) cap.value), false);
        }
    }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
