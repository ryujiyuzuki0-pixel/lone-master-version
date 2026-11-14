package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.util.KeyLatch;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class CopyCoordsModule extends Module {
    private final KeybindSetting key = add(new KeybindSetting("Copy key", GLFW.GLFW_KEY_F7));
    private final KeyLatch latch = new KeyLatch();

    public CopyCoordsModule() {
        super("copycoords", "Copy Coordinates", "Copies your coordinates to the clipboard with one key.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || !latch.pressed(mc.screen == null && key.isDown(mc))) return;
        BlockPos p = mc.player.blockPosition();
        String s = p.getX() + " " + p.getY() + " " + p.getZ();
        mc.keyboardHandler.setClipboard(s);
        if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Copied " + s), false);
    }
}
