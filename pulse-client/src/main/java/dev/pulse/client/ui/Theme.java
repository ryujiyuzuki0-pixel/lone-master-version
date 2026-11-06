package dev.pulse.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class Theme {
    public static final int BG = 0xFF0E1120;
    public static final int PANEL = 0xFF161A2E;
    public static final int PANEL_HI = 0xFF1D2240;
    public static final int LINE = 0xFF262C4D;
    public static final int TEXT = 0xFFE8EAF6;
    public static final int DIM = 0xFF8A90B2;
    public static int ACCENT = 0xFF3B82F6;
    public static int ACCENT2 = 0xFF7DD3FC;

    // Lone Client menu palette
    public static final int L_BG = 0xFF090B12;
    public static final int L_SIDE = 0xFF0B0E16;
    public static final int L_LINE = 0xFF1A1E2C;
    public static final int L_CARD = 0xFF10131D;
    public static final int L_CARD_HI = 0xFF181C2A;
    public static final int L_TEXT = 0xFFE7E9F1;
    public static final int L_DIM = 0xFF7B8197;
    public static final int L_DIM2 = 0xFF4A5068;
    public static final int L_WHITE = 0xFFF4F6FA;
    public static final int L_INK = 0xFF0B0D14;
    public static final int GOOD = 0xFF4ADE80;
    public static final int WARN = 0xFFFBBF24;
    public static final int BAD = 0xFFF87171;
    public static final int HUD_BG = 0xA00E1120;

    private Theme() {}

    public static void setAccents(int a, int b) {
        ACCENT = a;
        ACCENT2 = b;
    }

    /** Hue in degrees to opaque ARGB (no java.awt dependency). */
    public static int hsb(float hue, float sat, float val) {
        float h = ((hue % 360f) + 360f) % 360f / 60f;
        int i = (int) h;
        float f = h - i, p = val * (1 - sat), q = val * (1 - sat * f), t = val * (1 - sat * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = val; g = t; b = p; }
            case 1 -> { r = q; g = val; b = p; }
            case 2 -> { r = p; g = val; b = t; }
            case 3 -> { r = p; g = q; b = val; }
            case 4 -> { r = t; g = p; b = val; }
            default -> { r = val; g = p; b = q; }
        }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int width(String s) {
        return font().width(s);
    }

    public static int alpha(int c, float a) {
        int al = (int) (((c >>> 24) & 0xFF) * a);
        return (al << 24) | (c & 0xFFFFFF);
    }

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void roundRect(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
        g.fill(x, y + r, x + w, y + h - r, color);
    }

    public static void hGradient(GuiGraphics g, int x, int y, int w, int h, int c1, int c2) {
        for (int i = 0; i < w; i += 2) {
            int cw = Math.min(2, w - i);
            g.fill(x + i, y, x + i + cw, y + h, lerp(c1, c2, (float) i / Math.max(1, w - 1)));
        }
    }

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, false);
    }

    public static void shadow(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, true);
    }

    public static void centered(GuiGraphics g, String s, int cx, int y, int color, boolean shadow) {
        g.drawString(font(), s, cx - width(s) / 2, y, color, shadow);
    }

    public static void scaledText(GuiGraphics g, String s, int x, int y, float scale, int color, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font(), s, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    public static String fit(String s, int maxWidth) {
        if (width(s) <= maxWidth) return s;
        String ell = "...";
        int end = s.length();
        while (end > 0 && width(s.substring(0, end)) + width(ell) > maxWidth) end--;
        return s.substring(0, Math.max(0, end)) + ell;
    }

    /** HUD card background: translucent body with a thin accent line on top. */
    public static void panel(GuiGraphics g, int w, int h) {
        roundRect(g, 0, 0, w, h, 4, HUD_BG);
        hGradient(g, 4, 0, Math.max(0, w - 8), 1, ACCENT, ACCENT2);
    }

    public static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    public static void toggle(GuiGraphics g, int x, int y, float anim) {
        roundRect(g, x, y, 26, 14, 7, lerp(0xFF2B3050, ACCENT, anim));
        roundRect(g, x + 2 + Math.round(12 * anim), y + 2, 10, 10, 5, 0xFFFFFFFF);
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean primary, boolean hover) {
        int bg = primary ? (hover ? lerp(ACCENT, 0xFFFFFFFF, 0.2f) : ACCENT) : (hover ? 0xFF2A3052 : PANEL_HI);
        roundRect(g, x, y, w, h, 4, bg);
        centered(g, label, x + w / 2, y + (h - 8) / 2, primary ? 0xFFFFFFFF : TEXT, false);
    }

    public static void chevron(GuiGraphics g, int cx, int cy, boolean open, int color) {
        for (int i = 0; i < 4; i++) {
            if (open) {
                g.fill(cx - 3 + i, cy - 1 + i, cx - 2 + i, cy + i, color);
                g.fill(cx + 3 - i, cy - 1 + i, cx + 4 - i, cy + i, color);
            } else {
                g.fill(cx - 1 + i, cy - 3 + i, cx + i, cy - 2 + i, color);
                g.fill(cx - 1 + i, cy + 3 - i, cx + i, cy + 4 - i, color);
            }
        }
    }
}
