package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import dev.pulse.client.ui.Theme;

/** A compact "LABEL ........ value" HUD line. */
public abstract class LineModule extends Module {
    private final BoolSetting showLabel = add(new BoolSetting("Show label", true));
    private final ChoiceSetting layout = add(new ChoiceSetting("Layout", 0, "Split", "Inline"));

    protected LineModule(String id, String name, String desc, Category cat, float fx, float fy) {
        super(id, name, desc, cat, true, fx, fy, true);
    }

    protected abstract String label();

    protected abstract String value(Minecraft mc);

    /** Color of the value. Defaults to the user's text color. */
    protected int valueColor(Minecraft mc) {
        return textColor();
    }

    protected int minWidth() {
        return 66;
    }

    private boolean inline() {
        return layout.index == 1;
    }

    @Override
    public int width(Minecraft mc) {
        String v = value(mc);
        if (!showLabel.value) return Math.max(36, 12 + Theme.width(v));
        if (inline()) return Math.max(36, 12 + Theme.width(label() + ": " + v));
        return Math.max(minWidth(), 12 + Theme.width(label()) + 10 + Theme.width(v));
    }

    @Override
    public int height(Minecraft mc) {
        return 17;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        String v = value(mc);
        int vc = valueColor(mc);
        if (!showLabel.value) {
            text(g, v, (w - Theme.width(v)) / 2, 5, vc);
        } else if (inline()) {
            String l = label() + ": ";
            text(g, l, 6, 5, dimColor());
            text(g, v, 6 + Theme.width(l), 5, vc);
        } else {
            text(g, label(), 6, 5, dimColor());
            text(g, v, w - 6 - Theme.width(v), 5, vc);
        }
    }
}
