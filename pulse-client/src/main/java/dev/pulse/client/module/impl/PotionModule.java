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
import net.minecraft.client.renderer.RenderPipelines;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PotionModule extends Module {
    private static final String[] ROMAN = {"", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};

    private record Entry(String name, String time, int color, boolean low, String sprite) {}

    private final ChoiceSetting style = add(new ChoiceSetting("Style", 0, "Vanilla", "Compact list"));
    private final BoolSetting showTime = add(new BoolSetting("Show duration", true), () -> this.style.index == 1);
    private final BoolSetting showLevel = add(new BoolSetting("Show level", true));
    private final BoolSetting marker = add(new BoolSetting("Color marker", true), () -> this.style.index == 1);
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
            out.add(new Entry("Speed" + (showLevel.value ? " II" : ""), fmt(42), 0x7CAFC6, false, "minecraft:speed"));
            out.add(new Entry("Strength", fmt(8), 0x932423, lowAt.value >= 8, "minecraft:strength"));
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
                time = "\u221E";
            } else {
                int s = e.getDuration() / 20;
                time = fmt(s);
                low = lowAt.value > 0 && e.getDuration() > 0 && s < lowAt.value;
            }
            out.add(new Entry(name, time, e.getEffect().value().getColor(), low, e.getEffect().getRegisteredName()));
        }
        return out;
    }

    private static String fmt(int totalSeconds) {
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private boolean vanilla() {
        return style.index == 0;
    }

    private static final int CARD_H = 32, CARD_GAP = 2;

    @Override
    public int width(Minecraft mc) {
        return entries(mc).isEmpty() ? 0 : boxWidth.intValue();
    }

    @Override
    public int height(Minecraft mc) {
        int n = entries(mc).size();
        if (n == 0) return 0;
        return vanilla() ? n * CARD_H + (n - 1) * CARD_GAP : 8 + n * 13;
    }

    @Override
    public void drawBackground(GuiGraphics g, int w, int h) {
        if (vanilla()) return; // each effect draws its own card
        super.drawBackground(g, w, h);
    }

    /** Draws the vanilla effect icon (the "mob_effect/<name>" GUI sprite) at the given spot, 18x18. */
    private static void icon(GuiGraphics g, String registered, int x, int y) {
        int c = registered.indexOf(':');
        String ns = c < 0 ? "minecraft" : registered.substring(0, c);
        String path = "mob_effect/" + (c < 0 ? registered : registered.substring(c + 1));
        //? if >=1.21.11 {
        Identifier id = Identifier.fromNamespaceAndPath(ns, path);
        //?} else {
        /*ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ns, path);
        *///?}
        g.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, 18, 18);
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        if (vanilla()) {
            int y = 0;
            for (Entry e : entries(mc)) {
                Theme.roundRect(g, 0, y, w, CARD_H, 4, 0xE0101018);
                Theme.roundRect(g, 1, y + 1, w - 2, CARD_H - 2, 3, 0xE0242430);
                icon(g, e.sprite(), 7, y + 7);
                g.drawString(Theme.font(), Theme.fit(e.name(), w - 36), 29, y + 7, 0xFFFFFFFF, true);
                g.drawString(Theme.font(), e.time(), 29, y + 18, e.low() ? Theme.BAD : 0xFF8F8F8F, true);
                y += CARD_H + CARD_GAP;
            }
            return;
        }
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
