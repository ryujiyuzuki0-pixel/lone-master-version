package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.OptionGuard;
import net.minecraft.client.Minecraft;

/**
 * Overrides the field of view the game renders with, without touching the player's own FOV setting or options.txt.
 * <p>
 * The value is applied where the game reads the FOV option (see OptionInstanceMixin). The game multiplies that base
 * value by its dynamic effects (sprinting, speed and slowness, bow draw, flying) afterwards, so those effects keep
 * working exactly as in vanilla, scaled by the player's own "FOV Effects" slider. The Zoom module wins while it is
 * zooming, the real value is shown while the Video Settings screen is open, and turning the module off puts the
 * player's own FOV back immediately.
 */
public final class FovModule extends Module {
    private static FovModule instance;

    private final SliderSetting fov = add(new SliderSetting("Field of view", 30, 120, 1, 90, ""), "General");

    public FovModule() {
        super("fov", "FOV Changer",
                "Set your field of view beyond the vanilla slider. Sprint and speed effects still work.",
                Category.VISUAL, false, 0f, 0f, false);
        instance = this;
    }

    /**
     * The FOV the renderer should use, or -1 to leave the player's own value alone. Called on every read of the
     * FOV option, so it does nothing but a few field reads.
     */
    public static int override() {
        FovModule m = instance;
        if (m == null || !m.enabled || OptionGuard.passThrough() || ZoomModule.isZooming()) return -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || OptionGuard.inVideoSettings(mc)) return -1;
        return (int) Math.round(m.fov.value);
    }
}
