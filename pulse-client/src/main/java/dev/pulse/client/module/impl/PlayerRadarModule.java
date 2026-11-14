package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Lists the closest other players with their distance. */
public final class PlayerRadarModule extends Module {
    private static final int ROW = 12;
    private final SliderSetting rows = add(new SliderSetting("Rows", 1, 10, 1, 5, ""));
    private final SliderSetting range = add(new SliderSetting("Range", 16, 256, 8, 96, "m"));

    private record Entry(String name, double distance) {}

    public PlayerRadarModule() {
        super("playerradar", "Player Radar", "Nearby players and how far away they are.", Category.HUD, true, 0.008f, 0.5f, true);
    }

    private List<Entry> entries(Minecraft mc) {
        List<Entry> out = new ArrayList<>();
        if (HudRenderer.editing() && (mc.player == null || mc.level == null)) {
            out.add(new Entry("Player", 12.0));
            out.add(new Entry("Friend", 31.5));
            return out;
        }
        if (mc.player == null || mc.level == null) return out;
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            double d = p.distanceTo(mc.player);
            if (d <= range.value) out.add(new Entry(p.getName().getString(), d));
        }
        out.sort(Comparator.comparingDouble(Entry::distance));
        return out.size() > rows.value ? new ArrayList<>(out.subList(0, (int) rows.value)) : out;
    }

    @Override public int width(Minecraft mc) { return entries(mc).isEmpty() ? 0 : 130; }

    @Override public int height(Minecraft mc) { return entries(mc).isEmpty() ? 0 : entries(mc).size() * ROW + 6; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int y = 4;
        for (Entry e : entries(mc)) {
            String d = String.format(Locale.ROOT, "%.0fm", e.distance());
            text(g, Theme.fit(e.name(), w - 18 - Theme.width(d)), 6, y, textColor());
            text(g, d, w - 6 - Theme.width(d), y, dimColor());
            y += ROW;
        }
    }
}
