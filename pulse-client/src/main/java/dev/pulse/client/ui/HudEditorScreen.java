package dev.pulse.client.ui;

import dev.pulse.client.PulseConfig;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Drag every HUD element where you want it. */
public class HudEditorScreen extends Screen {
    private final Screen parent;
    private Module dragging;
    private double offX, offY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        HudRenderer.setEditing(true);
    }

    @Override
    public void removed() {
        HudRenderer.setEditing(false);
    }

    private List<Module> visible() {
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.all()) if (m.hud && m.enabled) out.add(m);
        return out;
    }

    private int doneX() { return width / 2 - 66; }
    private int btnY() { return height - 34; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0x88050710);
        // faint grid
        for (int x = 0; x < width; x += 40) g.fill(x, 0, x + 1, height, 0x0CFFFFFF);
        for (int y = 0; y < height; y += 40) g.fill(0, y, width, y + 1, 0x0CFFFFFF);

        Theme.centered(g, "Edit HUD", width / 2, 14, Theme.TEXT, true);
        Theme.centered(g, "Drag elements to move them. Exact positions are in each module's settings.", width / 2, 28, Theme.DIM, false);

        var mc = minecraft;
        for (Module m : visible()) {
            HudRenderer.drawModule(g, m, mc);
            int[] r = HudRenderer.rect(m, mc);
            if (r[2] <= 0) continue;
            boolean hot = m == dragging || (dragging == null && Theme.in(mx, my, r[0], r[1], r[2], r[3]));
            int c = hot ? Theme.ACCENT : 0x55FFFFFF;
            g.fill(r[0] - 2, r[1] - 2, r[0] + r[2] + 2, r[1] - 1, c);
            g.fill(r[0] - 2, r[1] + r[3] + 1, r[0] + r[2] + 2, r[1] + r[3] + 2, c);
            g.fill(r[0] - 2, r[1] - 1, r[0] - 1, r[1] + r[3] + 1, c);
            g.fill(r[0] + r[2] + 1, r[1] - 1, r[0] + r[2] + 2, r[1] + r[3] + 1, c);
            if (hot) Theme.shadow(g, m.name, r[0], Math.max(2, r[1] - 12), Theme.TEXT);
        }

        Theme.button(g, doneX(), btnY(), 60, 22, "Done", true, Theme.in(mx, my, doneX(), btnY(), 60, 22));
        Theme.button(g, doneX() + 72, btnY(), 60, 22, "Reset", false, Theme.in(mx, my, doneX() + 72, btnY(), 60, 22));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        if (Theme.in(mx, my, doneX(), btnY(), 60, 22)) {
            onClose();
            return true;
        }
        if (Theme.in(mx, my, doneX() + 72, btnY(), 60, 22)) {
            ModuleManager.resetLayout();
            return true;
        }
        List<Module> list = visible();
        for (int i = list.size() - 1; i >= 0; i--) {
            Module m = list.get(i);
            int[] r = HudRenderer.rect(m, minecraft);
            if (Theme.in(mx, my, r[0], r[1], r[2], r[3])) {
                dragging = m;
                offX = mx - r[0];
                offY = my - r[1];
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(e, dx, dy);
        int[] r = HudRenderer.rect(dragging, minecraft);
        int freeW = Math.max(1, width - r[2]), freeH = Math.max(1, height - r[3]);
        dragging.fx = (float) Math.max(0, Math.min(1, (e.x() - offX) / freeW));
        dragging.fy = (float) Math.max(0, Math.min(1, (e.y() - offY) / freeH));
        dragging.syncPosition();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging != null) {
            dragging = null;
            PulseConfig.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        var cm = ModuleManager.client();
        if (e.key() == (cm == null ? GLFW.GLFW_KEY_RIGHT_SHIFT : cm.menuKey()) || e.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
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
