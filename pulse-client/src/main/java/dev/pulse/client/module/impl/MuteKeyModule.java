package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.util.KeyLatch;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

/** One key to mute the game completely and bring your volume back. */
public final class MuteKeyModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Mute key", GLFW.GLFW_KEY_F8));
    private final OptionHold<Double> hold = new OptionHold<>(o -> o.getSoundSourceOptionInstance(SoundSource.MASTER));
    private final KeyLatch latch = new KeyLatch();

    public MuteKeyModule() {
        super("mutekey", "Mute Key", "Press a key to mute or unmute all game sound.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!latch.pressed(mc.screen == null && key.isDown(mc))) return;
        if (hold.held()) {
            hold.release(mc);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Sound on"), false);
        } else {
            hold.apply(mc, 0.0);
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Sound muted"), false);
        }
    }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
