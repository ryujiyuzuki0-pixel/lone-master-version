package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

public final class ReachModule extends LineModule {
    private final SliderSetting decimals = add(new SliderSetting("Decimals", 0, 3, 1, 2, ""));
    private final SliderSetting resetAfter = add(new SliderSetting("Reset after", 0, 30, 1, 0, "s").zero("Never"));
    private final BoolSetting unit = add(new BoolSetting("Show unit", false));
    private double last = -1;
    private long lastTime;

    public ReachModule() {
        super("reach", "Reach", "Distance of your last hit.", Category.GAMEPLAY, 0.008f, 0.24f);
    }

    public void record(Player p, Entity e) {
        last = Math.sqrt(e.getBoundingBox().distanceToSqr(p.getEyePosition()));
        lastTime = System.currentTimeMillis();
    }

    @Override protected String label() { return "REACH"; }

    @Override
    protected String value(Minecraft mc) {
        if (last >= 0 && resetAfter.value > 0 && System.currentTimeMillis() - lastTime > resetAfter.value * 1000) last = -1;
        if (last < 0) return "-";
        return String.format(Locale.ROOT, "%." + decimals.intValue() + "f", last) + (unit.value ? " m" : "");
    }
}
