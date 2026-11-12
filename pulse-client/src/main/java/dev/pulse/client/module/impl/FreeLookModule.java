package dev.pulse.client.module.impl;

import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Look around without turning. While the key is held (or toggled) the mouse rotates the camera only; the player's
 * real rotation is never written, so movement, attacks, block placing and what the server sees stay exactly as they
 * were. Letting go swings the camera back to the real view over the "Return time".
 * <p>
 * Two hooks make it work: EntityMixin hands the mouse movement to {@link #onTurn} instead of the player, and
 * CameraMixin asks {@link #rotation} for the camera angles. Both are require = 0; if either did not apply on this
 * Minecraft version the module never activates (so the view can never end up half-hooked) and says so once.
 */
public final class FreeLookModule extends Module {
    private static FreeLookModule instance;

    /** Counters that stay 0 when a hook did not apply on this Minecraft version. */
    public static volatile long cameraHits, turnHits;

    /** Result of {@link #rotation}: the camera angles to use (player-space yaw / pitch, already adjusted for the view). */
    public static float outYaw, outPitch;

    private final KeybindSetting key = add(new KeybindSetting("Free look key", GLFW.GLFW_KEY_LEFT_ALT), "General");
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Hold", "Toggle"), "General");
    private final SliderSetting sensitivity = add(new SliderSetting("Sensitivity", 0.25, 2.0, 0.05, 1.0, "x"), "General");
    private final SliderSetting returnMs = add(new SliderSetting("Return time", 0, 600, 25, 200, "ms").zero("Instant"), "General");
    /** What the view does while free look is held. The original view is restored on release. */
    private final ChoiceSetting perspective = add(new ChoiceSetting("Perspective", 1, "Keep view", "Third person back", "Third person front"), "General");
    private final BoolSetting invertYaw = add(new BoolSetting("Invert horizontal", false), "General");
    private final BoolSetting invertPitch = add(new BoolSetting("Invert vertical", false), "General");

    private boolean active;      // the camera is decoupled from the player
    private boolean returning;   // the camera is swinging back to the player's view
    private boolean toggled;
    private boolean keyWasDown;
    private float camYaw, camPitch;     // player-space angles of the camera
    private float fromYaw, fromPitch;   // where the return started
    private long returnStart;
    private int worldTicks;
    private boolean warned;
    private CameraType savedView;        // the player's own view (first / third person) before free look changed it

    public FreeLookModule() {
        super("freelook", "Free Look",
                "Hold a key to look around without turning your player. Movement and attacks stay where you aim.",
                Category.GAMEPLAY, false, 0f, 0f, false);
        instance = this;
    }

    // ------------------------------------------------------------------ state

    private static boolean inWorld(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.getCameraEntity() == mc.player && !mc.player.isSleeping();
    }

    /** Polls the key and starts / ends free look. Cheap and idempotent, called every tick and every camera update. */
    private void update(Minecraft mc) {
        boolean ready = cameraHits > 0 && turnHits > 0 && inWorld(mc) && mc.screen == null && mc.getWindow() != null;
        boolean down = ready && key.isDown(mc);
        boolean want;
        if (!ready) {
            toggled = false;
            want = false;
        } else if (mode.index == 1) {
            if (down && !keyWasDown) toggled = !toggled;
            want = toggled;
        } else {
            toggled = false;
            want = down;
        }
        keyWasDown = down;
        if (want && !active) {
            if (!returning) { // a fresh start; during a return the camera simply carries on from where it is
                camYaw = mc.player.getYRot();
                camPitch = mc.player.getXRot();
            }
            active = true;
            returning = false;
            applyPerspective(mc);
        } else if (!want && active) {
            stop(mc);
        }
    }

    private void stop(Minecraft mc) {
        active = false;
        restorePerspective(mc);
        if (returnMs.value <= 0 || !inWorld(mc)) {
            returning = false;
            return;
        }
        returning = true;
        fromYaw = camYaw;
        fromPitch = camPitch;
        returnStart = System.nanoTime();
    }

    private void reset() {
        restorePerspective(Minecraft.getInstance());
        active = false;
        returning = false;
        toggled = false;
    }

    /** Switches to the chosen perspective for the duration of free look, remembering the player's own. */
    private void applyPerspective(Minecraft mc) {
        if (perspective.index == 0 || mc.options == null || savedView != null) return;
        savedView = mc.options.getCameraType();
        mc.options.setCameraType(perspective.index == 2 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
    }

    /** Puts the player's own view back (first person stays first person). Safe to call at any time. */
    private void restorePerspective(Minecraft mc) {
        if (savedView == null) return;
        CameraType back = savedView;
        savedView = null;
        if (mc != null && mc.options != null) mc.options.setCameraType(back);
    }

    @Override
    public void tick(Minecraft mc) {
        update(mc);
        if (mc.player == null) {
            worldTicks = 0;
            return;
        }
        // Hook watchdog: say so once if a hook never ran (the module then stays inactive instead of half working).
        if (!warned && ++worldTicks == 400 && (cameraHits == 0 || turnHits == 0)) {
            warned = true;
            PulseClient.LOG.warn("Free Look: a hook did not apply on this Minecraft version (camera={}, turn={}).",
                    cameraHits, turnHits);
            mc.player.displayClientMessage(Component.literal("Free Look: not supported on this Minecraft version yet."), true);
        }
    }

    @Override
    public void onDisable() {
        reset();
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Mouse movement of the local player (EntityMixin). Returns true when free look took it, in which case the
     * player must not turn.
     */
    public static boolean onTurn(double yawDelta, double pitchDelta) {
        turnHits++;
        FreeLookModule m = instance;
        if (m == null || !m.enabled || !m.active) return false;
        float s = 0.15f * (float) m.sensitivity.value; // vanilla turns by 0.15 degrees per mouse unit
        m.camYaw += (float) yawDelta * s * (m.invertYaw.value ? -1f : 1f);
        m.camPitch = Mth.clamp(m.camPitch + (float) pitchDelta * s * (m.invertPitch.value ? -1f : 1f), -90f, 90f);
        return true;
    }

    /**
     * Camera angles as vanilla computed them in; returns true and fills {@link #outYaw} / {@link #outPitch} when
     * free look wants different ones (CameraMixin).
     */
    public static boolean rotation(float yaw, float pitch) {
        cameraHits++;
        FreeLookModule m = instance;
        if (m == null || !m.enabled) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        m.update(mc);
        if (!m.active && !m.returning) return false;
        if (!inWorld(mc)) {
            m.reset();
            return false;
        }
        // Third person from the front hands the camera yaw + 180 and the pitch negated; work in player space.
        boolean mirrored = Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot())) > 90f;
        float realYaw = mirrored ? yaw - 180f : yaw;
        float realPitch = mirrored ? -pitch : pitch;
        float y, p;
        if (m.active) {
            y = m.camYaw;
            p = m.camPitch;
        } else {
            float t = (float) ((System.nanoTime() - m.returnStart) / 1.0e6 / Math.max(1.0, m.returnMs.value));
            if (t >= 1f) {
                m.returning = false;
                return false;
            }
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            y = m.fromYaw + Mth.wrapDegrees(realYaw - m.fromYaw) * e;
            p = m.fromPitch + (realPitch - m.fromPitch) * e;
            m.camYaw = y; // keeps a re-press during the return continuous
            m.camPitch = p;
        }
        outYaw = mirrored ? y + 180f : y;
        outPitch = mirrored ? -p : p;
        return true;
    }
}
