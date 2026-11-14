package dev.pulse.client.ui;

import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.WaypointsModule;
import dev.pulse.client.module.impl.WaypointsModule.Waypoint;
import net.minecraft.ChatFormatting;
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
import java.util.Locale;
import java.util.Map;

/**
 * Waypoint manager: every waypoint in a filterable list, and an editor for the selected one (name, coordinates,
 * dimension, colour, visibility). Colour, dimension and visibility apply instantly; name and coordinates apply with
 * Save (or Enter). Works from the main menu too, so waypoints can be edited without joining a world.
 */
public class WaypointScreen extends Screen {
    private static final String[] FILTERS = {"All", "Overworld", "Nether", "End", "Here"};
    private static final String[] DIMS = {"overworld", "the_nether", "the_end"};
    private static final String[] DIM_LABELS = {"Overworld", "Nether", "End"};
    private static final int ROW = 28;

    private record Hit(int x, int y, int w, int h, String id) {}

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private String hot = "";

    private int filter;
    private Waypoint selected;
    private float scroll, targetScroll;
    private float dt = 0.016f;
    private long lastNs = System.nanoTime(), openedAt = System.currentTimeMillis();
    private String message = "";
    private int messageColor = Theme.L_DIM;
    private long deleteArmedAt;

    private EditBox nameBox, xBox, yBox, zBox;
    private int px, py, pw, ph, listX, listY, listW, listH, edX, edY, edW;

    public WaypointScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ setup

    private EditBox box(String hint) {
        EditBox b = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal(hint));
        b.setBordered(false);
        b.setMaxLength(48);
        b.setTextColor(Theme.L_TEXT);
        addWidget(b);
        return b;
    }

    @Override
    protected void init() {
        String keepName = nameBox == null ? null : nameBox.getValue();
        nameBox = box("Name");
        xBox = box("X");
        yBox = box("Y");
        zBox = box("Z");
        if (selected != null) {
            loadFields(selected);
            if (keepName != null) nameBox.setValue(keepName);
        }
    }

    private WaypointsModule module() {
        return ModuleManager.waypoints();
    }

    private List<Waypoint> filtered() {
        List<Waypoint> out = new ArrayList<>();
        String here = module() == null || minecraft.level == null ? "" : WaypointsModule.currentDim(minecraft);
        for (Waypoint w : module().all()) {
            String label = WaypointsModule.dimLabel(w.dim);
            boolean ok = switch (filter) {
                case 1 -> label.equals("Overworld");
                case 2 -> label.equals("Nether");
                case 3 -> label.equals("End");
                case 4 -> here.isEmpty() || here.equals(w.dim);
                default -> true;
            };
            if (ok) out.add(w);
        }
        return out;
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? Long.toString((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private void loadFields(Waypoint w) {
        nameBox.setValue(w.name);
        xBox.setValue(fmt(w.x));
        yBox.setValue(fmt(w.y));
        zBox.setValue(fmt(w.z));
    }

    private void select(Waypoint w) {
        selected = w;
        deleteArmedAt = 0;
        setMessage("", Theme.L_DIM);
        if (w != null) loadFields(w);
    }

    private void setMessage(String s, int color) {
        message = s;
        messageColor = color;
    }

    // ------------------------------------------------------------------ actions

    private void create() {
        WaypointsModule m = module();
        Waypoint w = m.addHere(minecraft);
        if (w == null) { // no world: make one at a neutral spot the player can edit
            int n = 1;
            while (m.find("Waypoint " + n) != null) n++;
            m.addAt("Waypoint " + n, 0, 64, 0, rawDim("overworld"));
            w = m.find("Waypoint " + n);
        }
        select(w);
        filter = 0;
    }

    private void save() {
        if (selected == null) return;
        double x, y, z;
        try {
            x = Double.parseDouble(xBox.getValue().trim());
            y = Double.parseDouble(yBox.getValue().trim());
            z = Double.parseDouble(zBox.getValue().trim());
        } catch (NumberFormatException e) {
            setMessage("Coordinates must be numbers.", Theme.BAD);
            return;
        }
        if (!module().rename(selected, nameBox.getValue())) {
            setMessage("Pick a name that is not empty or already used.", Theme.BAD);
            return;
        }
        selected.x = x;
        selected.y = y;
        selected.z = z;
        module().changed();
        setMessage("Saved.", Theme.GOOD);
    }

    private void toMyPosition() {
        if (selected == null || minecraft.player == null) return;
        xBox.setValue(fmt(minecraft.player.getX()));
        yBox.setValue(fmt(minecraft.player.getY()));
        zBox.setValue(fmt(minecraft.player.getZ()));
        selected.dim = WaypointsModule.currentDim(minecraft);
        save();
    }

    private void delete() {
        if (selected == null) return;
        long now = System.currentTimeMillis();
        if (now - deleteArmedAt > 2500) { // first click arms, second confirms
            deleteArmedAt = now;
            return;
        }
        module().remove(selected.name);
        select(null);
    }

    /** The raw dimension key for a vanilla dimension, written in the same format the game reports. */
    private String rawDim(String type) {
        String template = minecraft.level != null ? WaypointsModule.currentDim(minecraft)
                : module().all().isEmpty() ? "" : module().all().get(0).dim;
        int i = template == null ? -1 : template.lastIndexOf(" / ");
        String prefix = i >= 0 ? template.substring(0, i + 3) : "ResourceKey[minecraft:dimension / ";
        return prefix + "minecraft:" + type + "]";
    }

    // ------------------------------------------------------------------ helpers

    private float hv(String id) {
        float cur = hover.getOrDefault(id, 0f);
        cur += ((id.equals(hot) ? 1f : 0f) - cur) * Math.min(1f, dt * 16f);
        hover.put(id, cur);
        return cur;
    }

    private void hit(int x, int y, int w, int h, String id) {
        hits.add(new Hit(x, y, w, h, id));
    }

    private void chip(GuiGraphics g, int x, int y, String label, boolean on, String id) {
        int w = Theme.width(label) + 16;
        Theme.roundRect(g, x, y, w, 16, 8, on ? Theme.L_WHITE : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv(id)));
        Theme.centered(g, label, x + w / 2, y + 4, on ? Theme.L_INK : Theme.L_TEXT, false);
        hit(x, y, w, 16, id);
        chipEnd = x + w + 6;
    }

    private int chipEnd;

    private void field(GuiGraphics g, EditBox b, int x, int y, int w, String label) {
        Theme.text(g, label, x, y - 11, Theme.L_DIM2);
        boolean f = b.isFocused();
        Theme.roundRect(g, x - 1, y - 1, w + 2, 18, 5, f ? Theme.alpha(Theme.ACCENT, 0.7f) : Theme.L_LINE);
        Theme.roundRect(g, x, y, w, 16, 4, 0xFF0C0F17);
        b.setX(x + 6);
        b.setY(y + 4);
        b.setWidth(w - 12);
        b.setHeight(8);
        b.render(g, 0, 0, 0f);
        hit(x, y, w, 16, "box:" + label);
    }

    private double distance(Waypoint w) {
        if (minecraft.player == null || minecraft.level == null || !WaypointsModule.currentDim(minecraft).equals(w.dim)) return -1;
        return Math.sqrt(Math.pow(w.x - minecraft.player.getX(), 2) + Math.pow(w.y - minecraft.player.getY(), 2)
                + Math.pow(w.z - minecraft.player.getZ(), 2));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        long now = System.currentTimeMillis();
        float intro = Theme.ease((now - openedAt) / 250f);
        scroll += (targetScroll - scroll) * Math.min(1f, dt * 16f);

        Backdrop.draw(g, width, height, (now - openedAt) / 1000.0, true);
        g.fill(0, 0, width, height, Theme.alpha(0xFF0A1E5C, 0.30f * intro));
        pw = Math.min(width - 24, 760);
        ph = Math.min(height - 24, 420);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1 - intro) * 10);
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 7, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 6, Theme.L_BG);
        hits.clear();
        hot = "";

        Component title = Component.literal("Waypoints").withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(px + 14, py + 10);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), title, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        List<Waypoint> all = module().all();
        Theme.text(g, all.size() + " saved", px + 14 + 74, py + 12, Theme.L_DIM);
        int addW = 74;
        Theme.roundRect(g, px + pw - addW - 12, py + 7, addW, 18, 5, Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.2f * hv("new")));
        Theme.centered(g, "+ New", px + pw - addW / 2 - 12, py + 12, 0xFFFFFFFF, false);
        hit(px + pw - addW - 12, py + 7, addW, 18, "new");
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        chipEnd = px + 12;
        for (int i = 0; i < FILTERS.length; i++) chip(g, chipEnd, py + 38, FILTERS[i], i == filter, "filter:" + i);

        listX = px + 10;
        listY = py + 62;
        listW = (int) (pw * 0.5);
        listH = ph - 62 - 38;
        drawList(g, mx, my);
        edX = listX + listW + 10;
        edY = listY;
        edW = px + pw - 10 - edX;
        drawEditor(g);
        drawFooter(g);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(mx, my, h.x, h.y, h.w, h.h)) {
                hot = h.id;
                break;
            }
        }
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        Theme.roundRect(g, listX - 1, listY - 1, listW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, listX, listY, listW, listH, 5, 0xFF0C0F17);
        List<Waypoint> items = filtered();
        int content = items.size() * ROW + 8;
        float max = Math.max(0, content - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll));
        if (items.isEmpty()) {
            Theme.centered(g, module().all().isEmpty() ? "No waypoints yet. Press + New." : "Nothing in this dimension.",
                    listX + listW / 2, listY + listH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < items.size(); i++) {
            Waypoint w = items.get(i);
            int y = listY + 4 + i * ROW - Math.round(scroll);
            if (y + ROW < listY || y > listY + listH) continue;
            String id = "row:" + i;
            float h = hv(id);
            boolean sel = w == selected;
            Theme.roundRect(g, listX + 4, y, listW - 8, ROW - 3, 5, sel ? Theme.L_CONTROL_HI : Theme.lerp(Theme.L_CARD, Theme.L_CARD_HI, h));
            float a = w.hidden ? 0.4f : 1f;
            Theme.roundRect(g, listX + 12, y + 8, 10, 10, 5, Theme.alpha(0xFF000000 | w.rgb(), a));
            Theme.text(g, Theme.fit(w.name, listW / 2 - 40), listX + 30, y + 4, Theme.alpha(Theme.L_TEXT, a));
            Theme.text(g, fmt(w.x) + " " + fmt(w.y) + " " + fmt(w.z), listX + 30, y + 14, Theme.alpha(Theme.L_DIM, a));
            String tag = WaypointsModule.dimLabel(w.dim);
            double d = distance(w);
            String right = d >= 0 ? Math.round(d) + "m" : tag;
            Theme.text(g, right, listX + listW - 14 - Theme.width(right), y + 4, Theme.alpha(Theme.L_SOFT, a));
            if (d >= 0) Theme.text(g, tag, listX + listW - 14 - Theme.width(tag), y + 14, Theme.alpha(Theme.L_DIM2, a));
            if (Theme.in(mx, my, listX, listY, listW, listH)) hit(listX + 4, y, listW - 8, ROW - 3, id);
        }
        g.disableScissor();
        if (max > 0) {
            int knob = Math.max(16, (int) (listH * (listH / (float) content)));
            int ky = listY + (int) ((listH - knob) * (scroll / max));
            Theme.roundRect(g, listX + listW - 5, ky, 3, knob, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
    }

    private void drawEditor(GuiGraphics g) {
        Theme.roundRect(g, edX - 1, edY - 1, edW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, edX, edY, edW, listH, 5, Theme.L_CARD);
        if (selected == null) {
            Theme.centered(g, "Select a waypoint to edit it.", edX + edW / 2, edY + listH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        int x = edX + 12, w = edW - 24, y = edY + 22;
        field(g, nameBox, x, y, w, "Name");
        y += 38;
        int cw = (w - 12) / 3;
        field(g, xBox, x, y, cw, "X");
        field(g, yBox, x + cw + 6, y, cw, "Y");
        field(g, zBox, x + 2 * (cw + 6), y, cw, "Z");
        y += 30;

        Theme.text(g, "Dimension", x, y, Theme.L_DIM2);
        String cur = WaypointsModule.dimLabel(selected.dim);
        chipEnd = x;
        for (int i = 0; i < DIMS.length; i++) chip(g, chipEnd, y + 11, DIM_LABELS[i], cur.equals(DIM_LABELS[i]), "dim:" + i);
        y += 36;

        Theme.text(g, "Colour", x, y, Theme.L_DIM2);
        int cx = x;
        for (int i = 0; i < WaypointsModule.PALETTE.length; i++) {
            int c = 0xFF000000 | WaypointsModule.PALETTE[i];
            boolean on = selected.color != 0 && (selected.color & 0xFFFFFF) == WaypointsModule.PALETTE[i];
            String id = "color:" + i;
            if (on) Theme.roundRect(g, cx - 2, y + 9, 18, 18, 9, Theme.L_WHITE);
            Theme.roundRect(g, cx, y + 11, 14, 14, 7, Theme.lerp(c, 0xFFFFFFFF, 0.25f * hv(id)));
            hit(cx - 1, y + 10, 16, 16, id);
            cx += 20;
            if (cx + 16 > x + w) break;
        }
        y += 34;

        chip(g, x, y, selected.hidden ? "Hidden" : "Visible", !selected.hidden, "visible");
        chip(g, chipEnd, y, selected.color == 0 ? "Auto colour" : "Reset colour", selected.color == 0, "autocolor");
        y = edY + listH - 30;
        int bw = (w - 12) / 3;
        Theme.button(g, x, y, bw, 20, "Save", true, hv("save") > 0.5f);
        hit(x, y, bw, 20, "save");
        hv("save");
        Theme.button(g, x + bw + 6, y, bw, 20, "My position", false, hv("here") > 0.5f);
        hit(x + bw + 6, y, bw, 20, "here");
        hv("here");
        boolean armed = System.currentTimeMillis() - deleteArmedAt <= 2500;
        int dc = armed ? Theme.BAD : Theme.L_CONTROL;
        Theme.roundRect(g, x + 2 * (bw + 6), y, bw, 20, 5, Theme.lerp(dc, 0xFFFFFFFF, 0.1f * hv("delete")));
        Theme.centered(g, armed ? "Sure?" : "Delete", x + 2 * (bw + 6) + bw / 2, y + 6, Theme.L_TEXT, false);
        hit(x + 2 * (bw + 6), y, bw, 20, "delete");
        if (!message.isEmpty()) Theme.text(g, Theme.fit(message, w), x, y - 12, messageColor);
    }

    private void drawFooter(GuiGraphics g) {
        int fy = py + ph - 28;
        g.fill(px + 1, fy - 1, px + pw - 1, fy, Theme.L_LINE);
        Theme.text(g, "Beams and markers appear in the world while the Waypoints module is on.", px + 14, fy + 10, Theme.L_DIM2);
        int doneW = 64, doneX = px + pw - doneW - 10;
        Theme.roundRect(g, doneX, fy + 5, doneW, 18, 5, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv("done")));
        Theme.centered(g, "Done", doneX + doneW / 2, fy + 10, Theme.L_TEXT, false);
        hit(doneX, fy + 5, doneW, 18, "done");
    }

    // ------------------------------------------------------------------ input

    private EditBox[] boxes() {
        return new EditBox[]{nameBox, xBox, yBox, zBox};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        String id = null;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(e.x(), e.y(), h.x, h.y, h.w, h.h)) {
                id = h.id;
                break;
            }
        }
        EditBox target = null;
        if (id != null && id.startsWith("box:")) {
            target = switch (id) {
                case "box:Name" -> nameBox;
                case "box:X" -> xBox;
                case "box:Y" -> yBox;
                default -> zBox;
            };
        }
        for (EditBox b : boxes()) b.setFocused(b == target);
        if (target != null) {
            setFocused(target);
            target.mouseClicked(e, dbl);
            return true;
        }
        if (id == null) return super.mouseClicked(e, dbl);

        switch (id) {
            case "done" -> onClose();
            case "new" -> create();
            case "save" -> save();
            case "here" -> toMyPosition();
            case "delete" -> delete();
            case "visible" -> {
                selected.hidden = !selected.hidden;
                module().changed();
            }
            case "autocolor" -> {
                selected.color = 0;
                module().changed();
            }
            default -> {
                if (id.startsWith("filter:")) {
                    filter = Integer.parseInt(id.substring(7));
                    targetScroll = 0;
                } else if (id.startsWith("row:")) {
                    List<Waypoint> items = filtered();
                    int i = Integer.parseInt(id.substring(4));
                    if (i >= 0 && i < items.size()) select(items.get(i));
                } else if (id.startsWith("dim:") && selected != null) {
                    selected.dim = rawDim(DIMS[Integer.parseInt(id.substring(4))]);
                    module().changed();
                } else if (id.startsWith("color:") && selected != null) {
                    selected.color = 0xFF000000 | WaypointsModule.PALETTE[Integer.parseInt(id.substring(6))];
                    module().changed();
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Theme.in(mx, my, listX, listY, listW, listH)) {
            targetScroll = Math.max(0, targetScroll - (float) sy * 30f);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        boolean typing = false;
        for (EditBox b : boxes()) typing |= b.isFocused();
        if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) {
                for (EditBox b : boxes()) b.setFocused(false);
                setFocused(null);
            } else {
                onClose();
            }
            return true;
        }
        if ((e.key() == GLFW.GLFW_KEY_ENTER || e.key() == GLFW.GLFW_KEY_KP_ENTER) && typing) {
            save();
            return true;
        }
        if (e.key() == GLFW.GLFW_KEY_DELETE && !typing && selected != null) {
            delete();
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
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
