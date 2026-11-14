package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.util.KeyLatch;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Press once to start sneaking, press again to stop. */
public final class ToggleSneakModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Toggle key", GLFW.GLFW_KEY_RIGHT_CONTROL));
    private final KeyLatch latch = new KeyLatch();
    private boolean on;

    public ToggleSneakModule() {
        super("togglesneak", "Toggle Sneak", "Sneak without holding the key.", Category.GAMEPLAY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) {
            on = false;
            return;
        }
        if (latch.pressed(mc.screen == null && key.isDown(mc))) on = !on;
        if (on && mc.screen == null) mc.options.keyShift.setDown(true);
        else if (!on && mc.options.keyShift.isDown() && !isPhysicallyHeld(mc)) mc.options.keyShift.setDown(false);
    }

    private static boolean isPhysicallyHeld(Minecraft mc) {
        return mc.options.keyShift.isDown() && mc.screen == null && org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().handle(),
                GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS;
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (on && mc.options != null) mc.options.keyShift.setDown(false);
        on = false;
    }
}
