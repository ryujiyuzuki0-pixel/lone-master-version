package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Pulsing colored screen edges when your health is low. */
public final class LowHealthModule extends Module {
    private final SliderSetting below = add(new SliderSetting("Hearts below", 1, 10, 1, 4, ""));
    private final SliderSetting strength = add(new SliderSetting("Strength", 0.1, 1.0, 0.05, 0.6, ""));
    private final SliderSetting edge = add(new SliderSetting("Edge size", 10, 50, 1, 20, "%"));
    private final ColorSetting color = add(new ColorSetting("Vignette color", 0xFFE02020, false, true), "Colors");
    private final BoolSetting pulse = add(new BoolSetting("Pulse", true), "Animation");
    private final SliderSetting pulseSpeed = add(new SliderSetting("Pulse speed", 0.3, 3.0, 0.1, 1.0, "x"), "Animation", () -> this.pulse.value);
    private final BoolSetting creativeToo = add(new BoolSetting("Show in creative", false), "Animation");

    public LowHealthModule() {
        super("lowhealth", "Low Health Vignette", "Pulsing colored edges when you're nearly dead.",
                Category.VISUAL, false, 0f, 0f, false);
    }

    @Override
    public void drawOverlay(GuiGraphics g, Minecraft mc) {
        if (mc.player == null || mc.screen != null || mc.player.isSpectator()) return;
        if (mc.player.isCreative() && !creativeToo.value) return;
        float hp = mc.player.getHealth();
        if (hp > below.value * 2 || hp <= 0) return;
        float urgency = 1f - hp / (float) (below.value * 2);
        float p = pulse.value ? 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 220.0 * pulseSpeed.value) : 1f;
        float a = (float) strength.value * p * (0.5f + 0.5f * urgency);
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        int edgePx = Math.max(24, (int) (h * edge.value / 100.0));
        int base = color.color() & 0xFFFFFF;
        int solid = Theme.alpha(0xFF000000 | base, a), none = base;
        g.fillGradient(0, 0, w, edgePx, solid, none);
        g.fillGradient(0, h - edgePx, w, h, none, solid);
        int side = edgePx;
        for (int i = 0; i < side; i += 2) {
            float t = 1f - i / (float) side;
            int c = Theme.alpha(0xFF000000 | base, a * t);
            g.fill(i, edgePx, i + 2, h - edgePx, c);
            g.fill(w - i - 2, edgePx, w - i, h - edgePx, c);
        }
    }
}
