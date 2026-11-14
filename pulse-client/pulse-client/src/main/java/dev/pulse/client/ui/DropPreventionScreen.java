package dev.pulse.client.ui;

import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.DropPreventionModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Manage the Protected and Allowed lists of Drop Prevention. Type an item id ("minecraft:elytra"), a short name
 * ("elytra") or a wildcard ("netherite*", "*_shulker_box"), then press Protect or Allow. Click an entry to select it.
 */
public class DropPreventionScreen extends Screen {
    private static final int ROW = 15, BTN_H = 18;

    private final Screen parent;
    private final long openedAt = System.currentTimeMillis();
    private EditBox input;
    private String text = "";
    /** 0 = protected list, 1 = allowed list, -1 = nothing selected. */
    private int selList = -1, selIdx = -1;
    private final float[] scroll = new float[2];
    private String status = "";

    private int px, py, pw, ph, listY, listH, colW, colX0, colX1, boxX, boxY, boxW;

    public DropPreventionScreen(Screen parent) {
        super(Component.literal("Drop Prevention"));
        this.parent = parent;
    }

    private DropPreventionModule mod() {
        return ModuleManager.byId("dropprevention") instanceof DropPreventionModule m ? m : null;
    }

    private List<String> list(int which) {
        DropPreventionModule m = mod();
        if (m == null) return List.of();
        return which == 0 ? m.protectedList() : m.allowedList();
    }

    @Override
    protected void init() {
        input = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Item id or wildcard"));
        input.setBordered(false);
        input.setMaxLength(64);
        input.setTextColor(Theme.L_TEXT);
        input.setResponder(s -> text = s);
        input.setValue(text);
        addWidget(input);
    }

    // bottom row: Protect, Allow, Held item, Remove ... Done
    private int[] btn(int i) {
        int[] w = {70, 60, 78, 64};
        int x = px + 10;
        for (int k = 0; k < i; k++) x += w[k] + 6;
        return new int[]{x, py + ph - BTN_H - 9, w[i], BTN_H};
    }

    private int[] done() {
        return new int[]{px + pw - 64 - 10, py + ph - BTN_H - 9, 64, BTN_H};
    }

    private void drawButton(GuiGraphics g, int[] r, String label, int mx, int my, boolean primary) {
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

        pw = Math.min(width - 24, 640);
        ph = Math.min(height - 24, 340);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 9, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 8, Theme.L_BG);
        Theme.shadow(g, "Drop Prevention", px + 14, py + 11, Theme.L_TEXT);
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        listY = py + 56;
        listH = ph - 56 - 78;
        colW = (pw - 30) / 2;
        colX0 = px + 10;
        colX1 = colX0 + colW + 10;
        drawColumn(g, mx, my, 0, "Protected (never dropped by accident)", colX0);
        drawColumn(g, mx, my, 1, "Allowed (always droppable)", colX1);

        boxX = px + 10;
        boxY = py + ph - 76;
        boxW = pw - 20;
        boolean focused = input.isFocused();
        Theme.roundRect(g, boxX - 1, boxY - 1, boxW + 2, 18, 5, focused ? Theme.ACCENT : Theme.L_LINE);
        Theme.roundRect(g, boxX, boxY, boxW, 16, 4, 0xFF0C0F17);
        input.setX(boxX + 6);
        input.setY(boxY + 4);
        input.setWidth(boxW - 12);
        input.setHeight(8);
        input.render(g, mx, my, 0f);
        if (text.isEmpty() && !focused) {
            Theme.text(g, "minecraft:elytra   or   elytra   or   netherite*   or   *_shulker_box", boxX + 6, boxY + 4, Theme.L_DIM2);
        }
        if (!status.isEmpty()) {
            Theme.text(g, Theme.fit(status, pw - 20), px + 14, py + 37, Theme.L_DIM);
        }

        drawButton(g, btn(0), "Protect", mx, my, true);
        drawButton(g, btn(1), "Allow", mx, my, false);
        drawButton(g, btn(2), "Held item", mx, my, false);
        drawButton(g, btn(3), "Remove", mx, my, false);
        drawButton(g, done(), "Done", mx, my, false);
    }

    private void drawColumn(GuiGraphics g, int mx, int my, int which, String title, int x) {
        List<String> items = list(which);
        Theme.text(g, Theme.fit(title, colW), x + 2, listY - 12, Theme.L_DIM);
        Theme.roundRect(g, x - 1, listY - 1, colW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, x, listY, colW, listH, 5, 0xFF0C0F17);
        g.enableScissor(x, listY, x + colW, listY + listH);
        if (items.isEmpty()) {
            Theme.centered(g, "Empty", x + colW / 2, listY + listH / 2 - 4, Theme.L_DIM2, false);
        }
        float max = Math.max(0, items.size() * ROW + 8 - listH);
        scroll[which] = Math.max(0, Math.min(max, scroll[which]));
        for (int i = 0; i < items.size(); i++) {
            int ry = listY + 4 + i * ROW - Math.round(scroll[which]);
            if (ry + ROW < listY || ry > listY + listH) continue;
            boolean sel = selList == which && selIdx == i;
            boolean hot = Theme.in(mx, my, x + 4, ry, colW - 8, ROW - 1) && Theme.in(mx, my, x, listY, colW, listH);
            if (sel) Theme.roundRect(g, x + 4, ry, colW - 8, ROW - 1, 4, Theme.L_WHITE);
            else if (hot) Theme.roundRect(g, x + 4, ry, colW - 8, ROW - 1, 4, Theme.alpha(0xFFFFFFFF, 0.08f));
            Theme.text(g, Theme.fit(items.get(i), colW - 24), x + 10, ry + 3, sel ? Theme.L_INK : Theme.L_TEXT);
        }
        g.disableScissor();
    }

    private void add(boolean toProtect) {
        DropPreventionModule m = mod();
        if (m == null) return;
        String entry = text.trim();
        if (entry.isEmpty()) {
            status = "Type an item id, name or wildcard first (or use Held item).";
            return;
        }
        if (m.addEntry(toProtect, entry)) {
            status = "Added '" + entry.toLowerCase() + "' to the " + (toProtect ? "protected" : "allowed") + " list.";
            input.setValue("");
        } else {
            status = "Could not add that (empty, has spaces, or already on the list).";
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        if (e.button() != 0) return super.mouseClicked(e, dbl);

        boolean onBox = Theme.in(mx, my, boxX, boxY, boxW, 16);
        input.setFocused(onBox);
        if (onBox) {
            setFocused(input);
            input.mouseClicked(e, dbl);
            return true;
        }
        if (getFocused() == input) setFocused(null);

        int[] d = done();
        if (Theme.in(mx, my, d[0], d[1], d[2], d[3])) {
            onClose();
            return true;
        }
        DropPreventionModule m = mod();
        if (m == null) return super.mouseClicked(e, dbl);
        int[] b0 = btn(0), b1 = btn(1), b2 = btn(2), b3 = btn(3);
        if (Theme.in(mx, my, b0[0], b0[1], b0[2], b0[3])) {
            add(true);
            return true;
        }
        if (Theme.in(mx, my, b1[0], b1[1], b1[2], b1[3])) {
            add(false);
            return true;
        }
        if (Theme.in(mx, my, b2[0], b2[1], b2[2], b2[3])) {
            String id = DropPreventionModule.heldId();
            if (id == null) {
                status = "Hold an item in your hand first.";
            } else {
                input.setValue(id);
                status = "Filled in your held item. Press Protect or Allow.";
            }
            return true;
        }
        if (Theme.in(mx, my, b3[0], b3[1], b3[2], b3[3])) {
            if (selList >= 0 && selIdx >= 0 && selIdx < list(selList).size()) {
                String gone = list(selList).get(selIdx);
                m.removeEntry(selList == 0, selIdx);
                status = "Removed '" + gone + "'.";
            } else {
                status = "Click an entry in one of the lists first.";
            }
            selList = selIdx = -1;
            return true;
        }
        for (int which = 0; which < 2; which++) {
            int x = which == 0 ? colX0 : colX1;
            if (Theme.in(mx, my, x, listY, colW, listH)) {
                int i = (int) ((my - listY - 4 + Math.round(scroll[which])) / ROW);
                if (i >= 0 && i < list(which).size()) {
                    selList = which;
                    selIdx = i;
                }
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int which = mx >= colX1 ? 1 : 0;
        scroll[which] -= (float) sy * 24f;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        boolean typing = input.isFocused();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) {
                input.setFocused(false);
                setFocused(null);
            } else {
                onClose();
            }
            return true;
        }
        if (typing && (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) {
            add(true);
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
