package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class TotemModule extends Module {
    private final ItemStack icon = new ItemStack(Items.TOTEM_OF_UNDYING);
    private final BoolSetting showIcon = add(new BoolSetting("Show icon", true));
    private final BoolSetting prefix = add(new BoolSetting("Show \"x\" prefix", true));
    private final BoolSetting hideZero = add(new BoolSetting("Hide when zero", false));
    private final SliderSetting warnBelow = add(new SliderSetting("Warn below", 0, 5, 1, 1, "").zero("Off"));

    public TotemModule() {
        super("totems", "Totem Count", "Totems of Undying in your inventory.", Category.HUD,
                true, 0.008f, 0.164f, true);
    }

    private int count(Minecraft mc) {
        if (mc.player == null) return 0;
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.TOTEM_OF_UNDYING)) n += s.getCount();
        }
        return n;
    }

    private String label(int c) {
        return (prefix.value ? "x" : "") + c;
    }

    @Override
    public int width(Minecraft mc) {
        if (hideZero.value && !HudRenderer.editing() && count(mc) == 0) return 0;
        return (showIcon.value ? 25 : 8) + Theme.width(label(count(mc))) + 8;
    }

    @Override public int height(Minecraft mc) { return 22; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int c = count(mc);
        if (showIcon.value) g.renderItem(icon, 4, 3);
        int color = warnBelow.value > 0 && c < warnBelow.value ? Theme.BAD : textColor();
        text(g, label(c), showIcon.value ? 25 : 8, 7, color);
    }
}
