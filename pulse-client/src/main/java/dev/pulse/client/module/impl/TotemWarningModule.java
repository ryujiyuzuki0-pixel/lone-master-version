package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

public final class TotemWarningModule extends AlertModule {
    private final SliderSetting health = add(new SliderSetting("Warn at health", 2, 12, 1, 6, ""));

    public TotemWarningModule() {
        super("totemwarning", "Totem Warning", "Warns when you are low on health with no totem in either hand.", Category.GAMEPLAY);
    }

    @Override
    protected String check(Minecraft mc) {
        if (mc.player.getHealth() > health.value) return null;
        boolean held = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        return held ? null : "Low health and no totem in hand";
    }
}
