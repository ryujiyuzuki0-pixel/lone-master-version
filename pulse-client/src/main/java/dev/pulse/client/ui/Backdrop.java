package dev.pulse.client.ui;

import dev.pulse.client.PulseClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * The snowy Minecraft landscape (assets/pulse/textures/gui/title_bg.png) drawn to cover the screen with a very
 * slow pan, plus animated falling snow. Used behind the title screen, the mods list and the Lone menu.
 */
public final class Backdrop {
    private static final int TEX_W = 1920, TEX_H = 1080;
    //? if >=1.21.11 {
    private static final Identifier BG = Identifier.fromNamespaceAndPath(PulseClient.ID, "textures/gui/title_bg.png");
    //?} else {
    /*private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(PulseClient.ID, "textures/gui/title_bg.png");
    *///?}

    private Backdrop() {}

    private static float hash(int n) {
        int h = n * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    public static void draw(GuiGraphics g, int w, int h, double time) {
        draw(g, w, h, time, true);
    }

    /** @param time seconds (already scaled by the speed setting) */
    public static void draw(GuiGraphics g, int w, int h, double time, boolean snow) {
        // cover the screen, zoomed in a little so the pan never shows an edge
        double scale = Math.max(w / (double) TEX_W, h / (double) TEX_H) * 1.08;
        int srcW = Math.min(TEX_W, (int) Math.ceil(w / scale));
        int srcH = Math.min(TEX_H, (int) Math.ceil(h / scale));
        float panX = 0.5f + 0.5f * (float) Math.sin(time * 0.07);
        float panY = 0.5f + 0.5f * (float) Math.sin(time * 0.05 + 1.3);
        float u = (TEX_W - srcW) * panX;
        float v = (TEX_H - srcH) * panY;
        g.blit(RenderPipelines.GUI_TEXTURED, BG, 0, 0, u, v, w, h, srcW, srcH, TEX_W, TEX_H);
        if (snow) flakes(g, w, h, time);
    }

    private static void flakes(GuiGraphics g, int w, int h, double time) {
        int count = Math.max(30, w * h / 2400);
        for (int i = 0; i < count; i++) {
            float speed = 7f + hash(i * 3 + 1) * 16f;
            float size = hash(i * 3 + 2) < 0.2f ? 2f : 1f;
            double x = hash(i * 3) * w + Math.sin(time * 0.7 + i) * 6 + time * 4;
            double y = hash(i * 7 + 5) * h + time * speed;
            int px = (int) (((x % w) + w) % w);
            int py = (int) (((y % h) + h) % h);
            float a = 0.45f + 0.5f * hash(i * 11 + 3);
            int s = (int) size;
            g.fill(px, py, px + s, py + s, Theme.alpha(0xFFFFFFFF, a));
        }
    }
}
