package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * A "LABEL ... value" HUD line whose value comes from a function. {@link InfoModules} builds a whole family of
 * these (speed, direction, light level, ...) so each one is a few lines instead of a class of its own.
 */
public final class InfoLineModule extends LineModule {
    private final String label;
    private final Function<Minecraft, String> value;
    private final ToIntFunction<Minecraft> color;
    private final boolean needsWorld;

    /**
     * @param needsWorld true if the value reads the player or level; the line then shows "--" in menus
     * @param color      value colour, or null for the user's text colour (a result of 0 also means "default")
     */
    InfoLineModule(String id, String name, String desc, float fx, float fy, String label, boolean needsWorld,
                   Function<Minecraft, String> value, ToIntFunction<Minecraft> color) {
        super(id, name, desc, Category.HUD, fx, fy);
        this.label = label;
        this.needsWorld = needsWorld;
        this.value = value;
        this.color = color;
    }

    /** Lets {@link InfoModules} attach a setting that the value function reads. */
    <T extends Setting> T option(T s) {
        return add(s);
    }

    @Override protected String label() { return label; }

    @Override
    protected String value(Minecraft mc) {
        if (needsWorld && (mc.player == null || mc.level == null)) return "--";
        try {
            return value.apply(mc);
        } catch (RuntimeException e) { // a bad read must never take the HUD down
            return "--";
        }
    }

    @Override
    protected int valueColor(Minecraft mc) {
        if (color == null || (needsWorld && (mc.player == null || mc.level == null))) return textColor();
        int c = color.applyAsInt(mc);
        return c == 0 ? textColor() : c;
    }
}
