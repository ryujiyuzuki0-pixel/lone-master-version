package dev.pulse.client.packs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Small persistent settings for the pack browser, kept in config/lone-packsmc.json:
 * the player's own PacksMC API key, the folder their browser downloads to, and whether a freshly installed pack is
 * switched on straight away. The key is never part of the source code; every player uses their own free key.
 */
public final class PacksSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;

    private static String apiKey = "";
    private static String downloadsDir = "";
    private static boolean autoActivate = true;

    private PacksSettings() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("lone-packsmc.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("apiKey")) apiKey = o.get("apiKey").getAsString().trim();
            if (o.has("downloadsDir")) downloadsDir = o.get("downloadsDir").getAsString().trim();
            if (o.has("autoActivate")) autoActivate = o.get("autoActivate").getAsBoolean();
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read {}: {}", f, e.toString());
        }
    }

    private static synchronized void save() {
        JsonObject o = new JsonObject();
        o.addProperty("apiKey", apiKey);
        o.addProperty("downloadsDir", downloadsDir);
        o.addProperty("autoActivate", autoActivate);
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PulseClient.LOG.warn("Could not save {}: {}", f, e.toString());
        }
    }

    public static String apiKey() {
        ensureLoaded();
        return apiKey;
    }

    public static void setApiKey(String key) {
        ensureLoaded();
        apiKey = key == null ? "" : key.trim();
        save();
    }

    public static boolean autoActivate() {
        ensureLoaded();
        return autoActivate;
    }

    public static void setAutoActivate(boolean b) {
        ensureLoaded();
        autoActivate = b;
        save();
    }

    /** The folder the player's browser saves to (default: ~/Downloads). */
    public static Path downloadsDir() {
        ensureLoaded();
        if (!downloadsDir.isBlank()) return Path.of(downloadsDir);
        return Path.of(System.getProperty("user.home", "."), "Downloads");
    }

    public static void setDownloadsDir(String dir) {
        ensureLoaded();
        downloadsDir = dir == null ? "" : dir.trim();
        save();
    }
}
