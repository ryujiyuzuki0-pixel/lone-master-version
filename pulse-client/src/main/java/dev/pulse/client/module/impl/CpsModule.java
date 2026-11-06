package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.util.CpsTracker;
import net.minecraft.client.Minecraft;

public final class CpsModule extends LineModule {
    private final BoolSetting showRight = add(new BoolSetting("Show right click", true));
    private final ChoiceSetting separator = add(new ChoiceSetting("Separator", 0, " | ", " / ", " - ", ", "), "Format", () -> this.showRight.value);

    public CpsModule() {
        super("cps", "CPS", "Clicks per second for both mouse buttons.", Category.GAMEPLAY, 0.008f, 0.05f);
    }

    @Override protected String label() { return "CPS"; }

    @Override
    protected String value(Minecraft mc) {
        return showRight.value ? CpsTracker.left() + separator.get() + CpsTracker.right() : String.valueOf(CpsTracker.left());
    }
}
