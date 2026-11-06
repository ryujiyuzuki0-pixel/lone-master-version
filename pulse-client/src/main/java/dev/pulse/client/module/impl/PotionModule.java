package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PotionModule extends Module {
    private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

    private record Entry(String name, String time, int color, boolean low) {}

    private final BoolSetting showTime = add(new BoolSetting("Show duration", true));
    private final BoolSetting showLevel = add(new BoolSetting("Show level", true));
    private final BoolSetting marker = add(new BoolSetting("Color marker", true));
    private final ChoiceSetting sort = add(new ChoiceSetting("Sort", 0, "Longest first", "Shortest first", "Name"));
    private final SliderSetting lowAt = add(new SliderSetting("Low warning", 0, 30, 1, 10, "s").zero("Off"), "Layout");
    private final SliderSetting maxRows = add(new SliderSetting("Max rows", 1, 12, 1, 8, ""), "Layout");
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 90, 220, 2, 128, "px"), "Layout");

    public PotionModule() {
        super("potions", "Potion Effects", "Active effects with a countdown.", Category.HUD,
                true, 0.992f, 0.02f, true);
    }

    private List<Entry> entries(Minecraft mc) {
        List<Entry> out = new ArrayList<>();
        if (HudRenderer.editing() && (mc.player == null || mc.player.getActiveEffects().isEmpty())) {
            out.add(new Entry("Speed" + (showLevel.value ? " II" : ""), "0:42", 0x7CAFC6, false));
            out.add(new Entry("Strength", "0:08", 0x932423, lowAt.value >= 8));
            return out;
        }
        if (mc.player == null) return out;
        List<MobEffectInstance> list = new ArrayList<>(mc.player.getActiveEffects());
        switch (sort.index) {
            case 1 -> list.sort(Comparator.comparingInt(MobEffectInstance::getDuration));
            case 2 -> list.sort(Comparator.comparing((MobEffectInstance e) -> e.getEffect().value().getDisplayName().getString()));
            default -> list.sort(Comparator.comparingInt(MobEffectInstance::getDuration).reversed());
        }
        int cap = maxRows.intValue();
        for (MobEffectInstance e : list) {
            if (out.size() >= cap) break;
            int amp = Math.max(0, Math.min(ROMAN.length - 1, e.getAmplifier()));
            String name = e.getEffect().value().getDisplayName().getString() + (showLevel.value ? ROMAN[amp] : "");
            String time;
            boolean low = false;
            if (e.isInfiniteDuration()) {
                time = "**";
            } else {
                int s = e.getDuration() / 20;
                time = (s / 60) + ":" + String.format("%02d", s % 60);
                low = lowAt.value > 0 && s < lowAt.value;
            }
            out.add(new Entry(name, time, e.getEffect().value().getColor(), low));
        }
        return out;
    }

    @Override
    public int width(Minecraft mc) {
        return entries(mc).isEmpty() ? 0 : boxWidth.intValue();
    }

    @Override
    public int height(Minecraft mc) {
        int n = entries(mc).size();
        return n == 0 ? 0 : 8 + n * 13;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int y = 5;
        int nameX = marker.value ? 13 : 6;
        for (Entry e : entries(mc)) {
            if (marker.value) g.fill(6, y + 2, 9, y + 7, 0xFF000000 | e.color());
            int reserve = showTime.value ? 60 : 16;
            text(g, Theme.fit(e.name(), w - reserve), nameX, y, textColor());
            if (showTime.value) text(g, e.time(), w - 6 - Theme.width(e.time()), y, e.low() ? Theme.BAD : dimColor());
            y += 13;
        }
    }
}
