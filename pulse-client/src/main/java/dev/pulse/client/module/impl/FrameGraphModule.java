package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * A live graph of how long each frame takes. Spikes show up as tall red bars, which is the quickest way to see
 * whether a setting or another module is causing stutter. It samples once per drawn frame and costs next to nothing.
 */
public final class FrameGraphModule extends Module {
    private static final int SAMPLES = 100;
    private final SliderSetting ceiling = add(new SliderSetting("Graph height", 10, 100, 5, 33, "ms"));
    private final float[] ring = new float[SAMPLES];
    private int head;
    private long last;

    public FrameGraphModule() {
        super("framegraph", "Frame Graph", "Frame time history, so stutter is easy to spot.", Category.HUD, true, 0.008f, 0.7f, true);
    }

    @Override public int width(Minecraft mc) { return SAMPLES + 12; }

    @Override public int height(Minecraft mc) { return 52; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        long now = System.nanoTime();
        if (last != 0) {
            ring[head] = (now - last) / 1_000_000f;
            head = (head + 1) % SAMPLES;
        }
        last = now;

        float max = 0, sum = 0;
        for (float f : ring) {
            max = Math.max(max, f);
            sum += f;
        }
        int gh = 30, top = 18;
        for (int i = 0; i < SAMPLES; i++) {
            float ms = ring[(head + i) % SAMPLES];
            int bar = Math.max(1, Math.min(gh, Math.round(ms / (float) ceiling.value * gh)));
            int c = ms > 33.4f ? 0xFFF87171 : ms > 16.8f ? 0xFFFBBF24 : 0xFF4ADE80;
            g.fill(6 + i, top + gh - bar, 7 + i, top + gh, c);
        }
        String avg = String.format(Locale.ROOT, "%.1f ms", sum / SAMPLES);
        text(g, "FRAMES", 6, 4, dimColor());
        text(g, avg + "  max " + Math.round(max), w - 6 - Theme.width(avg + "  max " + Math.round(max)), 4, textColor());
    }
}
