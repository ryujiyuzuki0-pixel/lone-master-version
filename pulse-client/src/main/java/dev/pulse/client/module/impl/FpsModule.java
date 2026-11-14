package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import dev.pulse.client.util.FrameClock;
import net.minecraft.client.Minecraft;

public final class FpsModule extends LineModule {
    private final BoolSetting dynamic = add(new BoolSetting("Dynamic color", true), "Colors");
    private final SliderSetting good = add(new SliderSetting("Good above", 30, 300, 5, 100, " fps"), "Colors", () -> this.dynamic.value);
    private final SliderSetting low = add(new SliderSetting("Low below", 10, 120, 5, 45, " fps"), "Colors", () -> this.dynamic.value);

    public FpsModule() {
        super("fps", "FPS", "Frames per second.", Category.HUD, 0.008f, 0.012f);
    }

    @Override protected String label() { return "FPS"; }

    @Override protected String value(Minecraft mc) { return String.valueOf(FrameClock.fps()); }

    @Override
    protected int valueColor(Minecraft mc) {
        if (!dynamic.value) return textColor();
        int f = FrameClock.fps();
        return f >= good.value ? Theme.GOOD : f >= low.value ? textColor() : f >= low.value * 0.55 ? Theme.WARN : Theme.BAD;
    }
}
