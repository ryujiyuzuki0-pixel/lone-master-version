package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;

import java.util.Locale;

public final class CoordsModule extends LineModule {
    private final BoolSetting direction = add(new BoolSetting("Show direction", true));
    private final ChoiceSetting dirStyle = add(new ChoiceSetting("Direction style", 0, "Letter", "Word"), "Format", () -> this.direction.value);
    private final BoolSetting showY = add(new BoolSetting("Show Y", true), "Format");
    private final SliderSetting decimals = add(new SliderSetting("Decimals", 0, 3, 1, 0, ""), "Format");
    private final ChoiceSetting separator = add(new ChoiceSetting("Separator", 0, "  ", " , ", " / ", " | "), "Format");

    public CoordsModule() {
        super("coords", "Coordinates", "Your XYZ position and facing.", Category.HUD, 0.008f, 0.126f);
    }

    @Override protected String label() { return "XYZ"; }

    private String num(double v) {
        int d = decimals.intValue();
        if (d <= 0) return String.valueOf((int) Math.floor(v));
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    @Override
    protected String value(Minecraft mc) {
        if (mc.player == null) return "0 0 0";
        String sep = separator.get();
        String s = num(mc.player.getX()) + sep + (showY.value ? num(mc.player.getY()) + sep : "") + num(mc.player.getZ());
        if (direction.value) {
            Direction d = mc.player.getDirection();
            String n = d.getName();
            s += sep + (dirStyle.index == 0 ? String.valueOf(Character.toUpperCase(n.charAt(0)))
                    : Character.toUpperCase(n.charAt(0)) + n.substring(1));
        }
        return s;
    }

    @Override protected int minWidth() { return 120; }
}
