package dev.pulse.client.ui;

import dev.pulse.client.module.Module;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * A small multi-row HUD card ("label ..... value  delta", optional rating bar under a row).
 * Built once per tick by a module, drawn every frame. Colors come from the module so the card follows the
 * user's text / label colors, scale, background and position settings like every other HUD element.
 */
public final class StatCard {
    public static final int ROW = 11;
    public static final int PAD = 6;

    /** One row. delta is "" when absent, bar is below 0 when absent, gap adds a little space before the row. */
    public record Row(String label, int labelColor, String value, int valueColor,
                      String delta, int deltaColor, float bar, boolean gap) {}

    public final List<Row> rows;
    public final int w, h;

    public StatCard(List<Row> rows, int minWidth) {
        this.rows = rows;
        int width = minWidth;
        int height = PAD * 2;
        for (Row r : rows) {
            int rw = 8 + Theme.width(r.label()) + 14 + Theme.width(r.value()) + 8;
            if (!r.delta().isEmpty()) rw += 6 + Theme.width(r.delta());
            width = Math.max(width, rw);
            if (r.gap()) height += 3;
            height += ROW;
            if (r.bar() >= 0f) height += 4;
        }
        this.w = width;
        this.h = height;
    }

    public static Row row(String label, int labelColor, String value, int valueColor) {
        return new Row(label, labelColor, value, valueColor, "", 0, -1f, false);
    }

    /** Draws the card at (0,0); the pose is already translated and scaled by the HUD renderer. */
    public void draw(Module m, GuiGraphics g) {
        int y = PAD;
        for (Row r : rows) {
            if (r.gap()) y += 3;
            m.text(g, r.label(), 8, y, r.labelColor());
            int right = w - 8;
            if (!r.delta().isEmpty()) {
                m.text(g, r.delta(), right - Theme.width(r.delta()), y, r.deltaColor());
                right -= Theme.width(r.delta()) + 6;
            }
            m.text(g, r.value(), right - Theme.width(r.value()), y, r.valueColor());
            y += ROW;
            if (r.bar() >= 0f) {
                int bw = w - 16;
                float f = Math.max(0f, Math.min(1f, r.bar()));
                Theme.roundRect(g, 8, y, bw, 2, 1, Theme.alpha(0xFFFFFFFF, 0.14f));
                int fill = Math.round(bw * f);
                if (fill > 0) Theme.roundRect(g, 8, y, Math.max(2, fill), 2, 1, Theme.lerp(Theme.BAD, Theme.GOOD, f));
                y += 4;
            }
        }
    }
}
