package dev.pulse.client.ui;

import dev.pulse.client.Brand;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.ClientModule;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The Lone Client opening menu: logo on top, a column of buttons in the middle
 * (SinglePlayer, Multiplayer, Cosmetic, Mods, Settings, Lone Mods) and your own skin on the right with a Wardrobe button.
 * "Settings" opens Minecraft's own options; the Lone Client settings live under "Lone Mods" (sidebar: Settings).
 * "Mods" lists the mods installed in your game; "Lone Mods" opens the client's built-in modules.
 */
public class LoneTitleScreen extends Screen {
    private static final String[] LABELS = {"SinglePlayer", "Multiplayer", "Cosmetic", "Mods", "Settings", "Lone Mods", "Texture Packs"};
    private static final int WARDROBE = 7;

    private final long start = System.currentTimeMillis();
    private final float[] hover = new float[8];
    private int selected = -1;
    private boolean keyboardSel;
    private float quitHover;
    private long lastFrame = System.nanoTime();

    // layout of the last frame (used for mouse hit tests)
    private int bx, bw, bh, step, y0, extra;
    private int wx, wy, ww, wh;
    private int plX, plFeet;
    private float plScale;
    private int quitX, quitY, quitW = 44, quitH = 15;

    public LoneTitleScreen() {
        super(Component.literal(Brand.NAME));
    }

    /** True when the Lone title screen should replace the vanilla one. */
    public static boolean enabled() {
        ClientModule c = ModuleManager.client();
        return c == null || c.loneTitle();
    }

    private boolean showPlayer() {
        ClientModule c = ModuleManager.client();
        return c == null || c.titlePlayer();
    }

    // ------------------------------------------------------------------ layout

    private void layout() {
        bh = Math.max(15, Math.round(height * 0.068f));
        step = Math.max(bh + 4, Math.round(height * 0.075f)); // 7 rows now
        bw = Math.max(100, Math.min(170, (int) (width * 0.2f)));
        bx = (width - bw) / 2;
        y0 = Math.round(height * 0.377f) - bh / 2;
        extra = Math.max(4, step / 3);

        ww = Math.max(64, Math.min(110, (int) (width * 0.13f)));
        wh = bh + 2;
        wx = (int) (width * 0.79f) - ww / 2;
        wy = Math.round(height * 0.735f);

        plX = (int) (width * 0.79f);
        plFeet = Math.round(height * 0.655f);
        plScale = height * 0.40f / 32f;

        quitX = width - quitW - 8;
        quitY = height - quitH - 7;
    }

    private int btnY(int i) {
        return i < 5 ? y0 + i * step : y0 + i * step + extra; // the last two sit apart from the vanilla-style five
    }

    private int hit(double mx, double my) {
        for (int i = 0; i < LABELS.length; i++) {
            if (Theme.in(mx, my, bx, btnY(i), bw, bh)) return i;
        }
        if (showPlayer() && Theme.in(mx, my, wx, wy, ww, wh)) return WARDROBE;
        return -1;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        ClientModule cm = ModuleManager.client();
        float speed = cm == null ? 1f : cm.titleSpeed();
        boolean animated = cm == null || cm.titleAnimated();
        boolean snow = cm == null || cm.titleSnow();
        float anim = cm == null ? 1f : cm.animSpeed();
        double elapsed = (System.currentTimeMillis() - start) / 1000.0;
        double t = animated ? elapsed * speed : 0;

        layout();
        Backdrop.draw(g, width, height, t, snow);

        // hover / keyboard selection
        int h = hit(mx, my);
        if (h >= 0) {
            selected = h;
            keyboardSel = false;
        } else if (!keyboardSel) {
            selected = -1;
        }
        for (int i = 0; i < hover.length; i++) {
            hover[i] += ((i == selected ? 1f : 0f) - hover[i]) * Math.min(1f, dt * 14f * anim);
        }
        boolean qh = Theme.in(mx, my, quitX, quitY, quitW, quitH);
        quitHover += ((qh ? 1f : 0f) - quitHover) * Math.min(1f, dt * 14f * anim);

        float intro = Theme.ease((float) (elapsed / 0.8));
        drawLogo(g, intro, t);

        if (showPlayer()) {
            float pi = Theme.ease((float) ((elapsed - 0.25) / 0.7));
            PlayerPreview.draw(g, minecraft, plX + Math.round((1 - pi) * 24), plFeet, plScale, elapsed, pi);
            drawButton(g, wx, wy, ww, wh, "Wardrobe", hover[WARDROBE], pi, 0);
        }

        for (int i = 0; i < LABELS.length; i++) {
            float a = Theme.ease((float) ((elapsed - 0.15 - i * 0.07) / 0.45));
            int slide = Math.round((1 - a) * 14);
            drawButton(g, bx - slide, btnY(i), bw - (i >= 5 ? 6 : 0), bh, LABELS[i], hover[i], a, i >= 5 ? 3 : 0);
        }
        drawFooter(g, intro);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, float hv, float a, int xOff) {
        if (a <= 0.01f) return;
        x += xOff;
        int r = Math.min(h / 2, 9);
        int infl = Math.round(hv * 2);
        int lift = Math.round(hv);
        // soft shadow + hover glow
        Theme.roundRect(g, x, y + 2, w, h, r, Theme.alpha(0xFF2C5CC0, 0.16f * a));
        if (hv > 0.02f) {
            Theme.roundRect(g, x - infl - 2, y - lift - infl - 2, w + 2 * infl + 4, h + 2 * infl + 4, r + 2,
                    Theme.alpha(0xFFFFFFFF, 0.42f * hv * a));
        }
        // frosted outline + body (white -> blue when hovered)
        Theme.roundRect(g, x - infl - 1, y - lift - infl - 1, w + 2 * infl + 2, h + 2 * infl + 2, r + 1, Theme.alpha(0xFFFFFFFF, 0.65f * a));
        int body = Theme.lerp(0xFFF1F6FF, 0xFF6C97F2, hv);
        Theme.roundRect(g, x - infl, y - lift - infl, w + 2 * infl, h + 2 * infl, r, Theme.alpha(body, a));
        g.fill(x + r, y - lift - infl + 1, x + w - r, y - lift - infl + 2, Theme.alpha(0xFFFFFFFF, 0.8f * a));
        // label
        Component c = Component.literal(label).withStyle(ChatFormatting.BOLD);
        int tw = Theme.font().width(c);
        g.drawString(Theme.font(), c, x + (w - tw) / 2, y - lift + (h - 8) / 2, Theme.alpha(0xFF14246B, a), false);
    }

    private void drawLogo(GuiGraphics g, float intro, double t) {
        int logoY = Math.round(height * 0.14f) - Math.round((1 - intro) * 10);
        float scale = Math.max(1.6f, Math.min(2.8f, height / 105f));
        // Two-tier wordmark: LONE (bold, large) + CLIENT (small, letter-spaced, bottom-aligned to it).
        Component word = Component.literal(Brand.WORD).withStyle(ChatFormatting.BOLD);
        float subScale = scale * 0.5f;
        int mainW = (int) (Theme.font().width(word) * scale);
        int gap = Math.max(5, (int) (scale * 3));
        int subW = spacedWidth(Brand.SUB, subScale);
        int textW = mainW + gap + subW;
        int emblem = (int) (28 * Math.max(1f, scale / 2.2f));
        int total = emblem + 8 + textW;
        int x0 = (width - total) / 2;
        int cy = logoY + (int) (9 * scale / 2f) + 2;

        // sparkles around the logo
        for (int i = 0; i < 26; i++) {
            float fx = (hash(i * 2) - 0.5f) * total * 1.35f;
            float fy = (hash(i * 2 + 1) - 0.5f) * (scale * 14f);
            float tw = (float) Math.max(0, Math.sin(t * 1.6 + i * 1.9));
            int sx = width / 2 + Math.round(fx), sy = cy + Math.round(fy);
            int sz = hash(i + 50) < 0.25f ? 2 : 1;
            g.fill(sx, sy, sx + sz, sy + sz, Theme.alpha(0xFFFFFFFF, 0.9f * tw * intro));
            if (sz == 2 && tw > 0.6f) {
                g.fill(sx - 1, sy, sx, sy + 1, Theme.alpha(0xFFFFFFFF, 0.5f * intro));
                g.fill(sx + 2, sy, sx + 3, sy + 1, Theme.alpha(0xFFFFFFFF, 0.5f * intro));
            }
        }

        drawEmblem(g, x0 + emblem / 2 + 1, cy + 1, emblem / 2, Theme.alpha(0xFF2A58C8, 0.55f * intro));
        drawEmblem(g, x0 + emblem / 2, cy, emblem / 2, Theme.alpha(0xFFFFFFFF, intro));

        int subY = Math.round(cy - 4 * scale + 8 * scale - 8 * subScale);
        for (int pass = 0; pass < 2; pass++) {
            float off = pass == 0 ? scale * 0.5f : 0;
            int shade = pass == 0 ? Theme.alpha(0xFF2A58C8, 0.5f * intro) : Theme.alpha(0xFFFFFFFF, intro);
            g.pose().pushMatrix();
            g.pose().translate(x0 + emblem + 8 + off, cy - 4 * scale + off);
            g.pose().scale(scale, scale);
            g.drawString(Theme.font(), word, 0, 0, shade, false);
            g.pose().popMatrix();
            drawSpaced(g, Brand.SUB, x0 + emblem + 8 + mainW + gap + Math.round(off), subY + Math.round(off), subScale,
                    pass == 0 ? shade : Theme.alpha(0xFFDCE8FF, 0.95f * intro));
        }

        // glow line under the logo
        int ly = cy + (int) (6 * scale) + 8;
        int lw = Math.min(width - 40, (int) (total * 1.25));
        int lx = (width - lw) / 2;
        for (int i = 0; i < lw; i += 2) {
            float f = (float) Math.sin(Math.PI * i / (double) lw);
            f = f * f;
            g.fill(lx + i, ly, lx + i + 2, ly + 1, Theme.alpha(0xFFFFFFFF, 0.9f * f * intro));
            g.fill(lx + i, ly + 1, lx + i + 2, ly + 2, Theme.alpha(0xFF9CC4FF, 0.35f * f * intro));
        }
        float bloom = 0.5f + 0.5f * (float) Math.sin(t * 1.5);
        Theme.roundRect(g, width / 2 - 30, ly - 1, 60, 3, 1, Theme.alpha(0xFFFFFFFF, (0.45f + 0.3f * bloom) * intro));
    }

    private static int spacedWidth(String s, float scale) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += Math.round((Theme.width(String.valueOf(s.charAt(i))) + 1.5f) * scale);
        return w;
    }

    /** Draws small caps-style text with a little extra space between letters. */
    private static void drawSpaced(GuiGraphics g, String s, int x, int y, float scale, int color) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            Theme.scaledText(g, ch, Math.round(cx), y, scale, color, false);
            cx += (Theme.width(ch) + 1.5f) * scale;
        }
    }

    private static float hash(int n) {
        int h = n * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }

    private void drawEmblem(GuiGraphics g, int cx, int cy, int r, int color) {
        int th = Math.max(2, r / 5);
        for (int dy = -r; dy <= r; dy++) {
            int outer = hexHalf(dy, r);
            if (Math.abs(dy) <= r - th) {
                int inner = hexHalf(dy, r - th);
                g.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, color);
                g.fill(cx + inner, cy + dy, cx + outer, cy + dy + 1, color);
            } else {
                g.fill(cx - outer, cy + dy, cx + outer, cy + dy + 1, color);
            }
        }
        Icons.draw(g, Icons.DIAMOND, cx - 4, cy - 5, 1, color);
        for (int i = 0; i < 5; i++) {
            g.fill(cx - r + 2 - i * 2, cy + r - 6 + i * 2, cx - r + 5 - i * 2, cy + r - 3 + i * 2, color);
        }
    }

    private static int hexHalf(int dy, int r) {
        int ady = Math.abs(dy);
        if (r <= 0) return 0;
        if (ady <= r / 2) return (int) Math.round(r * 0.866);
        return Math.max(0, (int) Math.round((r - ady) * 1.732));
    }

    private void drawFooter(GuiGraphics g, float intro) {
        ClientModule cm = ModuleManager.client();
        if (cm == null || cm.titleVersion()) {
            g.drawString(Theme.font(), Brand.versionLine(), 8, height - 14,
                    Theme.alpha(0xFF2A4A9A, 0.85f * intro), false);
        }
        int bgc = Theme.lerp(Theme.alpha(0xFFFFFFFF, 0.55f), Theme.alpha(0xFF6C97F2, 0.95f), quitHover);
        Theme.roundRect(g, quitX, quitY, quitW, quitH, 6, Theme.alpha(bgc, intro));
        Theme.centered(g, "Quit", quitX + quitW / 2, quitY + (quitH - 8) / 2, Theme.alpha(0xFF14246B, intro), false);
    }

    // ------------------------------------------------------------------ input

    private void activate(int i) {
        switch (i) {
            case 0 -> minecraft.setScreen(new SelectWorldScreen(this));
            case 1 -> minecraft.setScreen(new JoinMultiplayerScreen(this));
            case 2 -> minecraft.setScreen(new MenuScreen(this, MenuScreen.Page.COSMETICS));
            case WARDROBE -> minecraft.setScreen(new MenuScreen(this, MenuScreen.Page.SKINS));
            case 3 -> minecraft.setScreen(new InstalledModsScreen(this));
            case 4 -> minecraft.setScreen(new OptionsScreen(this, minecraft.options)); // the real Minecraft settings
            case 5 -> minecraft.setScreen(new MenuScreen(this, MenuScreen.Page.HUD));
            case 6 -> minecraft.setScreen(new PacksScreen(this));
            default -> {
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() == 0) {
            if (Theme.in(e.x(), e.y(), quitX, quitY, quitW, quitH)) {
                minecraft.stop();
                return true;
            }
            int i = hit(e.x(), e.y());
            if (i >= 0) {
                activate(i);
                return true;
            }
            // clicking the player also opens the wardrobe
            if (showPlayer() && Theme.in(e.x(), e.y(), plX - (int) (plScale * 8), plFeet - (int) (plScale * 32),
                    (int) (plScale * 16), (int) (plScale * 32))) {
                activate(WARDROBE);
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int n = LABELS.length;
        switch (e.key()) {
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_TAB -> {
                selected = selected < 0 || selected >= n ? 0 : (selected + 1) % n;
                keyboardSel = true;
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                selected = selected <= 0 || selected >= n ? n - 1 : selected - 1;
                keyboardSel = true;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (selected >= 0) activate(selected);
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                return true;
            }
            default -> {
                return super.keyPressed(e);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
