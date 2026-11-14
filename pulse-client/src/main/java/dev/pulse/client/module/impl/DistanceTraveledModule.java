package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.ActionSetting;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Horizontal distance you have covered since the counter was last reset. Teleports are not counted. */
public final class DistanceTraveledModule extends LineModule {
    private double meters;

    public DistanceTraveledModule() {
        super("distancetraveled", "Distance Traveled", "How far you have travelled.", Category.HUD, 0.992f, 0.18f);
        add(new ActionSetting("Reset distance", "Reset", () -> meters = 0));
    }

    @Override protected String label() { return "TRAVEL"; }

    @Override
    protected String value(Minecraft mc) {
        return meters >= 1000 ? String.format(Locale.ROOT, "%.2f km", meters / 1000.0) : Math.round(meters) + " m";
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        double step = Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo);
        if (step < 10) meters += step; // anything bigger in one tick is a teleport or a world change
    }
}
