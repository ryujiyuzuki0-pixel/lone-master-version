package dev.pulse.client.ui;

import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.DeathInfoModule;
import dev.pulse.client.module.impl.DeathInfoModule.Death;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Death history: pick a death on the left, copy its coordinates / make a waypoint / delete it on the right. */
public class DeathInfoScreen extends Screen {
    private static final int ROW = 26;

    private final Screen parent;
    private final long openedAt = System.currentTimeMillis();
    private Death selected;
    private float scroll;
    private int px, py, pw, ph, listX, listY, listW, listH, detX, detY, detW, detH;
    private final int btnH = 18;

    public DeathInfoScreen(Screen parent) {
        super(Component.literal("Death Info"));
        this.parent = parent;
    }

    private DeathInfoModule mod() {
        return (DeathInfoModule) ModuleManager.byId("deathinfo");
    }

    private List<Death> list() {
        DeathInfoModule m = mod();
        return m == null ? List.of() : m.history();
    }

    @Override
    protected void init() {
        List<Death> l = list();
        if (selected == null || !l.contains(selected)) selected = l.isEmpty() ? null : l.get(0);
    }

    // button rects (computed in render, used by clicks)
    private int[] btn(int i) {
        int bw = Math.max(60, (detW - 20 - 12) / 3);
        return new int[]{detX + 10 + i * (bw + 6), detY + detH - btnH - 10, bw, btnH};
    }

    private int[] footer(int i) {
        int w = i == 0 ? 64 : 92;
        int x = i == 0 ? px + pw - 64 - 10 : px + 10;
        return new int[]{x, py + ph - btnH - 9, w, btnH};
    }

    private void button(GuiGraphics g, int[] r, String label, int mx, int my, boolean primary) {
        boolean hot = Theme.in(mx, my, r[0], r[1], r[2], r[3]);
        int base = primary ? Theme.ACCENT : Theme.L_CARD;
        int hi = primary ? Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.25f) : 0xFF232940;
        Theme.roundRect(g, r[0], r[1], r[2], r[3], 5, hot ? hi : base);
        Theme.centered(g, label, r[0] + r[2] / 2, r[1] + 5, primary ? 0xFFFFFFFF : Theme.L_TEXT, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        float intro = Theme.ease((System.currentTimeMillis() - openedAt) / 250f);
        double t = (System.currentTimeMillis() - openedAt) / 1000.0;
        Backdrop.draw(g, width, height, t, true);
        g.fill(0, 0, width, height, Theme.alpha(0xFF0A1E5C, 0.30f * intro));

        pw = Math.min(width - 24, 700);
        ph = Math.min(height - 24, 380);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 9, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 8, Theme.L_BG);
        Theme.shadow(g, "Death Info", px + 14, py + 11, Theme.L_TEXT);
        List<Death> deaths = list();
        String count = deaths.size() + (deaths.size() == 1 ? " death" : " deaths") + " recorded";
        Theme.text(g, count, px + pw - 14 - Theme.width(count), py + 11, Theme.L_DIM);
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        listX = px + 10;
        listY = py + 38;
        listW = (int) (pw * 0.42);
        listH = ph - 38 - 36;
        detX = listX + listW + 10;
        detY = listY;
        detW = px + pw - 10 - detX;
        detH = listH;
        drawList(g, mx, my, deaths);
        drawDetails(g, mx, my);

        button(g, footer(0), "Done", mx, my, true);
        button(g, footer(1), "Clear history", mx, my, false);
    }

    private void drawList(GuiGraphics g, int mx, int my, List<Death> deaths) {
        Theme.roundRect(g, listX - 1, listY - 1, listW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, listX, listY, listW, listH, 5, 0xFF0C0F17);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        if (deaths.isEmpty()) {
            Theme.centered(g, "No deaths yet. Stay alive!", listX + listW / 2, listY + listH / 2 - 4, Theme.L_DIM, false);
        }
        float max = Math.max(0, deaths.size() * ROW + 8 - listH);
        scroll = Math.max(0, Math.min(max, scroll));
        for (int i = 0; i < deaths.size(); i++) {
            Death d = deaths.get(i);
            int ry = listY + 4 + i * ROW - Math.round(scroll);
            if (ry + ROW < listY || ry > listY + listH) continue;
            boolean sel = d == selected;
            boolean hot = Theme.in(mx, my, listX + 4, ry, listW - 12, ROW - 3) && Theme.in(mx, my, listX, listY, listW, listH);
            if (sel) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.L_WHITE);
            else if (hot) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.alpha(0xFFFFFFFF, 0.08f));
            int ink = sel ? Theme.L_INK : Theme.L_TEXT;
            int sub = sel ? 0xFF6B7186 : Theme.L_DIM;
            Theme.text(g, Theme.fit(d.cause, listW - 28), listX + 12, ry + 4, ink);
            Theme.text(g, Theme.fit(DeathInfoModule.coords(d) + "  -  " + DeathInfoModule.ago(d.time), listW - 28), listX + 12, ry + 14, sub);
        }
        g.disableScissor();
    }

    private void drawDetails(GuiGraphics g, int mx, int my) {
        Theme.roundRect(g, detX - 1, detY - 1, detW + 2, detH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, detX, detY, detW, detH, 5, 0xFF0C0F17);
        if (selected == null) {
            Theme.centered(g, "Select a death to see its details.", detX + detW / 2, detY + detH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        Death d = selected;
        int x = detX + 14, y = detY + 14;
        Theme.scaledText(g, Theme.fit(d.cause, (int) ((detW - 28) / 1.2f)), x, y, 1.2f, Theme.L_TEXT, false);
        y += 22;
        String[][] rows = {
                {"Coordinates", DeathInfoModule.coords(d)},
                {"Dimension", d.dim},
                {"Time", DeathInfoModule.stamp(d.time) + "  (" + DeathInfoModule.ago(d.time) + ")"},
                {"Last health", String.format(java.util.Locale.ROOT, "%.1f hearts", d.health / 2f)},
                {"Where", d.where == null || d.where.isEmpty() ? "-" : d.where},
        };
        for (String[] r : rows) {
            Theme.text(g, r[0], x, y, Theme.L_DIM);
            Theme.text(g, Theme.fit(r[1], detW - 28 - 80), x + 80, y, Theme.L_TEXT);
            y += 14;
        }
        button(g, btn(0), "Copy coords", mx, my, true);
        button(g, btn(1), "Waypoint", mx, my, false);
        button(g, btn(2), "Delete", mx, my, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        int[] done = footer(0), clear = footer(1);
        if (Theme.in(mx, my, done[0], done[1], done[2], done[3])) {
            onClose();
            return true;
        }
        DeathInfoModule m = mod();
        if (m == null) return super.mouseClicked(e, dbl);
        if (Theme.in(mx, my, clear[0], clear[1], clear[2], clear[3])) {
            m.clearHistory();
            selected = null;
            return true;
        }
        if (selected != null) {
            int[] c = btn(0), w = btn(1), d = btn(2);
            if (Theme.in(mx, my, c[0], c[1], c[2], c[3])) {
                m.copyCoords(selected);
                return true;
            }
            if (Theme.in(mx, my, w[0], w[1], w[2], w[3])) {
                m.createWaypoint(selected);
                return true;
            }
            if (Theme.in(mx, my, d[0], d[1], d[2], d[3])) {
                m.remove(selected);
                List<Death> l = list();
                selected = l.isEmpty() ? null : l.get(0);
                return true;
            }
        }
        if (Theme.in(mx, my, listX, listY, listW, listH)) {
            List<Death> l = list();
            int i = (int) ((my - listY - 4 + Math.round(scroll)) / ROW);
            if (i >= 0 && i < l.size()) selected = l.get(i);
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (float) sy * 28f;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
