package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import dev.pulse.client.util.CpsTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class KeystrokesModule extends Module {
    private final BoolSetting mouse = add(new BoolSetting("Show mouse buttons", true));
    private final BoolSetting space = add(new BoolSetting("Show spacebar", true));
    private final BoolSetting showCps = add(new BoolSetting("Show CPS", true), () -> this.mouse.value);
    private final ChoiceSetting labels = add(new ChoiceSetting("Labels", 0, "WASD", "Arrows"));
    private final SliderSetting keySize = add(new SliderSetting("Key size", 16, 34, 1, 22, "px"), "Layout");
    private final SliderSetting gap = add(new SliderSetting("Key gap", 0, 8, 1, 2, "px"), "Layout");
    private final SliderSetting keyRadius = add(new SliderSetting("Key radius", 0, 8, 1, 3, "px"), "Layout");
    private final SliderSetting fade = add(new SliderSetting("Fade speed", 0.1, 1.0, 0.05, 0.35, ""), "Layout");
    private final ColorSetting keyColor = add(new ColorSetting("Key color", Theme.HUD_BG, true, true), "Colors");
    private final ColorSetting pressedColor = add(new ColorSetting("Pressed color", 0xD9FFFFFF, true, true).withMode(1), "Colors");
    private final ColorSetting keyText = add(new ColorSetting("Key text", Theme.TEXT, false, true), "Colors");
    private final ColorSetting pressedText = add(new ColorSetting("Pressed text", 0xFFFFFFFF, false, true), "Colors");
    private final float[] anim = new float[7];

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", "WASD, jump and mouse buttons with live CPS.",
                Category.HUD, true, 0.008f, 0.78f, false);
    }

    private int k() { return keySize.intValue(); }

    private int gp() { return gap.intValue(); }

    private int mouseH() { return showCps.value ? 26 : 20; }

    @Override
    public int width(Minecraft mc) {
        return 3 * k() + 2 * gp();
    }

    @Override
    public int height(Minecraft mc) {
        int h = 2 * k() + gp();
        if (mouse.value) h += gp() + mouseH();
        if (space.value) h += gp() + 14;
        return h;
    }

    private boolean down(KeyMapping key, int i) {
        boolean d = key.isDown() || (HudRenderer.editing() && i == 0);
        anim[i] += ((d ? 1f : 0f) - anim[i]) * (float) fade.value;
        return d;
    }

    private void key(GuiGraphics g, int x, int y, int w, int h, int i, String label, String sub, KeyMapping km) {
        down(km, i);
        int bg = Theme.lerp(keyColor.color(), pressedColor.color(), anim[i]);
        Theme.roundRect(g, x, y, w, h, (int) keyRadius.value, bg);
        int tc = Theme.lerp(keyText.color(), pressedText.color(), anim[i]);
        if (sub == null) {
            g.drawString(Theme.font(), label, x + (w - Theme.width(label)) / 2, y + (h - 8) / 2, tc, shadowOn());
        } else {
            g.drawString(Theme.font(), label, x + (w - Theme.width(label)) / 2, y + (h - 8) / 2 - 5, tc, shadowOn());
            int dc = Theme.lerp(dimColor(), 0xFFE0E6FF, anim[i]);
            g.drawString(Theme.font(), sub, x + (w - Theme.width(sub)) / 2, y + (h - 8) / 2 + 5, dc, false);
        }
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        var o = mc.options;
        int K = k(), G = gp();
        boolean arrows = labels.index == 1;
        key(g, K + G, 0, K, K, 0, arrows ? "\u2191" : "W", null, o.keyUp);
        key(g, 0, K + G, K, K, 1, arrows ? "\u2190" : "A", null, o.keyLeft);
        key(g, K + G, K + G, K, K, 2, arrows ? "\u2193" : "S", null, o.keyDown);
        key(g, 2 * (K + G), K + G, K, K, 3, arrows ? "\u2192" : "D", null, o.keyRight);
        int y = 2 * K + 2 * G;
        if (mouse.value) {
            int mh = mouseH();
            int mw = (w - G) / 2;
            key(g, 0, y, mw, mh, 4, "LMB", showCps.value ? String.valueOf(CpsTracker.left()) : null, o.keyAttack);
            key(g, mw + G, y, w - mw - G, mh, 5, "RMB", showCps.value ? String.valueOf(CpsTracker.right()) : null, o.keyUse);
            y += mh + G;
        }
        if (space.value) {
            down(o.keyJump, 6);
            int bg = Theme.lerp(keyColor.color(), pressedColor.color(), anim[6]);
            Theme.roundRect(g, 0, y, w, 14, (int) keyRadius.value, bg);
            int cy = y + 7, lw = 18;
            g.fill((w - lw) / 2, cy, (w + lw) / 2, cy + 1, Theme.lerp(keyText.color(), pressedText.color(), anim[6]));
        }
    }
}
