package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class ZoomModule extends Module {
    // Vanilla clamps FOV to 30..110, so 30 is the strongest zoom without mixins.
    private final KeybindSetting key = add(new KeybindSetting("Zoom key", GLFW.GLFW_KEY_C));
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Hold", "Toggle"));
    private final SliderSetting fov = add(new SliderSetting("Zoom FOV", 30, 110, 1, 30, ""));
    private boolean zooming;
    private boolean toggled;
    private boolean keyWasDown;
    private int original;

    public ZoomModule() {
        super("zoom", "Zoom", "Hold a key to zoom in (default C).", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.getWindow() == null || mc.player == null) return;
        boolean keyDown = mc.screen == null && key.isDown(mc);
        boolean want;
        if (mode.index == 1) {
            if (keyDown && !keyWasDown) toggled = !toggled;
            want = toggled && mc.screen == null;
        } else {
            toggled = false;
            want = keyDown;
        }
        keyWasDown = keyDown;
        if (want && !zooming) {
            original = mc.options.fov().get();
            zooming = true;
        }
        if (zooming) {
            if (want) {
                mc.options.fov().set((int) fov.value);
            } else {
                restore(mc);
            }
        }
    }

    private void restore(Minecraft mc) {
        if (zooming && mc.options != null) mc.options.fov().set(original);
        zooming = false;
    }

    @Override
    public void onDisable() {
        toggled = false;
        restore(Minecraft.getInstance());
    }
}
