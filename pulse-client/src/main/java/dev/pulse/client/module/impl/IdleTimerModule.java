package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/** How long it has been since you last moved, turned or clicked. Handy for spotting AFK time. */
public final class IdleTimerModule extends LineModule {
    private long lastActive = System.currentTimeMillis();
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;

    public IdleTimerModule() {
        super("idletimer", "Idle Timer", "Time since you last did anything.", Category.HUD, 0.992f, 0.14f);
    }

    @Override protected String label() { return "IDLE"; }

    @Override
    protected String value(Minecraft mc) {
        long s = (System.currentTimeMillis() - lastActive) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    @Override
    protected int valueColor(Minecraft mc) {
        return System.currentTimeMillis() - lastActive > 60_000 ? 0xFFFBBF24 : textColor();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        boolean moved = Math.abs(mc.player.getX() - lastX) + Math.abs(mc.player.getY() - lastY) + Math.abs(mc.player.getZ() - lastZ) > 0.01
                || Math.abs(mc.player.getYRot() - lastYaw) > 0.1f || Math.abs(mc.player.getXRot() - lastPitch) > 0.1f
                || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
        if (moved) lastActive = System.currentTimeMillis();
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        lastZ = mc.player.getZ();
        lastYaw = mc.player.getYRot();
        lastPitch = mc.player.getXRot();
    }
}
