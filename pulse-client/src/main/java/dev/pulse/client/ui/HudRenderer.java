package dev.pulse.client.ui;

import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.util.CpsTracker;
import dev.pulse.client.util.FrameClock;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class HudRenderer {
    private static boolean editing;

    private HudRenderer() {}

    public static boolean editing() {
        return editing;
    }

    public static void setEditing(boolean b) {
        editing = b;
    }

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        FrameClock.tick();
        CpsTracker.poll(mc);
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen instanceof HudEditorScreen) return;
        for (Module m : ModuleManager.all()) {
            if (m.enabled) m.drawOverlay(g, mc);
        }
        for (Module m : ModuleManager.all()) {
            if (m.hud && m.enabled) drawModule(g, m, mc);
        }
    }

    /** {x, y, scaledW, scaledH, w, h} in GUI pixels. */
    public static int[] rect(Module m, Minecraft mc) {
        int w = m.width(mc), h = m.height(mc);
        float s = m.scaleValue();
        int bw = Math.round(w * s), bh = Math.round(h * s);
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = Math.round(m.fx * Math.max(0, sw - bw));
        int y = Math.round(m.fy * Math.max(0, sh - bh));
        return new int[]{x, y, bw, bh, w, h};
    }

    public static void drawModule(GuiGraphics g, Module m, Minecraft mc) {
        int[] r = rect(m, mc);
        if (r[4] <= 0 || r[5] <= 0) return;
        g.pose().pushMatrix();
        g.pose().translate(r[0], r[1]);
        g.pose().scale(m.scaleValue(), m.scaleValue());
        m.drawBackground(g, r[4], r[5]);
        m.draw(g, mc, r[4], r[5]);
        g.pose().popMatrix();
    }
}
