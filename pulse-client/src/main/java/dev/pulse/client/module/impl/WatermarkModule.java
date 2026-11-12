package dev.pulse.client.module.impl;

import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Small "LONE <version>" tag. */
public final class WatermarkModule extends Module {
    private final ChoiceSetting style = add(new ChoiceSetting("Text", 0, "LONE", "LONE CLIENT", "Lone Client"));
    private final BoolSetting showVersion = add(new BoolSetting("Show version", true));

    public WatermarkModule() {
        super("watermark", "Watermark", "Shows the client name and version.", Category.HUD,
                true, 0.5f, 0.008f, true);
        textColorSetting.withMode(1);
    }

    private String text() {
        return style.get() + (showVersion.value ? " " + PulseClient.shortVersion() : "");
    }

    @Override public int width(Minecraft mc) { return 14 + Theme.width(text()); }

    @Override public int height(Minecraft mc) { return 17; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        text(g, text(), 7, 5, textColor());
    }
}
