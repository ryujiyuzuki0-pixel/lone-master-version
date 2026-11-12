package dev.pulse.client.module.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Moves, turns and scales the hand and the held item in first person, and tunes the swing and equip animations.
 * Purely visual: nothing about the player, the item or what the server sees changes. Every value is read each frame,
 * so changes in the menu apply live.
 * <p>
 * Hooked in ItemInHandRendererMixin (require = 0: if a Minecraft version reshapes the renderer the client still
 * starts, the hand is drawn as in vanilla and the module says so once). Position is in blocks of view space
 * (X right, Y up, Z toward you); rotation and scale pivot around where the hand rests, so they turn the item
 * rather than swing it around the camera.
 */
public final class ViewmodelModule extends Module {
    private static ViewmodelModule instance;
    /** Counts calls of the pose hook; stays 0 when the hook did not apply on this Minecraft version. */
    public static volatile long hookHits;
    /** Hand being rendered, set by the mixin just before the pose hook runs. */
    private static InteractionHand currentHand = InteractionHand.MAIN_HAND;

    private static final String[] HANDS = {"Both hands", "Main hand", "Off hand"};
    private static final String[] SWING = {"Vanilla", "Custom speed", "No swing"};

    private final ChoiceSetting hands = add(new ChoiceSetting("Apply to", 0, HANDS), "General");

    private final SliderSetting posX = add(new SliderSetting("Position X", -1.5, 1.5, 0.01, 0, ""), "Offset");
    private final SliderSetting posY = add(new SliderSetting("Position Y", -1.5, 1.5, 0.01, 0, ""), "Offset");
    private final SliderSetting posZ = add(new SliderSetting("Position Z", -1.5, 1.5, 0.01, 0, ""), "Offset");

    private final SliderSetting rotX = add(new SliderSetting("Rotation X", -180, 180, 1, 0, "°"), "Rotation");
    private final SliderSetting rotY = add(new SliderSetting("Rotation Y", -180, 180, 1, 0, "°"), "Rotation");
    private final SliderSetting rotZ = add(new SliderSetting("Rotation Z", -180, 180, 1, 0, "°"), "Rotation");

    private final SliderSetting scale = add(new SliderSetting("Item scale", 0.2, 2.0, 0.01, 1.0, "x"), "Size");

    private final ChoiceSetting swing = add(new ChoiceSetting("Swing animation", 0, SWING), "Animation");
    private final SliderSetting swingSpeed = add(new SliderSetting("Swing speed", 0.3, 3.0, 0.05, 1.0, "x"), "Animation",
            () -> this.swing.index == 1);
    private final BoolSetting equip = add(new BoolSetting("Equip animation", true), "Animation");

    private int worldTicks;
    private boolean warned;

    public ViewmodelModule() {
        super("viewmodel", "Viewmodel",
                "Move, rotate and scale your hand and held item, and tune the swing and equip animations.",
                Category.VISUAL, false, 0f, 0f, false);
        instance = this;
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) {
            worldTicks = 0;
            return;
        }
        if (!warned && ++worldTicks == 400 && hookHits == 0) {
            warned = true;
            PulseClient.LOG.warn("Viewmodel: the hand renderer hook did not apply on this Minecraft version.");
            mc.player.displayClientMessage(Component.literal("Viewmodel: not supported on this Minecraft version yet."), true);
        }
    }

    // ------------------------------------------------------------------ hooks (called from the mixin, every frame)

    private static boolean applies(InteractionHand hand) {
        ViewmodelModule m = instance;
        if (m == null || !m.enabled) return false;
        return switch (m.hands.index) {
            case 1 -> hand == InteractionHand.MAIN_HAND;
            case 2 -> hand == InteractionHand.OFF_HAND;
            default -> true;
        };
    }

    /** Remembers which hand the renderer is about to draw. Returns the hand unchanged. */
    public static InteractionHand hand(InteractionHand hand) {
        if (hand != null) currentHand = hand;
        return hand;
    }

    /** Swing progress (0 = idle, 1 = finished) as the renderer should use it. */
    public static float swing(float progress) {
        ViewmodelModule m = instance;
        if (m == null || !m.enabled || !applies(currentHand)) return progress;
        switch (m.swing.index) {
            case 1: {
                float p = Math.max(0f, Math.min(1f, progress));
                float s = (float) m.swingSpeed.value;
                // 0 stays 0 and 1 stays 1, so the arm never snaps: faster finishes early, slower starts late.
                return s >= 1f ? Math.min(1f, p * s) : (float) Math.pow(p, 1.0 / s);
            }
            case 2:
                return 0f;
            default:
                return progress;
        }
    }

    /** Equip progress (0 = item fully raised) as the renderer should use it. */
    public static float equip(float progress) {
        ViewmodelModule m = instance;
        if (m == null || !m.enabled || m.equip.value || !applies(currentHand)) return progress;
        return 0f;
    }

    /**
     * Applies position, rotation and scale. Called right after the renderer pushed its own pose for this hand, so
     * the renderer's own pop removes the change again and nothing leaks into the other hand or the next frame.
     */
    public static void apply(PoseStack pose) {
        hookHits++;
        ViewmodelModule m = instance;
        if (m == null || !m.enabled || pose == null || !applies(currentHand)) return;
        float x = (float) m.posX.value, y = (float) m.posY.value, z = (float) m.posZ.value;
        float rx = (float) m.rotX.value, ry = (float) m.rotY.value, rz = (float) m.rotZ.value;
        float s = (float) m.scale.value;
        if (x == 0f && y == 0f && z == 0f && rx == 0f && ry == 0f && rz == 0f && s == 1f) return;

        // Where the vanilla renderer rests this hand: pivot for rotation and scale.
        Minecraft mc = Minecraft.getInstance();
        float side = 1f;
        if (mc != null && mc.player != null) {
            HumanoidArm arm = currentHand == InteractionHand.MAIN_HAND ? mc.player.getMainArm() : mc.player.getMainArm().getOpposite();
            side = arm == HumanoidArm.RIGHT ? 1f : -1f;
        }
        float pivotX = side * 0.56f, pivotY = -0.52f, pivotZ = -0.72f;

        pose.translate(x, y, z);
        pose.translate(pivotX, pivotY, pivotZ);
        if (rz != 0f) pose.mulPose(Axis.ZP.rotationDegrees(rz));
        if (ry != 0f) pose.mulPose(Axis.YP.rotationDegrees(ry));
        if (rx != 0f) pose.mulPose(Axis.XP.rotationDegrees(rx));
        if (s != 1f) pose.scale(s, s, s);
        pose.translate(-pivotX, -pivotY, -pivotZ);
    }
}
