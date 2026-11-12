package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.util.Mth;

/**
 * Changes how dropped items look: spin speed and direction, sway or no rotation, bobbing, tilt and how high they
 * float. Purely visual and client-side; the item entity itself (position, pickup, despawn) is never touched.
 * <p>
 * The values are applied where the item renderer asks for its spin angle, bob wave and ground offset (see
 * ItemEntityMixin / ItemEntityRendererMixin). Rotation uses the frame-interpolated age the renderer already
 * passes in, so it is smooth at any FPS. Every hook is a few float operations with no allocation, and all hooks
 * are require = 0: if a Minecraft version changes them the client still starts and items simply look vanilla.
 */
public final class ItemPhysicsModule extends Module {
    private static ItemPhysicsModule instance;

    private static final int SPIN = 0, SWAY = 1, STILL = 2;

    private final ChoiceSetting mode = add(new ChoiceSetting("Rotation", 0, "Spin", "Sway", "Still"), "Rotation");
    private final SliderSetting spinSpeed = add(new SliderSetting("Spin speed", 0, 5, 0.1, 1.0, "x"), "Rotation", () -> mode.index != STILL);
    private final BoolSetting reverse = add(new BoolSetting("Reverse direction", false), "Rotation", () -> mode.index == SPIN);
    private final SliderSetting swayAngle = add(new SliderSetting("Sway angle", 5, 90, 5, 30, "\u00b0"), "Rotation", () -> mode.index == SWAY);

    private final BoolSetting bobbing = add(new BoolSetting("Bobbing", true), "Bobbing");
    private final SliderSetting bobHeight = add(new SliderSetting("Bob height", 0, 3, 0.1, 1.0, "x"), "Bobbing", () -> bobbing.value);
    private final SliderSetting bobSpeed = add(new SliderSetting("Bob speed", 0.1, 3, 0.1, 1.0, "x"), "Bobbing", () -> bobbing.value);

    private final SliderSetting tilt = add(new SliderSetting("Tilt", 0, 45, 1, 0, "\u00b0").zero("Off"), "Look");
    private final SliderSetting height = add(new SliderSetting("Ground offset", -0.1, 0.25, 0.01, 0, ""), "Look");

    public ItemPhysicsModule() {
        super("itemphysics", "Item Physics",
                "Smooth, adjustable spin, bobbing and tilt for dropped items. Visual only.",
                Category.VISUAL, false, 0f, 0f, false);
        instance = this;
    }

    private static boolean on() {
        ItemPhysicsModule m = instance;
        return m != null && m.enabled;
    }

    // ------------------------------------------------------------------ hooks (render thread)

    /** Replaces the item's spin angle (radians). {@code age} already includes the partial tick. */
    public static float spin(float age, float bobOffset, float vanilla) {
        if (!on()) return vanilla;
        ItemPhysicsModule m = instance;
        switch (m.mode.index) {
            case STILL:
                return bobOffset;
            case SWAY:
                return bobOffset + Mth.sin(age / 20f * (float) m.spinSpeed.value * 2f + bobOffset)
                        * (float) Math.toRadians(m.swayAngle.value);
            default:
                float dir = m.reverse.value ? -1f : 1f;
                return age / 20f * (float) m.spinSpeed.value * dir + bobOffset;
        }
    }

    /** Replaces the bob wave (vanilla: sin(age / 10 + offset), later scaled by 0.1). */
    public static float bob(float v, float vanilla) {
        if (!on()) return vanilla;
        ItemPhysicsModule m = instance;
        if (!m.bobbing.value) return 0f;
        return Mth.sin(v * (float) m.bobSpeed.value) * (float) m.bobHeight.value;
    }

    /** Extra tilt around the X axis in degrees (0 = none). */
    public static float tiltDegrees() {
        return on() ? (float) instance.tilt.value : 0f;
    }

    /** Added to the item's height above the ground. */
    public static float groundOffset() {
        return on() ? (float) instance.height.value : 0f;
    }
}
