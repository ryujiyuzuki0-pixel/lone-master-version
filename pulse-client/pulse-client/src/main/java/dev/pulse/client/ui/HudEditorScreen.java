package dev.pulse.client.ui;

import dev.pulse.client.PulseConfig;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drag-and-drop HUD editor. Left: every HUD element with a visibility switch. Canvas: drag elements (they snap to
 * screen edges, the centre and each other, with guide lines) or nudge with the arrow keys. Right: scale, text
 * colour, background and shadow for the selected element. Bottom: saved layouts you can store and swap in one click.
 */
public class HudEditorScreen extends Screen {
    private static final int[] SWATCHES = {0xFFFFFFFF, 0xFFE5E7EB, 0xFFF87171, 0xFFFB923C, 0xFFFBBF24, 0xFF4ADE80,
            0xFF2DD4BF, 0xFF38BDF8, 0xFF818CF8, 0xFFF472B6};
    private static final int SNAP = 5, PANEL_W = 150, ROW = 18;

    private record Hit(int x, int y, int w, int h, String id) {}

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private String hot = "";
    private float dt = 0.016f;
    private long lastNs = System.nanoTime();

    private Module selected, dragging;
    private double offX, offY;
    private boolean snapping = true;
    private int guideX = -1, guideY = -1;
    private boolean sliderDrag;
    private float listScroll, listTarget;
    private EditBox nameBox;
    private boolean naming;
    private String message = "";

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        HudRenderer.setEditing(true);
        nameBox = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Layout name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(24);
        nameBox.setTextColor(Theme.L_TEXT);
        addWidget(nameBox);
    }

    @Override
    public void removed() {
        HudRenderer.setEditing(false);
    }

    // ------------------------------------------------------------------ data

    private List<Module> hudModules() {
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.all()) if (m.hud && !m.hidden) out.add(m);
        return out;
    }

    private List<Module> shown() {
        List<Module> out = new ArrayList<>();
        for (Module m : hudModules()) if (m.enabled) out.add(m);
        return out;
    }

    private float hv(String id) {
        float cur = hover.getOrDefault(id, 0f);
        cur += ((id.equals(hot) ? 1f : 0f) - cur) * Math.min(1f, dt * 16f);
        hover.put(id, cur);
        return cur;
    }

    private void hit(int x, int y, int w, int h, String id) {
        hits.add(new Hit(x, y, w, h, id));
    }

    private void toast(String s) {
        message = s;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        listScroll += (listTarget - listScroll) * Math.min(1f, dt * 16f);
        hits.clear();
        hot = "";

        g.fill(0, 0, width, height, 0x99050710);
        for (int x = 0; x < width; x += 40) g.fill(x, 0, x + 1, height, 0x0CFFFFFF);
        for (int y = 0; y < height; y += 40) g.fill(0, y, width, y + 1, 0x0CFFFFFF);
        if (guideX >= 0) g.fill(guideX, 0, guideX + 1, height, Theme.alpha(Theme.ACCENT, 0.8f));
        if (guideY >= 0) g.fill(0, guideY, width, guideY + 1, Theme.alpha(Theme.ACCENT, 0.8f));
        g.fill(width / 2, 0, width / 2 + 1, 6, 0x66FFFFFF);
        g.fill(0, height / 2, 6, height / 2 + 1, 0x66FFFFFF);

        Theme.centered(g, "Edit HUD", width / 2, 10, Theme.TEXT, true);
        Theme.centered(g, "Drag to move. Arrow keys nudge (hold Shift for 10px). Click an element to edit it.", width / 2, 24, Theme.DIM, false);

        var mc = minecraft;
        for (Module m : shown()) {
            HudRenderer.drawModule(g, m, mc);
            int[] r = HudRenderer.rect(m, mc);
            if (r[2] <= 0) continue;
            boolean isSel = m == selected;
            boolean over = dragging == null && Theme.in(mx, my, r[0], r[1], r[2], r[3]);
            int c = isSel ? Theme.L_WHITE : over || m == dragging ? Theme.ACCENT : 0x55FFFFFF;
            g.fill(r[0] - 2, r[1] - 2, r[0] + r[2] + 2, r[1] - 1, c);
            g.fill(r[0] - 2, r[1] + r[3] + 1, r[0] + r[2] + 2, r[1] + r[3] + 2, c);
            g.fill(r[0] - 2, r[1] - 1, r[0] - 1, r[1] + r[3] + 1, c);
            g.fill(r[0] + r[2] + 1, r[1] - 1, r[0] + r[2] + 2, r[1] + r[3] + 1, c);
            if (isSel || over) Theme.shadow(g, m.name, r[0], Math.max(2, r[1] - 12), Theme.TEXT);
        }

        drawElementList(g);
        drawInspector(g);
        drawBottomBar(g);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(mx, my, h.x, h.y, h.w, h.h)) {
                hot = h.id;
                break;
            }
        }
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        Theme.roundRect(g, x - 1, y - 1, w + 2, h + 2, 7, Theme.L_LINE);
        Theme.roundRect(g, x, y, w, h, 6, Theme.alpha(Theme.L_BG, 0.96f));
    }

    private int listTop() {
        return 56;
    }

    private int listHeight() {
        return height - listTop() - 70;
    }

    private void drawElementList(GuiGraphics g) {
        int x = 8, y = 40, w = PANEL_W, h = height - 40 - 62;
        panel(g, x, y, w, h);
        Theme.text(g, "ELEMENTS", x + 10, y + 7, Theme.L_DIM2);
        List<Module> mods = hudModules();
        int top = listTop() + 6, viewH = listHeight() - 6;
        int content = mods.size() * ROW;
        float max = Math.max(0, content - viewH);
        listTarget = Math.max(0, Math.min(max, listTarget));
        g.enableScissor(x, top, x + w, top + viewH);
        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            int ry = top + i * ROW - Math.round(listScroll);
            if (ry + ROW < top || ry > top + viewH) continue;
            String id = "el:" + m.id;
            float hh = hv(id);
            if (m == selected) Theme.roundRect(g, x + 4, ry, w - 8, ROW - 2, 4, Theme.L_CONTROL_HI);
            else if (hh > 0.01f) Theme.roundRect(g, x + 4, ry, w - 8, ROW - 2, 4, Theme.alpha(Theme.L_CONTROL, hh));
            int eye = m.enabled ? Theme.L_TEXT : Theme.L_DIM2;
            Theme.roundRect(g, x + 10, ry + 5, 8, 8, 4, m.enabled ? Theme.GOOD : Theme.L_CONTROL_HI);
            Theme.text(g, Theme.fit(m.name, w - 38), x + 24, ry + 5, eye);
            hit(x + 4, ry, w - 8, ROW - 2, id);
            hit(x + 8, ry + 2, 14, 14, "vis:" + m.id);
        }
        g.disableScissor();
        if (max > 0) {
            int knob = Math.max(14, (int) (viewH * (viewH / (float) content)));
            int ky = top + (int) ((viewH - knob) * (listScroll / max));
            Theme.roundRect(g, x + w - 5, ky, 3, knob, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
    }

    private void drawInspector(GuiGraphics g) {
        if (selected == null) return;
        int w = 160, x = width - w - 8, y = 40, h = 188;
        panel(g, x, y, w, h);
        Theme.text(g, Theme.fit(selected.name, w - 20), x + 10, y + 8, Theme.L_TEXT);
        int cy = y + 26;

        SliderSetting scale = HudLayouts.find(selected, "Scale", SliderSetting.class);
        if (scale != null) {
            Theme.text(g, "Scale", x + 10, cy, Theme.L_DIM);
            Theme.text(g, scale.format(), x + w - 10 - Theme.width(scale.format()), cy, Theme.L_SOFT);
            int tx = x + 10, tw = w - 20, ty = cy + 14;
            Theme.roundRect(g, tx, ty, tw, 4, 2, Theme.L_CONTROL_HI);
            int fill = (int) (tw * scale.normalized());
            Theme.roundRect(g, tx, ty, Math.max(4, fill), 4, 2, Theme.ACCENT);
            Theme.roundRect(g, tx + fill - 4, ty - 3, 8, 10, 4, Theme.L_WHITE);
            hit(tx - 4, ty - 6, tw + 8, 16, "scale");
            cy += 30;
        }

        ColorSetting text = HudLayouts.find(selected, "Text color", ColorSetting.class);
        if (text != null) {
            Theme.text(g, "Text colour", x + 10, cy, Theme.L_DIM);
            int sx = x + 10;
            for (int i = 0; i < SWATCHES.length; i++) {
                int col = (i % 5) * 28 + sx, row = (i / 5) * 18;
                boolean on = text.mode == 0 && text.rgb == (SWATCHES[i] & 0xFFFFFF);
                String id = "tc:" + i;
                if (on) Theme.roundRect(g, col - 2, cy + 11 + row, 20, 16, 5, Theme.L_WHITE);
                Theme.roundRect(g, col, cy + 13 + row, 16, 12, 4, Theme.lerp(SWATCHES[i], 0xFFFFFFFF, 0.2f * hv(id)));
                hit(col - 2, cy + 11 + row, 20, 16, id);
            }
            cy += 52;
        }

        BoolSetting bg = HudLayouts.find(selected, "Background", BoolSetting.class);
        if (bg != null) cy = toggleRow(g, x, cy, w, "Background", bg.value, "bg");
        BoolSetting sh = HudLayouts.find(selected, "Text shadow", BoolSetting.class);
        if (sh != null) cy = toggleRow(g, x, cy, w, "Text shadow", sh.value, "shadow");

        int by = y + h - 26;
        Theme.button(g, x + 10, by, w - 20, 18, "Reset position", false, hv("resetpos") > 0.5f);
        hit(x + 10, by, w - 20, 18, "resetpos");
        hv("resetpos");
    }

    private int toggleRow(GuiGraphics g, int x, int y, int w, String label, boolean on, String id) {
        Theme.text(g, label, x + 10, y + 3, Theme.L_DIM);
        Theme.toggle(g, x + w - 10 - 24, y, on ? 1f : 0f);
        hit(x + 8, y - 2, w - 16, 16, id);
        return y + 20;
    }

    private void drawBottomBar(GuiGraphics g) {
        int bh = 46, bx = PANEL_W + 20, bw = width - bx - 176, by = height - bh - 8;
        if (bw < 260) {
            bx = 8;
            bw = width - 16;
        }
        panel(g, bx, by, bw, bh);
        int x = bx + 10, y = by + 8;
        if (naming) {
            Theme.text(g, "Layout name", x, y, Theme.L_DIM2);
            Theme.roundRect(g, x - 1, y + 10, 162, 18, 5, Theme.alpha(Theme.ACCENT, 0.7f));
            Theme.roundRect(g, x, y + 11, 160, 16, 4, 0xFF0C0F17);
            nameBox.setX(x + 6);
            nameBox.setY(y + 15);
            nameBox.setWidth(148);
            nameBox.setHeight(8);
            nameBox.render(g, 0, 0, 0f);
            action(g, x + 170, y + 10, 52, "Save", true, "naming:ok");
            action(g, x + 228, y + 10, 52, "Cancel", false, "naming:cancel");
            return;
        }
        Theme.text(g, "LAYOUTS", x, y, Theme.L_DIM2);
        int cx = x;
        for (String name : HudLayouts.names()) {
            int w = Theme.width(name) + 26;
            if (cx + w > bx + bw - 250) break;
            String id = "layout:" + name;
            float h = hv(id);
            Theme.roundRect(g, cx, y + 11, w, 18, 9, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, h));
            Theme.text(g, name, cx + 9, y + 16, Theme.L_TEXT);
            Icons.draw(g, Icons.CLOSE, cx + w - 14, y + 16, 1, Theme.lerp(Theme.L_DIM2, Theme.BAD, hv("del:" + name)));
            hit(cx, y + 11, w - 16, 18, id);
            hit(cx + w - 16, y + 11, 16, 18, "del:" + name);
            cx += w + 6;
        }
        if (HudLayouts.names().isEmpty()) Theme.text(g, "None yet. Arrange your HUD, then press Save layout.", x, y + 16, Theme.L_DIM);

        int rx = bx + bw - 10;
        rx -= 54;
        action(g, rx, y + 10, 54, "Done", true, "done");
        rx -= 60;
        action(g, rx, y + 10, 54, "Reset", false, "resetall");
        rx -= 64;
        action(g, rx, y + 10, 58, snapping ? "Snap: on" : "Snap: off", snapping, "snap");
        rx -= 84;
        action(g, rx, y + 10, 78, "Save layout", false, "savelayout");
        if (!message.isEmpty()) Theme.text(g, message, bx + 10, by - 12, Theme.L_SOFT);
    }

    private void action(GuiGraphics g, int x, int y, int w, String label, boolean primary, String id) {
        float h = hv(id);
        int bg = primary ? Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.2f * h) : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, h);
        Theme.roundRect(g, x, y, w, 20, 5, bg);
        Theme.centered(g, label, x + w / 2, y + 6, Theme.L_TEXT, false);
        hit(x, y, w, 20, id);
    }

    // ------------------------------------------------------------------ input

    private String hitId(double mx, double my) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(mx, my, h.x, h.y, h.w, h.h)) return h.id;
        }
        return null;
    }

    private void setScaleFrom(double mx, int trackX, int trackW) {
        SliderSetting scale = selected == null ? null : HudLayouts.find(selected, "Scale", SliderSetting.class);
        if (scale != null) scale.setNormalized((mx - trackX) / trackW);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        String id = hitId(mx, my);
        if (naming) nameBox.setFocused(true);
        if (id != null) {
            if (handle(id, mx)) return true;
        }
        List<Module> list = shown();
        for (int i = list.size() - 1; i >= 0; i--) {
            Module m = list.get(i);
            int[] r = HudRenderer.rect(m, minecraft);
            if (Theme.in(mx, my, r[0], r[1], r[2], r[3])) {
                selected = m;
                dragging = m;
                offX = mx - r[0];
                offY = my - r[1];
                return true;
            }
        }
        selected = null;
        return super.mouseClicked(e, dbl);
    }

    private boolean handle(String id, double mx) {
        switch (id) {
            case "done" -> onClose();
            case "resetall" -> {
                ModuleManager.resetLayout();
                toast("Positions reset.");
            }
            case "snap" -> snapping = !snapping;
            case "savelayout" -> {
                naming = true;
                nameBox.setValue("");
                nameBox.setFocused(true);
                setFocused(nameBox);
            }
            case "naming:ok" -> commitName();
            case "naming:cancel" -> naming = false;
            case "resetpos" -> {
                if (selected != null) {
                    selected.resetPosition();
                    selected.syncPosition();
                    PulseConfig.save();
                }
            }
            case "scale" -> {
                sliderDrag = true;
                setScaleFrom(mx, width - 160 - 8 + 10, 140);
            }
            case "bg" -> flip("Background");
            case "shadow" -> flip("Text shadow");
            default -> {
                if (id.startsWith("el:")) selected = byId(id.substring(3));
                else if (id.startsWith("vis:")) {
                    Module m = byId(id.substring(4));
                    if (m != null) {
                        m.setEnabled(!m.enabled);
                        selected = m;
                        PulseConfig.save();
                    }
                } else if (id.startsWith("tc:") && selected != null) {
                    ColorSetting c = HudLayouts.find(selected, "Text color", ColorSetting.class);
                    if (c != null) {
                        c.mode = 0;
                        c.setRgb(SWATCHES[Integer.parseInt(id.substring(3))]);
                        PulseConfig.save();
                    }
                } else if (id.startsWith("layout:")) {
                    String n = id.substring(7);
                    toast(HudLayouts.load(n) ? "Loaded layout \"" + n + "\"." : "That layout no longer exists.");
                } else if (id.startsWith("del:")) {
                    HudLayouts.delete(id.substring(4));
                    toast("Layout deleted.");
                } else return false;
            }
        }
        return true;
    }

    private void flip(String settingName) {
        if (selected == null) return;
        BoolSetting b = HudLayouts.find(selected, settingName, BoolSetting.class);
        if (b == null) return;
        b.value = !b.value;
        PulseConfig.save();
    }

    private Module byId(String id) {
        for (Module m : hudModules()) if (m.id.equals(id)) return m;
        return null;
    }

    private void commitName() {
        String n = nameBox.getValue().trim();
        if (!HudLayouts.validName(n)) {
            toast("Give the layout a name (up to 24 characters).");
            return;
        }
        HudLayouts.save(n);
        naming = false;
        toast("Saved layout \"" + n + "\".");
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (sliderDrag) {
            setScaleFrom(e.x(), width - 160 - 8 + 10, 140);
            return true;
        }
        if (dragging == null) return super.mouseDragged(e, dx, dy);
        int[] r = HudRenderer.rect(dragging, minecraft);
        int freeW = Math.max(1, width - r[2]), freeH = Math.max(1, height - r[3]);
        double x = e.x() - offX, y = e.y() - offY;
        guideX = guideY = -1;
        if (snapping) {
            double[] sx = snapAxis(x, r[2], width, true, dragging);
            double[] sy = snapAxis(y, r[3], height, false, dragging);
            x = sx[0];
            y = sy[0];
            guideX = sx[1] < 0 ? -1 : (int) sx[1];
            guideY = sy[1] < 0 ? -1 : (int) sy[1];
        }
        dragging.fx = (float) Math.max(0, Math.min(1, x / freeW));
        dragging.fy = (float) Math.max(0, Math.min(1, y / freeH));
        dragging.syncPosition();
        return true;
    }

    /** Returns {snapped position, guide line coordinate or -1} for one axis. */
    private double[] snapAxis(double pos, int size, int screen, boolean horizontal, Module self) {
        double best = pos, guide = -1, bestDist = SNAP + 1;
        List<double[]> targets = new ArrayList<>(); // {position the element's left/top should take, guide line}
        targets.add(new double[]{0, 0});
        targets.add(new double[]{(screen - size) / 2.0, screen / 2.0});
        targets.add(new double[]{screen - size, screen - 1});
        for (Module m : shown()) {
            if (m == self) continue;
            int[] o = HudRenderer.rect(m, minecraft);
            int start = horizontal ? o[0] : o[1], len = horizontal ? o[2] : o[3];
            targets.add(new double[]{start, start});
            targets.add(new double[]{start + len - size, start + len});
            targets.add(new double[]{start + (len - size) / 2.0, start + len / 2.0});
        }
        for (double[] t : targets) {
            double d = Math.abs(pos - t[0]);
            if (d < bestDist) {
                bestDist = d;
                best = t[0];
                guide = t[1];
            }
        }
        return bestDist <= SNAP ? new double[]{best, guide} : new double[]{pos, -1};
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        boolean was = dragging != null || sliderDrag;
        dragging = null;
        sliderDrag = false;
        guideX = guideY = -1;
        if (was) {
            PulseConfig.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Theme.in(mx, my, 8, listTop(), PANEL_W, listHeight())) {
            listTarget = Math.max(0, listTarget - (float) sy * 24f);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (naming) {
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                commitName();
                return true;
            }
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                naming = false;
                return true;
            }
            return super.keyPressed(e);
        }
        var cm = ModuleManager.client();
        if (k == (cm == null ? GLFW.GLFW_KEY_RIGHT_SHIFT : cm.menuKey()) || k == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (selected != null && (k == GLFW.GLFW_KEY_LEFT || k == GLFW.GLFW_KEY_RIGHT || k == GLFW.GLFW_KEY_UP || k == GLFW.GLFW_KEY_DOWN)) {
            int step = (e.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
            int dx = k == GLFW.GLFW_KEY_LEFT ? -step : k == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = k == GLFW.GLFW_KEY_UP ? -step : k == GLFW.GLFW_KEY_DOWN ? step : 0;
            int[] r = HudRenderer.rect(selected, minecraft);
            int freeW = Math.max(1, width - r[2]), freeH = Math.max(1, height - r[3]);
            selected.fx = (float) Math.max(0, Math.min(1, (r[0] + dx) / (double) freeW));
            selected.fy = (float) Math.max(0, Math.min(1, (r[1] + dy) / (double) freeH));
            selected.syncPosition();
            PulseConfig.save();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        PulseConfig.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
