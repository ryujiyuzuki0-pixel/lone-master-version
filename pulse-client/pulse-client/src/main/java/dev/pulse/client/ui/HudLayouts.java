package dev.pulse.client.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.PulseConfig;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.Setting;
import dev.pulse.client.module.setting.SliderSetting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Named HUD layouts: where every HUD element sits, whether it is shown, its scale, background and text colour.
 * Saved to config/lone-hud-layouts.json, separate from config profiles so a layout can be swapped without
 * touching any other setting.
 */
public final class HudLayouts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, JsonObject> LAYOUTS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static boolean loaded;

    private HudLayouts() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("lone-hud-layouts.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (String name : root.keySet()) if (root.get(name).isJsonObject()) LAYOUTS.put(name, root.getAsJsonObject(name));
        } catch (IOException | RuntimeException e) {
            PulseClient.LOG.warn("Could not read {}: {}", f, e.toString());
        }
    }

    private static synchronized void write() {
        JsonObject root = new JsonObject();
        LAYOUTS.forEach(root::add);
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PulseClient.LOG.warn("Could not save {}: {}", f, e.toString());
        }
    }

    public static List<String> names() {
        ensureLoaded();
        return new ArrayList<>(LAYOUTS.keySet());
    }

    public static boolean validName(String n) {
        return n != null && n.trim().length() >= 1 && n.trim().length() <= 24;
    }

    // ------------------------------------------------------------------ setting lookup shared with the editor

    public static <T extends Setting> T find(Module m, String name, Class<T> type) {
        for (Setting s : m.settings) if (s.name.equals(name) && type.isInstance(s)) return type.cast(s);
        return null;
    }

    // ------------------------------------------------------------------ save / load / delete

    public static void save(String name) {
        ensureLoaded();
        JsonObject layout = new JsonObject();
        for (Module m : ModuleManager.all()) {
            if (!m.hud || m.hidden) continue;
            JsonObject e = new JsonObject();
            e.addProperty("x", m.fx);
            e.addProperty("y", m.fy);
            e.addProperty("on", m.enabled);
            SliderSetting scale = find(m, "Scale", SliderSetting.class);
            if (scale != null) e.addProperty("scale", scale.value);
            BoolSetting bg = find(m, "Background", BoolSetting.class);
            if (bg != null) e.addProperty("bg", bg.value);
            ColorSetting text = find(m, "Text color", ColorSetting.class);
            if (text != null && text.mode == 0) e.addProperty("text", text.rgb);
            layout.add(m.id, e);
        }
        LAYOUTS.put(name.trim(), layout);
        write();
    }

    /** Applies a saved layout. Elements the layout does not mention are left as they are. */
    public static boolean load(String name) {
        ensureLoaded();
        JsonObject layout = LAYOUTS.get(name);
        if (layout == null) return false;
        for (Module m : ModuleManager.all()) {
            if (!m.hud || !layout.has(m.id) || !layout.get(m.id).isJsonObject()) continue;
            JsonObject e = layout.getAsJsonObject(m.id);
            try {
                if (e.has("x")) m.fx = e.get("x").getAsFloat();
                if (e.has("y")) m.fy = e.get("y").getAsFloat();
                m.syncPosition();
                if (e.has("on")) m.setEnabled(e.get("on").getAsBoolean());
                SliderSetting scale = find(m, "Scale", SliderSetting.class);
                if (scale != null && e.has("scale")) scale.set(e.get("scale").getAsDouble());
                BoolSetting bg = find(m, "Background", BoolSetting.class);
                if (bg != null && e.has("bg")) bg.value = e.get("bg").getAsBoolean();
                ColorSetting text = find(m, "Text color", ColorSetting.class);
                if (text != null && e.has("text")) {
                    text.mode = 0;
                    text.setRgb(e.get("text").getAsInt());
                }
            } catch (RuntimeException ex) {
                PulseClient.LOG.warn("Skipped a bad entry for {} in layout {}", m.id, name);
            }
        }
        PulseConfig.save();
        return true;
    }

    public static void delete(String name) {
        ensureLoaded();
        if (LAYOUTS.remove(name) != null) write();
    }
}
