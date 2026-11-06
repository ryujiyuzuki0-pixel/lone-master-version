package dev.pulse.client.module.impl;

import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Controls how bright the world looks, independent of the vanilla Brightness slider (that slider and
 * options.txt are never changed).
 * <p>
 * Three modes per dimension:
 * <ul>
 *   <li><b>Off</b>: vanilla brightness, untouched.</li>
 *   <li><b>Full Bright</b>: everything is fully lit, caves and night included.</li>
 *   <li><b>Custom</b>: your own level. 0% is "Moody", 100% is vanilla "Bright", above 100% goes brighter than the
 *       vanilla slider allows.</li>
 * </ul>
 * The value is applied where the game reads its gamma option for the light texture (see OptionInstanceMixin), so
 * it costs one comparison per option read. Changes fade in and out over the transition time.
 */
public final class CustomBrightnessModule extends Module {
    private static CustomBrightnessModule instance;
    /** Counts reads of the gamma option; stays 0 when the hook did not apply on this Minecraft version. */
    public static volatile long hookHits;

    /** Gamma factor used by Full Bright. The light shader saturates long before this. */
    private static final double FULL_BRIGHT = 16.0;

    private static final int OFF = 0, FULL = 1, CUSTOM = 2;

    /** Mode and level of one dimension (or of the global set). */
    private static final class Dim {
        ChoiceSetting mode;
        SliderSetting level;
    }

    private final ChoiceSetting preset = add(new ChoiceSetting("Preset", 0,
            "Custom", "Moody", "Vanilla Bright", "Bright", "Very bright"), "General");
    private final BoolSetting perDim = add(new BoolSetting("Per-dimension settings", false), "General");
    private final SliderSetting transition = add(new SliderSetting("Transition time", 0, 3, 0.25, 0.5, "s").zero("Instant"), "General");
    private final KeybindSetting toggleKey = add(new KeybindSetting("Quick toggle key", -1), "General");

    private final Dim global = dim("", "Brightness", () -> !perDim.value);
    private final Dim overworld = dim("Overworld ", "Overworld", () -> perDim.value);
    private final Dim nether = dim("Nether ", "Nether", () -> perDim.value);
    private final Dim end = dim("End ", "The End", () -> perDim.value);
    private final List<Dim> dims = List.of(global, overworld, nether, end);

    // Smoothed value, written on the render thread; target computed once per tick.
    private double cur = 1.0, tgt = 1.0;
    private float weight, tWeight;
    private long lastStep = System.nanoTime();

    private Object lastLevel;
    private int dimIndex;
    private int ticksSeen;
    private boolean warned;
    private boolean keyWasDown;

    public CustomBrightnessModule() {
        super("custombrightness", "Custom Brightness",
                "Full Bright, or set the world brightness yourself. Per dimension, with smooth fades.",
                Category.VISUAL, false, 0f, 0f, false);
        preset.onChange(this::applyPreset);
        instance = this;
    }

    private Dim dim(String prefix, String group, BooleanSupplier visible) {
        Dim d = new Dim();
        d.mode = add(new ChoiceSetting(prefix + "Mode", FULL, "Off", "Full Bright", "Custom"), group, visible);
        d.level = add(new SliderSetting(prefix + "Brightness", 0, 1000, 5, 100, "%"), group,
                () -> visible.getAsBoolean() && d.mode.index == CUSTOM);
        return d;
    }

    private void applyPreset() {
        double v;
        switch (preset.index) {
            case 1 -> v = 0;
            case 2 -> v = 100;
            case 3 -> v = 250;
            case 4 -> v = 500;
            default -> { return; }
        }
        for (Dim d : dims) {
            d.mode.index = CUSTOM;
            d.level.set(v);
        }
    }

    // ------------------------------------------------------------------ targets (once per tick)

    private Dim currentDim() {
        return switch (dimIndex) {
            case 1 -> nether;
            case 2 -> end;
            default -> overworld;
        };
    }

    /**
     * Runs every tick (ModuleManager calls it also while the module is off) so the module can fade out and so the
     * quick toggle key can switch it back on.
     */
    public void tickAlways(Minecraft mc) {
        boolean down = mc.screen == null && toggleKey.isDown(mc);
        if (down && !keyWasDown && mc.player != null) {
            setEnabled(!enabled);
            msg(mc.player, enabled ? describe() : "Off");
        }
        keyWasDown = down;
        if (enabled) updateTarget(mc);
        else tWeight = 0f;
    }

    private void updateTarget(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            tWeight = 0f;
            return;
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            String id = KeyNames.id(mc.level.dimension());
            dimIndex = id.contains("nether") ? 1 : id.contains("the_end") ? 2 : 0;
        }
        Dim d = perDim.value ? currentDim() : global;
        switch (d.mode.index) {
            case FULL -> {
                tgt = FULL_BRIGHT;
                tWeight = 1f;
            }
            case CUSTOM -> {
                tgt = d.level.value / 100.0;
                tWeight = 1f;
            }
            default -> tWeight = 0f;
        }
        // Hook watchdog: say so once if the game never read the gamma option through the hook.
        if (!warned && ++ticksSeen == 400 && hookHits == 0) {
            warned = true;
            PulseClient.LOG.warn("Custom Brightness: the brightness hook did not apply on this Minecraft version.");
            msg(mc.player, "not supported on this Minecraft version yet.");
        }
    }

    private String describe() {
        Dim d = perDim.value ? currentDim() : global;
        return switch (d.mode.index) {
            case FULL -> "Full Bright";
            case CUSTOM -> "Brightness " + d.level.format();
            default -> "Off (vanilla)";
        };
    }

    private static void msg(Player p, String s) {
        p.displayClientMessage(Component.literal("Custom Brightness: " + s), true);
    }

    @Override
    public void onDisable() {
        tWeight = 0f;
    }

    // ------------------------------------------------------------------ per-read blend (called from the mixin)

    private boolean live() {
        return enabled || weight > 0.001f;
    }

    /** Moves the smoothed values toward the targets. Cheap and safe to call many times per frame. */
    private void step() {
        long now = System.nanoTime();
        if (now - lastStep < 500_000L) return;
        float dt = Math.min(0.25f, (now - lastStep) / 1_000_000_000f);
        lastStep = now;
        float tau = (float) transition.value / 3f;
        float k = tau <= 0f ? 1f : 1f - (float) Math.exp(-dt / tau);
        weight += (tWeight - weight) * k;
        cur += (tgt - cur) * k;
        if (tWeight == 0f && weight < 0.001f) weight = 0f;
    }

    /**
     * The gamma value the game should use. {@code vanilla} is what the player's own Brightness slider says;
     * with the module off (or fully faded out) it is returned unchanged.
     */
    public static double gamma(double vanilla) {
        hookHits++;
        CustomBrightnessModule m = instance;
        if (m == null || !m.live()) return vanilla;
        m.step();
        return vanilla + (m.cur - vanilla) * m.weight;
    }
}
