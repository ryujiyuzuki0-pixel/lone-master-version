package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;

public final class ComboModule extends LineModule {
    private final SliderSetting resetAfter = add(new SliderSetting("Reset after", 1, 10, 0.5, 3, "s"));
    private final SliderSetting highlightAt = add(new SliderSetting("Highlight at", 2, 30, 1, 5, " hits"), "Colors");
    private final ColorSetting highlight = add(new ColorSetting("Highlight color", 0xFFFFFFFF, false, true).withMode(2), "Colors");
    private int combo;
    private long lastHit;
    private boolean wasHurt;

    public ComboModule() {
        super("combo", "Combo", "Consecutive hits without taking damage.", Category.GAMEPLAY, 0.008f, 0.2f);
    }

    public void hit() {
        combo++;
        lastHit = System.currentTimeMillis();
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        boolean hurt = mc.player.hurtTime > 0;
        if (hurt && !wasHurt) combo = 0;
        wasHurt = hurt;
        if (combo > 0 && System.currentTimeMillis() - lastHit > resetAfter.value * 1000) combo = 0;
    }

    @Override protected String label() { return "COMBO"; }

    @Override protected String value(Minecraft mc) { return String.valueOf(combo); }

    @Override protected int valueColor(Minecraft mc) { return combo >= highlightAt.value ? highlight.color() : textColor(); }
}
