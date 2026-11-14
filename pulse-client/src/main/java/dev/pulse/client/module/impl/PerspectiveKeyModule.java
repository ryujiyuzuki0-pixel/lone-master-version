package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Hold a key for a third-person view, release to go back to the view you had. */
public final class PerspectiveKeyModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Hold key", GLFW.GLFW_KEY_V));
    private final ChoiceSetting view = add(new ChoiceSetting("View", 0, "Behind you", "In front of you"));
    private CameraType saved;

    public PerspectiveKeyModule() {
        super("perspectivekey", "Perspective Key", "Hold a key to look at yourself from outside.", Category.VISUAL, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.options == null) return;
        boolean want = mc.player != null && mc.screen == null && key.isDown(mc);
        if (want && saved == null) {
            saved = mc.options.getCameraType();
            mc.options.setCameraType(view.index == 1 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
        } else if (!want) {
            restore(mc);
        }
    }

    private void restore(Minecraft mc) {
        if (saved == null) return;
        CameraType back = saved;
        saved = null;
        mc.options.setCameraType(back);
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) restore(mc);
    }
}
