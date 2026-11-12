package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The 27 main inventory slots (and optionally the hotbar) as a grid on the HUD. */
public final class InventoryHudModule extends Module {
    private final BoolSetting hotbar = add(new BoolSetting("Include hotbar", false));
    private final BoolSetting counts = add(new BoolSetting("Show item counts", true));
    private final BoolSetting slotBg = add(new BoolSetting("Slot background", true), "Layout");
    private final ColorSetting slotColor = add(new ColorSetting("Slot color", 0x8C1D2240, true, true), "Layout", () -> this.slotBg.value);
    private final SliderSetting gap = add(new SliderSetting("Slot gap", 0, 4, 1, 0, "px"), "Layout");

    public InventoryHudModule() {
        super("inventory_hud", "Inventory", "Your main inventory, always visible.", Category.HUD,
                true, 0.5f, 0.96f, true);
    }

    private int cell() { return 18 + gap.intValue(); }

    private int rows() { return hotbar.value ? 4 : 3; }

    @Override public int width(Minecraft mc) { return 9 * cell() + 6 - gap.intValue(); }

    @Override public int height(Minecraft mc) { return rows() * cell() + 6 - gap.intValue(); }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        if (mc.player == null) return;
        var inv = mc.player.getInventory();
        int cell = cell();
        int total = rows() * 9;
        for (int i = 0; i < total; i++) {
            int x = 3 + (i % 9) * cell;
            int y = 3 + (i / 9) * cell;
            if (slotBg.value) g.fill(x, y, x + 16 + 2, y + 16 + 2, slotColor.color());
            // main inventory rows first, hotbar (slots 0-8) as the last row
            int slot = i < 27 ? 9 + i : i - 27;
            ItemStack s = inv.getItem(slot);
            if (s.isEmpty() && HudRenderer.editing() && i < 4) {
                s = new ItemStack(i == 0 ? Items.DIAMOND_SWORD : i == 1 ? Items.ENDER_PEARL
                        : i == 2 ? Items.GOLDEN_APPLE : Items.TOTEM_OF_UNDYING);
            }
            if (s.isEmpty()) continue;
            g.renderItem(s, x + 1, y + 1);
            if (counts.value) g.renderItemDecorations(Theme.font(), s, x + 1, y + 1);
        }
    }
}
