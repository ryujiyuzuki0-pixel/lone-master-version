package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.KeyLatch;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

/** Flip between your normal GUI scale and another one with a key (handy for screenshots or small windows). */
public final class GuiScaleKeyModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Scale key", GLFW.GLFW_KEY_F9));
    private final SliderSetting scale = add(new SliderSetting("Alternate scale", 1, 4, 1, 2, ""));
    private final OptionHold<Integer> hold = new OptionHold<>(Options::guiScale);
    private final KeyLatch latch = new KeyLatch();

    public GuiScaleKeyModule() {
        super("guiscalekey", "GUI Scale Key", "Switches to another GUI scale with a key and back again.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!latch.pressed(mc.screen == null && key.isDown(mc))) return;
        if (hold.held()) hold.release(mc); else hold.apply(mc, (int) scale.value);
        mc.resizeDisplay();
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (hold.held()) {
            hold.release(mc);
            mc.resizeDisplay();
        }
    }
}
