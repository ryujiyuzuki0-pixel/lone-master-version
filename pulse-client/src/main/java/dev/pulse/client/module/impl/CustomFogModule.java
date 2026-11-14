package dev.pulse.client.module.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.KeyNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector4f;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Replaces the world fog distance, thickness and color.
 * <p>
 * The values are applied where the game writes its fog uniform buffer (see FogRendererMixin). Each frame the
 * vanilla numbers are blended toward the custom ones, so switching dimensions, presets or turning the module on
 * or off fades instead of snapping. Water, lava, blindness and darkness fog are left alone by default.
 * <p>
 * Distance is the fog end in blocks (0 = your render distance). Density sets where the fog starts:
 * start = end x (1 - density), so 0% is a thin wall at the end distance and 100% is fog right in front of you.
 */
public final class CustomFogModule extends Module {
    private static CustomFogModule instance;
    /** Counts calls from the mixin; stays 0 when the hook did not apply on this Minecraft version. */
    public static volatile long hookHits;

    /** Distance, density and color of one dimension (or of the global set). */
    private static final class Dim {
        SliderSetting dist, dens;
        BoolSetting useColor;
        ColorSetting color;
    }

    private final ChoiceSetting preset = add(new ChoiceSetting("Preset", 0,
            "Custom", "Crisp", "Haze", "Thick fog", "Dusk", "Deep blue"), "General");
    private final BoolSetting perDim = add(new BoolSetting("Per-dimension settings", false), "General");
    private final BoolSetting keepSpecial = add(new BoolSetting("Keep water / lava / blindness fog", true), "General");
    private final SliderSetting transition = add(new SliderSetting("Transition time", 0, 5, 0.25, 1.0, "s").zero("Instant"), "General");

    private final Dim global = dim("Fog", "Global", () -> !perDim.value);
    private final Dim overworld = dim("Overworld", "Overworld", () -> perDim.value);
    private final Dim nether = dim("Nether", "Nether", () -> perDim.value);
    private final Dim end = dim("End", "The End", () -> perDim.value);
    private final List<Dim> dims = List.of(global, overworld, nether, end);

    private final ChoiceSetting slot = add(new ChoiceSetting("User slot", 0, "Slot 1", "Slot 2", "Slot 3"), "My presets");
    private final ActionSetting save = add(new ActionSetting("Save current look to slot", "Save", this::saveSlot), "My presets");
    private final ActionSetting load = add(new ActionSetting("Load slot", "Load", this::loadSlot), "My presets");

    // current (smoothed) values, written from the render thread, targets from the tick
    private float wFog, wColor;
    private float curEnd = 128f, curDens = 0.25f;
    private final float[] curRgb = {0.7f, 0.77f, 0.84f};
    private float tFog, tColor, tEnd = 128f, tDens = 0.25f;
    private final float[] tRgb = {0.7f, 0.77f, 0.84f};
    private float baseline = 128f;
    private long lastStep = System.nanoTime();

    private Object lastLevel;
    private int dimIndex;
    private int ticksSeen;
    private boolean warned;

    public CustomFogModule() {
        super("customfog", "Custom Fog", "Your own fog distance, density and color, per dimension.",
                Category.VISUAL, false, 0f, 0f, false);
        preset.onChange(this::applyPreset);
        instance = this;
    }

    private Dim dim(String prefix, String group, BooleanSupplier visible) {
        String p = prefix.equals("Fog") ? "Fog " : prefix + " ";
        Dim d = new Dim();
        d.dist = add(new SliderSetting(p + "distance", 0, 512, 8, 0, " blocks").zero("Render distance"), group, visible);
        d.dens = add(new SliderSetting(p + "density", 0, 100, 5, 25, "%"), group, visible);
        d.useColor = add(new BoolSetting(prefix.equals("Fog") ? "Custom color" : prefix + " custom color", false), group, visible);
        d.color = add(new ColorSetting(prefix.equals("Fog") ? "Color" : prefix + " color", 0xFFB8C4D6, false, false),
                group, () -> visible.getAsBoolean() && d.useColor.value);
        return d;
    }

    // ------------------------------------------------------------------ presets

    private void applyPreset() {
        switch (preset.index) {
            case 1 -> loadAll(0, 8, false, 0);          // Crisp
            case 2 -> loadAll(0, 40, true, 0xC9D3DF);   // Haze
            case 3 -> loadAll(96, 70, true, 0x9AA3AD);  // Thick fog
            case 4 -> loadAll(0, 35, true, 0xC7849B);   // Dusk
            case 5 -> loadAll(80, 60, true, 0x1B4F72);  // Deep blue
            default -> { }
        }
    }

    private void loadAll(double dist, double dens, boolean useColor, int rgb) {
        for (Dim d : dims) {
            d.dist.set(dist);
            d.dens.set(dens);
            d.useColor.value = useColor;
            if (useColor) {
                d.color.mode = 0;
                d.color.setRgb(rgb);
            }
        }
    }

    private static Path slotFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-fogpresets.json");
    }

    private JsonObject readSlots() {
        try (Reader r = Files.newBufferedReader(slotFile())) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private void saveSlot() {
        JsonObject all = readSlots();
        JsonObject o = new JsonObject();
        Dim d = perDim.value ? currentDim() : global;
        o.addProperty("dist", d.dist.value);
        o.addProperty("dens", d.dens.value);
        o.addProperty("useColor", d.useColor.value);
        o.addProperty("rgb", d.color.rgb);
        all.add(String.valueOf(slot.index), o);
        try {
            Files.writeString(slotFile(), all.toString());
            say(slot.get() + " saved.");
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-fogpresets.json", e);
        }
    }

    private void loadSlot() {
        JsonObject all = readSlots();
        String key = String.valueOf(slot.index);
        if (!all.has(key)) {
            say(slot.get() + " is empty.");
            return;
        }
        try {
            JsonObject o = all.getAsJsonObject(key);
            loadAll(o.get("dist").getAsDouble(), o.get("dens").getAsDouble(),
                    o.get("useColor").getAsBoolean(), o.get("rgb").getAsInt());
            say(slot.get() + " loaded.");
        } catch (Exception e) {
            say(slot.get() + " could not be read.");
        }
    }

    private static void say(String s) {
        Player p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal("Custom Fog: " + s), true);
    }

    // ------------------------------------------------------------------ targets (once per tick)

    private Dim currentDim() {
        return switch (dimIndex) {
            case 1 -> nether;
            case 2 -> end;
            default -> overworld;
        };
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            tFog = 0;
            tColor = 0;
            return;
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            String id = KeyNames.id(mc.level.dimension());
            dimIndex = id.contains("nether") ? 1 : id.contains("the_end") ? 2 : 0;
        }
        baseline = Math.max(32f, mc.options.renderDistance().get() * 16f);
        Player p = mc.player;
        boolean special = keepSpecial.value && (p.isUnderWater() || p.isInLava()
                || p.hasEffect(MobEffects.BLINDNESS) || p.hasEffect(MobEffects.DARKNESS));
        Dim d = perDim.value ? currentDim() : global;
        tFog = special ? 0f : 1f;
        tColor = special || !d.useColor.value ? 0f : 1f;
        tEnd = d.dist.value > 0 ? (float) d.dist.value : baseline;
        tDens = (float) (d.dens.value / 100.0);
        int rgb = d.color.color();
        tRgb[0] = ((rgb >> 16) & 0xFF) / 255f;
        tRgb[1] = ((rgb >> 8) & 0xFF) / 255f;
        tRgb[2] = (rgb & 0xFF) / 255f;

        // Hook watchdog: if the game never called the fog hook, say so once instead of failing silently.
        if (!warned && ++ticksSeen == 400 && hookHits == 0) {
            warned = true;
            PulseClient.LOG.warn("Custom Fog: the fog hook did not apply on this Minecraft version.");
            say("not supported on this Minecraft version yet.");
        }
    }

    @Override
    public void onDisable() {
        tFog = 0;
        tColor = 0;
    }

    // ------------------------------------------------------------------ per-frame blend (called from the mixin)

    private boolean live() {
        return enabled || wFog > 0.001f || wColor > 0.001f;
    }

    /** Moves the current values toward the targets. Cheap and safe to call several times per frame. */
    private void step() {
        long now = System.nanoTime();
        if (now - lastStep < 500_000L) return;
        float dt = Math.min(0.25f, (now - lastStep) / 1_000_000_000f);
        lastStep = now;
        float tau = (float) transition.value / 3f;
        float k = tau <= 0f ? 1f : 1f - (float) Math.exp(-dt / tau);
        wFog += (tFog - wFog) * k;
        wColor += (tColor - wColor) * k;
        curEnd += (tEnd - curEnd) * k;
        curDens += (tDens - curDens) * k;
        for (int i = 0; i < 3; i++) curRgb[i] += (tRgb[i] - curRgb[i]) * k;
    }

    private float customStart() {
        return Math.max(0f, curEnd * (1f - curDens));
    }

    /** Values this large are the game's "no fog" buffer; those are never touched. */
    private static boolean real(float v) {
        return Float.isFinite(v) && Math.abs(v) < 1.0e6f;
    }

    public static float fogStart(float v) {
        hookHits++;
        CustomFogModule m = instance;
        if (m == null || !real(v)) return v;
        m.step();
        return m.live() ? v + (m.customStart() - v) * m.wFog : v;
    }

    public static float fogEnd(float v) {
        hookHits++;
        CustomFogModule m = instance;
        if (m == null || !real(v)) return v;
        m.step();
        return m.live() ? v + (m.curEnd - v) * m.wFog : v;
    }

    public static Vector4f fogColor(Vector4f v) {
        CustomFogModule m = instance;
        if (m == null || !m.live() || m.wColor < 0.001f) return v;
        m.step();
        float w = m.wColor;
        return new Vector4f(v.x + (m.curRgb[0] - v.x) * w, v.y + (m.curRgb[1] - v.y) * w,
                v.z + (m.curRgb[2] - v.z) * w, v.w);
    }

    /** For the status line in logs / commands. */
    public List<String> debug() {
        List<String> out = new ArrayList<>();
        out.add("hookHits=" + hookHits + " wFog=" + wFog + " end=" + curEnd);
        return out;
    }
}
