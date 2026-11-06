package dev.pulse.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * "Drop this protected item?" prompt. Esc or "Keep item" cancels; "Drop anyway" (or Enter) runs the drop.
 * The drop buttons only arm after a short delay so a double tap of Q can never confirm by accident.
 */
public class DropConfirmScreen extends Screen {
    private static final long ARM_MS = 350;
    private static final int PW = 270, PH = 118, BTN_H = 18;

    private final Screen parent;
    private final ItemStack stack;
    private final Runnable onConfirm;
    private final long openedAt = System.currentTimeMillis();

    public DropConfirmScreen(Screen parent, ItemStack stack, Runnable onConfirm) {
        super(Component.literal("Drop protected item?"));
        this.parent = parent;
        this.stack = stack;
        this.onConfirm = onConfirm;
    }

    private boolean armed() {
        return System.currentTimeMillis() - openedAt >= ARM_MS;
    }

    private int px() {
        return (width - PW) / 2;
    }

    private int py() {
        return (height - PH) / 2;
    }

    private int[] button(int i) {
        int bw = (PW - 28 - 8) / 2;
        return new int[]{px() + 14 + i * (bw + 8), py() + PH - BTN_H - 12, bw, BTN_H};
    }

    private void drawButton(GuiGraphics g, int[] r, String label, int mx, int my, boolean primary, boolean enabled) {
        boolean hot = enabled && Theme.in(mx, my, r[0], r[1], r[2], r[3]);
        int base = primary ? Theme.ACCENT : Theme.L_CARD;
        int hi = primary ? Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.25f) : 0xFF232940;
        Theme.roundRect(g, r[0], r[1], r[2], r[3], 5, hot ? hi : base);
        int ink = primary ? 0xFFFFFFFF : (enabled ? Theme.L_TEXT : Theme.L_DIM2);
        Theme.centered(g, label, r[0] + r[2] / 2, r[1] + 5, ink, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        float intro = Theme.ease((System.currentTimeMillis() - openedAt) / 200f);
        g.fill(0, 0, width, height, Theme.alpha(0xFF05070D, 0.60f * intro));

        int px = px(), py = py();
        Theme.roundRect(g, px - 1, py - 1, PW + 2, PH + 2, 9, Theme.L_LINE);
        Theme.roundRect(g, px, py, PW, PH, 8, Theme.L_BG);
        Theme.shadow(g, "Drop protected item?", px + 14, py + 11, Theme.L_TEXT);
        g.fill(px + 1, py + 30, px + PW - 1, py + 31, Theme.L_LINE);

        Theme.roundRect(g, px + 14, py + 40, 28, 28, 5, Theme.L_CARD);
        g.renderItem(stack, px + 20, py + 46);
        g.renderItemDecorations(Theme.font(), stack, px + 20, py + 46);
        Theme.text(g, Theme.fit(stack.getHoverName().getString(), PW - 70), px + 52, py + 47, Theme.L_TEXT);
        Theme.text(g, "You marked this item as protected.", px + 52, py + 59, Theme.L_DIM);

        boolean armed = armed();
        drawButton(g, button(0), "Drop anyway", mx, my, false, armed);
        drawButton(g, button(1), "Keep item", mx, my, true, true);
    }

    private void confirm() {
        minecraft.setScreen(parent);
        onConfirm.run();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        int[] drop = button(0), keep = button(1);
        if (armed() && Theme.in(e.x(), e.y(), drop[0], drop[1], drop[2], drop[3])) {
            confirm();
            return true;
        }
        if (Theme.in(e.x(), e.y(), keep[0], keep[1], keep[2], keep[3])) {
            onClose();
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && armed()) {
            confirm();
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
