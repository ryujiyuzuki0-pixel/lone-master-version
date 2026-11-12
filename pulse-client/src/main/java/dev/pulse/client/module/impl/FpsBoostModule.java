package dev.pulse.client.module.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Lowers the vanilla video settings that cost the most frames, and puts yours back when it is turned off.
 * <ul>
 *   <li>It only ever lowers a value. A setting you already run lower is never raised.</li>
 *   <li>If you change a setting yourself in the vanilla menu while Boost is on, that one is left alone
 *       when Boost turns off.</li>
 *   <li>Your originals are kept in config/pulse-fpsboost.json while Boost is active, so a crash or a
 *       killed game cannot leave you stuck on the boosted values.</li>
 * </ul>
 * It only calls public {@link Options} accessors (no mixins), so it behaves the same on every supported version.
 */
public final class FpsBoostModule extends Module {
    /** The "Unlimited" position of the vanilla Max Framerate slider. */
    private static final int UNLIMITED_FPS = 260;

    private final ChoiceSetting preset = add(new ChoiceSetting("Preset", 0, "Balanced", "Max FPS", "Potato"));
    private final BoolSetting uncap = add(new BoolSetting("Uncap FPS", true));
    private final BoolSetting vsyncOff = add(new BoolSetting("Disable VSync", true));
    private final ChoiceSetting particles = add(new ChoiceSetting("Particles", 1, "Keep", "Decreased", "Minimal"));
    private final ChoiceSetting clouds = add(new ChoiceSetting("Clouds", 0, "Keep", "Fast", "Off"));
    private final BoolSetting shadowsOff = add(new BoolSetting("Disable entity shadows", true));
    private final SliderSetting entityDistance = add(new SliderSetting("Entity distance", 50, 100, 25, 100, "%"));
    private final SliderSetting biomeBlend = add(new SliderSetting("Max biome blend", 0, 7, 1, 1, ""));
    private final SliderSetting renderCap = add(new SliderSetting("Max render distance", 2, 32, 1, 16, ""));

    private final List<Slot> slots = new ArrayList<>();
    private boolean active;
    private boolean broken;
    private int appliedSignature;

    public FpsBoostModule() {
        super("fpsboost", "FPS Boost", "Lighter video settings for more FPS.",
                Category.UTILITY, false, 0f, 0f, false);
        preset.onChange(this::applyPreset);

        slots.add(new Slot("framerate", Options::framerateLimit, this::framerateRule));
        slots.add(new Slot("vsync", Options::enableVsync, this::vsyncRule));
        slots.add(new Slot("particles", Options::particles, this::particleRule));
        slots.add(new Slot("clouds", Options::cloudStatus, this::cloudRule));
        slots.add(new Slot("shadows", Options::entityShadows, this::shadowRule));
        slots.add(new Slot("entityDistance", Options::entityDistanceScaling, this::entityRule));
        slots.add(new Slot("biomeBlend", Options::biomeBlendRadius, this::blendRule));
        slots.add(new Slot("renderDistance", Options::renderDistance, this::renderRule));
    }

    // ------------------------------------------------------------------ presets

    private void applyPreset() {
        switch (preset.index) {
            case 1 -> load(2, 2, 75, 0, 10);    // Max FPS
            case 2 -> load(2, 2, 50, 0, 6);     // Potato
            default -> load(1, 0, 100, 1, 16);  // Balanced
        }
    }

    private void load(int particleMode, int cloudMode, double entities, double blend, double render) {
        uncap.value = true;
        vsyncOff.value = true;
        shadowsOff.value = true;
        particles.index = particleMode;
        clouds.index = cloudMode;
        entityDistance.set(entities);
        biomeBlend.set(blend);
        renderCap.set(render);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        broken = false;
    }

    @Override
    public void tick(Minecraft mc) {
        Options o = mc.options;
        if (o == null || broken) return;
        try {
            int sig = signature();
            if (!active) {
                recoverStale(o);
                applyAll(o);
                active = true;
                appliedSignature = sig;
            } else if (sig != appliedSignature) {
                // A setting of this module changed: undo what we did, then apply the new values.
                restoreAll(o);
                applyAll(o);
                appliedSignature = sig;
            }
        } catch (RuntimeException e) {
            broken = true;
            PulseClient.LOG.warn("FPS Boost turned itself off after an error", e);
        }
    }

    @Override
    public void onDisable() {
        if (!active) return;
        active = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options != null && restoreAll(mc.options)) mc.options.save();
        } catch (RuntimeException e) {
            PulseClient.LOG.warn("FPS Boost could not restore your settings", e);
        }
    }

    /** Packs every value that drives the rules into one int, so a change is noticed in a single compare. */
    private int signature() {
        int s = 0;
        s = (s << 1) | (uncap.value ? 1 : 0);
        s = (s << 1) | (vsyncOff.value ? 1 : 0);
        s = (s << 1) | (shadowsOff.value ? 1 : 0);
        s = (s << 2) | particles.index;
        s = (s << 2) | clouds.index;
        s = (s << 7) | (int) Math.round(entityDistance.value);
        s = (s << 3) | (int) Math.round(biomeBlend.value);
        s = (s << 6) | (int) Math.round(renderCap.value);
        return s;
    }

    // ------------------------------------------------------------------ apply / restore

    private void applyAll(Options o) {
        for (Slot s : slots) {
            try {
                OptionInstance<?> opt = s.option.apply(o);
                Object cur = opt.get();
                Object target = s.rule.apply(cur);
                if (target == null || target.equals(cur)) continue;
                put(opt, target);
                Object now = opt.get();
                if (!now.equals(cur)) {
                    s.original = cur;
                    s.applied = now;
                }
            } catch (RuntimeException e) {
                PulseClient.LOG.warn("FPS Boost could not change {}", s.key, e);
            }
        }
        saveBackup();
    }

    /** Puts back every value we changed, unless the player has changed it since. Returns true if anything was put back. */
    private boolean restoreAll(Options o) {
        boolean changed = false;
        for (Slot s : slots) {
            if (s.applied == null) continue;
            try {
                OptionInstance<?> opt = s.option.apply(o);
                if (s.applied.equals(opt.get())) {
                    put(opt, s.original);
                    changed = true;
                }
            } catch (RuntimeException e) {
                PulseClient.LOG.warn("FPS Boost could not restore {}", s.key, e);
            }
            s.original = null;
            s.applied = null;
        }
        deleteBackup();
        return changed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(OptionInstance<?> opt, Object value) {
        ((OptionInstance) opt).set(value);
    }

    // ------------------------------------------------------------------ rules (null = leave it alone)

    private Object framerateRule(Object cur) {
        return uncap.value && (Integer) cur < UNLIMITED_FPS ? Integer.valueOf(UNLIMITED_FPS) : null;
    }

    private Object vsyncRule(Object cur) {
        return vsyncOff.value && Boolean.TRUE.equals(cur) ? Boolean.FALSE : null;
    }

    private Object shadowRule(Object cur) {
        return shadowsOff.value && Boolean.TRUE.equals(cur) ? Boolean.FALSE : null;
    }

    private Object renderRule(Object cur) {
        int cap = (int) Math.round(renderCap.value);
        return (Integer) cur > cap ? Integer.valueOf(cap) : null;
    }

    private Object blendRule(Object cur) {
        int cap = (int) Math.round(biomeBlend.value);
        return (Integer) cur > cap ? Integer.valueOf(cap) : null;
    }

    private Object entityRule(Object cur) {
        double cap = entityDistance.value / 100.0;
        return (Double) cur > cap + 1e-6 ? Double.valueOf(cap) : null;
    }

    // Particles and clouds are enums. They are matched by constant name so this file does not depend on
    // which package the enum lives in, and so a missing constant just means "leave it alone".

    private Object particleRule(Object cur) {
        String name = enumName(cur);
        return switch (particles.index) {
            case 1 -> "ALL".equals(name) ? enumValue(cur, "DECREASED") : null;
            case 2 -> !"MINIMAL".equals(name) ? enumValue(cur, "MINIMAL") : null;
            default -> null;
        };
    }

    private Object cloudRule(Object cur) {
        String name = enumName(cur);
        return switch (clouds.index) {
            case 1 -> "FANCY".equals(name) ? enumValue(cur, "FAST") : null;
            case 2 -> !"OFF".equals(name) ? enumValue(cur, "OFF") : null;
            default -> null;
        };
    }

    private static String enumName(Object v) {
        return v instanceof Enum<?> e ? e.name() : null;
    }

    private static Object enumValue(Object like, String name) {
        if (!(like instanceof Enum<?> e)) return null;
        for (Object c : e.getDeclaringClass().getEnumConstants()) {
            if (((Enum<?>) c).name().equals(name)) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ crash-safe backup

    private static Path backupPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-fpsboost.json");
    }

    private static String text(Object v) {
        return v instanceof Enum<?> e ? e.name() : String.valueOf(v);
    }

    private static Object parse(Object like, String s) {
        if (like instanceof Integer) return Integer.valueOf(s);
        if (like instanceof Boolean) return Boolean.valueOf(s);
        if (like instanceof Double) return Double.valueOf(s);
        return enumValue(like, s);
    }

    private void saveBackup() {
        try {
            JsonObject root = new JsonObject();
            for (Slot s : slots) {
                if (s.applied == null) continue;
                JsonObject e = new JsonObject();
                e.addProperty("original", text(s.original));
                e.addProperty("applied", text(s.applied));
                root.add(s.key, e);
            }
            if (root.entrySet().isEmpty()) {
                deleteBackup();
            } else {
                Files.writeString(backupPath(), root.toString());
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("FPS Boost could not write its backup", e);
        }
    }

    private static void deleteBackup() {
        try {
            Files.deleteIfExists(backupPath());
        } catch (Exception ignored) {
        }
    }

    /**
     * A backup that is still on disk at startup means the last session ended without restoring (crash, killed
     * process). Put back every value that is still exactly what we set, so the next apply starts from your real settings.
     */
    private void recoverStale(Options o) {
        Path p = backupPath();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (Slot s : slots) {
                if (!root.has(s.key)) continue;
                JsonObject e = root.getAsJsonObject(s.key);
                OptionInstance<?> opt = s.option.apply(o);
                Object cur = opt.get();
                Object applied = parse(cur, e.get("applied").getAsString());
                Object original = parse(cur, e.get("original").getAsString());
                if (applied != null && original != null && applied.equals(cur)) put(opt, original);
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("FPS Boost could not read its backup", e);
        }
        deleteBackup();
    }

    /** One vanilla option we manage, with the value it had before (original) and the one we set (applied). */
    private static final class Slot {
        final String key;
        final Function<Options, OptionInstance<?>> option;
        final Function<Object, Object> rule;
        Object original, applied;

        Slot(String key, Function<Options, OptionInstance<?>> option, Function<Object, Object> rule) {
            this.key = key;
            this.option = option;
            this.rule = rule;
        }
    }
}
