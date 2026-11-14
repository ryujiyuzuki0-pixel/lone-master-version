package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class DurabilityAlertModule extends AlertModule {
    private final SliderSetting threshold = add(new SliderSetting("Warn below", 5, 50, 5, 15, "%"));

    public DurabilityAlertModule() {
        super("durabilityalert", "Durability Alert", "Warns when armor or the item in your hand is nearly broken.", Category.GAMEPLAY);
    }

    @Override
    protected String check(Minecraft mc) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND}) {
            ItemStack s = mc.player.getItemBySlot(slot);
            if (!s.isDamageableItem()) continue;
            int left = Math.round(100f * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage());
            if (left < threshold.value) return "Low durability: " + s.getHoverName().getString() + " (" + left + "%)";
        }
        return null;
    }
}
