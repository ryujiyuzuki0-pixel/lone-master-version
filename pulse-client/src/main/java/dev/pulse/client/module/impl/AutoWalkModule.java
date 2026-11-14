package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.util.KeyLatch;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Press once to keep walking forward; press again or press the back key to stop. */
public final class AutoWalkModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Walk key", GLFW.GLFW_KEY_GRAVE_ACCENT));
    private final KeyLatch latch = new KeyLatch();
    private boolean on;

    public AutoWalkModule() {
        super("autowalk", "Auto Walk", "Keeps you walking forward hands-free.", Category.GAMEPLAY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) {
            on = false;
            return;
        }
        if (latch.pressed(mc.screen == null && key.isDown(mc))) on = !on;
        if (on && mc.options.keyDown.isDown()) on = false;
        if (on && mc.screen == null) mc.options.keyUp.setDown(true);
        else if (!on && mc.options.keyUp.isDown() && GLFW.glfwGetKey(mc.getWindow().handle(), GLFW.GLFW_KEY_W) != GLFW.GLFW_PRESS) {
            mc.options.keyUp.setDown(false);
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (on && mc.options != null) mc.options.keyUp.setDown(false);
        on = false;
    }
}
