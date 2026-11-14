package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;

public final class HungerAlertModule extends AlertModule {
    private final SliderSetting threshold = add(new SliderSetting("Warn at food", 1, 10, 1, 6, ""));

    public HungerAlertModule() {
        super("hungeralert", "Hunger Alert", "Warns when your food bar is low.", Category.GAMEPLAY);
    }

    @Override
    protected String check(Minecraft mc) {
        int food = mc.player.getFoodData().getFoodLevel();
        return food <= threshold.value ? "You are hungry (" + food + "/20)" : null;
    }
}
