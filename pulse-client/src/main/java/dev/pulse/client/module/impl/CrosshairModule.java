package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.HitResult;

/** Replaces the vanilla crosshair with a configurable one. */
public final class CrosshairModule extends Module {
    private final ChoiceSetting style = add(new ChoiceSetting("Style", 0, "Cross", "Dot", "Circle", "Cross + dot", "Square"));
    private final SliderSetting size = add(new SliderSetting("Size", 2, 24, 1, 5, ""));
    private final SliderSetting gap = add(new SliderSetting("Gap", 0, 12, 1, 2, ""));
    private final SliderSetting thick = add(new SliderSetting("Thickness", 1, 5, 1, 1, ""));
    private final SliderSetting dotSize = add(new SliderSetting("Dot size", 1, 8, 1, 3, ""), () -> this.style.index == 1 || this.style.index == 3);
    private final BoolSetting tShape = add(new BoolSetting("T-shape (no top arm)", false), () -> this.style.index == 0 || this.style.index == 3);
    private final ColorSetting color = add(new ColorSetting("Crosshair color", 0xFFFFFFFF, true, true), "Colors");
    private final BoolSetting outline = add(new BoolSetting("Outline", true), "Colors");
    private final ColorSetting outlineColor = add(new ColorSetting("Outline color", 0xFF000000, true, true), "Colors", () -> this.outline.value);
    private final BoolSetting onTarget = add(new BoolSetting("Change color on target", false), "Colors");
    private final ColorSetting targetColor = add(new ColorSetting("Target color", 0xFFF87171, true, true), "Colors", () -> this.onTarget.value);

    public CrosshairModule() {
        super("crosshair", "Custom Crosshair", "Replace the vanilla crosshair with your own style.",
                Category.VISUAL, false, 0f, 0f, false);
    }

    private int pick(Minecraft mc) {
        if (onTarget.value && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return targetColor.color();
        }
        return color.color();
    }

    private void box(GuiGraphics g, int x1, int y1, int x2, int y2, int c) {
        if (outline.value) g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, outlineColor.color());
        g.fill(x1, y1, x2, y2, c);
    }

    public void render(GuiGraphics g, Minecraft mc) {
        if (mc.screen != null || !mc.options.getCameraType().isFirstPerson()) return;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        int c = pick(mc);
        int s = (int) size.value, gp = (int) gap.value, t = (int) thick.value;
        int lo = t / 2, hi = t - lo;
        boolean cross = style.index == 0 || style.index == 3;
        boolean dot = style.index == 1 || style.index == 3;

        if (cross) {
            box(g, cx - gp - s, cy - lo, cx - gp, cy + hi, c);
            box(g, cx + gp, cy - lo, cx + gp + s, cy + hi, c);
            if (!tShape.value) box(g, cx - lo, cy - gp - s, cx + hi, cy - gp, c);
            box(g, cx - lo, cy + gp, cx + hi, cy + gp + s, c);
        }
        if (dot) {
            int d = (int) dotSize.value;
            int dl = d / 2, dh = d - dl;
            box(g, cx - dl, cy - dl, cx + dh, cy + dh, c);
        }
        if (style.index == 2) {
            int r = s + gp;
            if (outline.value) {
                for (int a = 0; a < 360; a += 4) {
                    double rad = Math.toRadians(a);
                    int x = cx + (int) Math.round(Math.cos(rad) * r);
                    int y = cy + (int) Math.round(Math.sin(rad) * r);
                    g.fill(x - 1, y - 1, x + t + 1, y + t + 1, outlineColor.color());
                }
            }
            for (int a = 0; a < 360; a += 4) {
                double rad = Math.toRadians(a);
                int x = cx + (int) Math.round(Math.cos(rad) * r);
                int y = cy + (int) Math.round(Math.sin(rad) * r);
                g.fill(x, y, x + t, y + t, c);
            }
        }
        if (style.index == 4) {
            int r = s + gp;
            box(g, cx - r, cy - r, cx + r, cy - r + t, c);
            box(g, cx - r, cy + r - t, cx + r, cy + r, c);
            box(g, cx - r, cy - r + t, cx - r + t, cy + r - t, c);
            box(g, cx + r - t, cy - r + t, cx + r, cy + r - t, c);
        }
    }
}
