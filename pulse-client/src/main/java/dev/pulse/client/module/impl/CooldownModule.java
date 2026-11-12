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

import java.util.ArrayList;
import java.util.List;

/** Shows item cooldowns (pearls, gapples, chorus, wind charges) as small draining bars. */
public final class CooldownModule extends Module {
    private static final ItemStack[] TRACKED = {
            new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.GOLDEN_APPLE),
            new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), new ItemStack(Items.CHORUS_FRUIT),
            new ItemStack(Items.WIND_CHARGE)};
    private static final int ROW = 19;

    private final BoolSetting pearl = add(new BoolSetting("Ender pearl", true), "Tracked items");
    private final BoolSetting gapple = add(new BoolSetting("Golden apple", true), "Tracked items");
    private final BoolSetting egapple = add(new BoolSetting("Enchanted golden apple", true), "Tracked items");
    private final BoolSetting chorus = add(new BoolSetting("Chorus fruit", true), "Tracked items");
    private final BoolSetting wind = add(new BoolSetting("Wind charge", true), "Tracked items");
    private final BoolSetting percent = add(new BoolSetting("Show percent", false));
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 70, 170, 2, 84, "px"), "Layout");
    private final SliderSetting barHeight = add(new SliderSetting("Bar height", 2, 8, 1, 4, "px"), "Layout");
    private final ColorSetting barColor = add(new ColorSetting("Bar color", 0xFFFFFFFF, false, true).withMode(1), "Colors");
    private final ColorSetting trackColor = add(new ColorSetting("Track color", Theme.LINE, true, false), "Colors");

    public CooldownModule() {
        super("cooldowns", "Cooldowns", "Active item cooldowns.", Category.HUD,
                true, 0.008f, 0.40f, true);
    }

    private boolean tracked(int i) {
        return switch (i) {
            case 0 -> pearl.value;
            case 1 -> gapple.value;
            case 2 -> egapple.value;
            case 3 -> chorus.value;
            default -> wind.value;
        };
    }

    /** {item index, remaining fraction}. */
    private List<float[]> active(Minecraft mc) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < TRACKED.length; i++) {
            if (!tracked(i)) continue;
            float p = 0f;
            if (HudRenderer.editing()) p = i == 0 ? 0.65f : i == 1 ? 0.3f : 0f;
            else if (mc.player != null) p = mc.player.getCooldowns().getCooldownPercent(TRACKED[i], 0f);
            if (p > 0f) out.add(new float[]{i, p});
        }
        return out;
    }

    @Override public int width(Minecraft mc) { return boxWidth.intValue(); }

    @Override
    public int height(Minecraft mc) {
        int n = active(mc).size();
        return n == 0 ? 0 : n * ROW + 3;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int row = 0;
        int bh = barHeight.intValue();
        for (float[] a : active(mc)) {
            int y = 3 + row++ * ROW;
            g.renderItem(TRACKED[(int) a[0]], 3, y);
            int bx = 24, bw = w - bx - (percent.value ? 30 : 6);
            int by = y + (16 - bh) / 2;
            g.fill(bx, by, bx + bw, by + bh, trackColor.color());
            g.fill(bx, by, bx + Math.round(bw * a[1]), by + bh, barColor.color());
            if (percent.value) {
                String p = Math.round(a[1] * 100) + "%";
                text(g, p, w - 5 - Theme.width(p), y + 4, dimColor());
            }
        }
    }
}
