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

/** Drop the render distance with a key when the game stutters, restore it with the same key. */
public final class RenderDistanceKeyModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Toggle key", GLFW.GLFW_KEY_F6));
    private final SliderSetting low = add(new SliderSetting("Low render distance", 2, 16, 1, 6, " chunks"));
    private final OptionHold<Integer> hold = new OptionHold<>(Options::renderDistance);
    private final KeyLatch latch = new KeyLatch();

    public RenderDistanceKeyModule() {
        super("renderdistancekey", "Render Distance Key", "Switch to a short render distance for more FPS and back.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!latch.pressed(mc.screen == null && key.isDown(mc))) return;
        if (hold.held()) {
            hold.release(mc);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Render distance restored"), false);
        } else {
            hold.apply(mc, (int) low.value);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Render distance " + (int) low.value), false);
        }
    }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
