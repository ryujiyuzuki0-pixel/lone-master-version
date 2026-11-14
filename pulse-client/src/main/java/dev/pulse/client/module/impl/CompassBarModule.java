package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** A scrolling compass strip showing the direction you face. */
public final class CompassBarModule extends Module {
    private static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float RANGE = 75f; // degrees visible either side of the centre

    public CompassBarModule() {
        super("compassbar", "Compass Bar", "A compass strip that scrolls as you turn.", Category.HUD, true, 0.5f, 0.01f, true);
    }

    @Override public int width(Minecraft mc) { return 170; }

    @Override public int height(Minecraft mc) { return 20; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        float yaw = mc.player == null ? 0f : mc.player.getYRot();
        float heading = Mth.wrapDegrees(yaw + 180f); // 0 = north, 90 = east
        int half = w / 2 - 8;
        for (int a = -180; a < 180; a += 15) {
            float delta = Mth.wrapDegrees(a - heading);
            if (Math.abs(delta) > RANGE) continue;
            int x = w / 2 + Math.round(delta / RANGE * half);
            int idx = Math.floorMod(a, 360);
            boolean major = idx % 45 == 0;
            float fade = 1f - Math.abs(delta) / RANGE * 0.7f;
            if (major) {
                String name = NAMES[idx / 45];
                text(g, name, x - Theme.width(name) / 2, 4, Theme.alpha(textColor(), fade));
            } else {
                g.fill(x, 8, x + 1, 12, Theme.alpha(dimColor(), fade));
            }
        }
        g.fill(w / 2, 15, w / 2 + 1, 18, 0xFFFFFFFF);
    }
}
