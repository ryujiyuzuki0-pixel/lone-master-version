package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ArmorModule extends Module {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private final BoolSetting numbers = add(new BoolSetting("Show durability", true));
    private final ChoiceSetting format = add(new ChoiceSetting("Durability format", 0, "Number", "Percent"), () -> this.numbers.value);
    private final BoolSetting colorByDurability = add(new BoolSetting("Color by durability", true), () -> this.numbers.value);
    private final SliderSetting spacing = add(new SliderSetting("Spacing", 0, 8, 1, 0, "px"), "Layout");
    private ItemStack[] sample;

    public ArmorModule() {
        super("armor", "Armor", "Equipped armor with remaining durability.", Category.HUD,
                true, 0.992f, 0.55f, false);
    }

    private int step() { return 19 + spacing.intValue(); }

    @Override
    public int width(Minecraft mc) {
        return numbers.value ? 16 + 6 + Theme.width("0000") + 6 : 22;
    }

    @Override
    public int height(Minecraft mc) {
        return 4 * step() + 3 - spacing.intValue();
    }

    private ItemStack stack(Minecraft mc, int i) {
        if (HudRenderer.editing()) {
            if (sample == null) {
                sample = new ItemStack[]{new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
                        new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS)};
            }
            return sample[i];
        }
        return mc.player == null ? ItemStack.EMPTY : mc.player.getItemBySlot(SLOTS[i]);
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        for (int i = 0; i < 4; i++) {
            ItemStack s = stack(mc, i);
            int y = 3 + i * step();
            if (s.isEmpty()) continue;
            g.renderItem(s, 3, y);
            if (numbers.value && s.isDamageableItem()) {
                int left = s.getMaxDamage() - s.getDamageValue();
                float ratio = left / (float) s.getMaxDamage();
                int c = !colorByDurability.value ? textColor()
                        : ratio > 0.5f ? Theme.GOOD : ratio > 0.2f ? Theme.WARN : Theme.BAD;
                String label = format.index == 1 ? Math.round(ratio * 100) + "%" : String.valueOf(left);
                text(g, label, 25, y + 4, c);
            }
        }
    }
}
