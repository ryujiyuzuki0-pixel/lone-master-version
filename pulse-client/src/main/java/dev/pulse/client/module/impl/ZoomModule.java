package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.OptionGuard;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Smooth zoom. Hold (or toggle) the key and the view eases in; scroll while zoomed to change how far.
 * <p>
 * Nothing in the player's options is ever changed: the FOV and mouse-sensitivity reads are answered by
 * OptionInstanceMixin through {@link #override()} and {@link #sensitivity}, so options.txt and the vanilla sliders
 * always keep your own values. The zoom level is animated every rendered frame ({@link #frame}), not every tick,
 * so it stays smooth at any frame rate. The game takes whole-degree FOV values, so at very high zoom the steps are
 * a little coarser than at low zoom.
 */
public final class ZoomModule extends Module {
    private static ZoomModule instance;

    private final KeybindSetting key = add(new KeybindSetting("Zoom key", GLFW.GLFW_KEY_C));
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Hold", "Toggle"));
    private final SliderSetting level = add(new SliderSetting("Zoom level", 1.5, 20, 0.5, 4, "x"));
    private final SliderSetting smooth = add(new SliderSetting("Smoothness", 4, 30, 1, 14, ""));
    private final BoolSetting scroll = add(new BoolSetting("Scroll to adjust", true));
    private final BoolSetting scaleSens = add(new BoolSetting("Scale mouse sensitivity", true));

    private double zoom = 1.0;     // current magnification, 1 = off
    private double target = 4.0;   // magnification while zooming (scroll changes this)
    private boolean toggled, keyWasDown, wantZoom;
    private long lastNs;

    public ZoomModule() {
        super("zoom", "Zoom", "Hold a key to zoom in smoothly. Scroll while zoomed to adjust.", Category.UTILITY, false, 0f, 0f, false);
        instance = this;
        target = level.value;
        level.onChange = () -> target = level.value;
    }

    // ------------------------------------------------------------------ per-frame update

    /** Called once per rendered frame from HudRenderer. */
    public static void frame(Minecraft mc) {
        ZoomModule m = instance;
        if (m == null) return;
        if (!m.enabled) {
            m.zoom = 1.0;
            return;
        }
        m.update(mc);
    }

    private void update(Minecraft mc) {
        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0.016 : Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;
        if (mc.getWindow() == null || mc.player == null) {
            toggled = false;
            wantZoom = false;
        } else {
            boolean down = mc.screen == null && key.isDown(mc);
            if (mode.index == 1) {
                if (down && !keyWasDown) toggled = !toggled;
                wantZoom = toggled && mc.screen == null;
            } else {
                toggled = false;
                wantZoom = down;
            }
            keyWasDown = down;
        }
        double goal = wantZoom ? target : 1.0;
        zoom += (goal - zoom) * (1.0 - Math.exp(-dt * smooth.value));
        if (!wantZoom && zoom < 1.005) zoom = 1.0;
        if (!wantZoom && zoom == 1.0) target = level.value; // next zoom starts from the configured level again
    }

    // ------------------------------------------------------------------ hooks read by mixins

    /** True while the view is zoomed in at all; the FOV Changer steps aside so the two never fight. */
    public static boolean isZooming() {
        ZoomModule m = instance;
        return m != null && m.enabled && m.zoom > 1.01;
    }

    /** The FOV to use right now, or -1 when not zooming. Answers reads of the FOV option (OptionInstanceMixin). */
    public static int override() {
        ZoomModule m = instance;
        if (m == null || !m.enabled || m.zoom <= 1.01 || OptionGuard.passThrough()) return -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || OptionGuard.inVideoSettings(mc)) return -1;
        int base = FovModule.effectiveBase(OptionGuard.rawFov(mc));
        return Math.max(1, (int) Math.round(base / m.zoom));
    }

    /** Mouse sensitivity while zoomed: lower in step with the magnification so aiming stays controllable. */
    public static double sensitivity(double vanilla) {
        ZoomModule m = instance;
        if (m == null || !m.enabled || !m.scaleSens.value || m.zoom <= 1.01) return vanilla;
        return vanilla / Math.pow(m.zoom, 0.7);
    }

    /** Called for every scroll event. Returns true if zoom used it (so the hotbar does not also scroll). */
    public static boolean consumeScroll(double dy) {
        ZoomModule m = instance;
        if (m == null || !m.enabled || !m.scroll.value || !m.wantZoom || dy == 0) return false;
        m.target = Math.max(1.2, Math.min(30.0, m.target * (dy > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }

    @Override
    public void onDisable() {
        zoom = 1.0;
        toggled = false;
        wantZoom = false;
        lastNs = 0;
    }
}
