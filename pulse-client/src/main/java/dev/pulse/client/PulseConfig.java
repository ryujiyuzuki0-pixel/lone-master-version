package dev.pulse.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.Setting;
import dev.pulse.client.spotify.SpotifyClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Saves every module's on/off state, position, favorite flag and settings. Several named profiles
 * are supported: "default" lives in config/pulse.json, the others in config/pulse-profile-NAME.json.
 */
public final class PulseConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static String profile = "default";

    private PulseConfig() {}

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static Path path(String name) {
        return name.equals("default") ? dir().resolve("pulse.json") : dir().resolve("pulse-profile-" + name + ".json");
    }

    private static Path activeFile() {
        return dir().resolve("pulse-profile.txt");
    }

    public static String profile() {
        return profile;
    }

    /** Reads the active profile name, then loads it. */
    public static void init() {
        try {
            Path a = activeFile();
            if (Files.exists(a)) {
                String n = Files.readString(a).trim();
                if (n.equals("default") || Files.exists(path(n))) profile = n;
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read the active profile", e);
        }
        load();
    }

    public static List<String> profiles() {
        List<String> out = new ArrayList<>();
        out.add("default");
        try (Stream<Path> s = Files.list(dir())) {
            s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("pulse-profile-") && n.endsWith(".json"))
                    .map(n -> n.substring("pulse-profile-".length(), n.length() - ".json".length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_\\-]{1,16}") && !n.equalsIgnoreCase("default");
    }

    /** Creates a profile as a copy of the current settings and switches to it. */
    public static boolean createProfile(String name) {
        if (!validName(name) || profiles().contains(name)) return false;
        save();
        profile = name;
        writeActive();
        save();
        return true;
    }

    public static void switchProfile(String name) {
        if (name.equals(profile) || !profiles().contains(name)) return;
        save();
        profile = name;
        writeActive();
        for (Module m : ModuleManager.all()) m.resetAll();
        load();
    }

    public static boolean deleteProfile(String name) {
        if (name.equals("default")) return false;
        try {
            if (name.equals(profile)) switchProfile("default");
            Files.deleteIfExists(path(name));
            return true;
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not delete profile", e);
            return false;
        }
    }

    private static void writeActive() {
        try {
            Files.writeString(activeFile(), profile);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save the active profile", e);
        }
    }

    public static void load() {
        Path p = path(profile);
        if (!Files.exists(p)) return;
        JsonObject root;
        try (Reader r = Files.newBufferedReader(p)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read " + p.getFileName() + ", using defaults (the damaged file is kept as .bak)", e);
            try {
                Files.copy(p, p.resolveSibling(p.getFileName() + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            if (root.has("spotifyClientId")) SpotifyClient.clientId = root.get("spotifyClientId").getAsString();
        } catch (Exception ignored) {
        }
        JsonObject mods = root.has("modules") && root.get("modules").isJsonObject() ? root.getAsJsonObject("modules") : null;
        if (mods == null) return;
        // One damaged entry must not stop the other modules from loading.
        for (Module m : ModuleManager.all()) {
            try {
                if (!mods.has(m.id) || !mods.get(m.id).isJsonObject()) continue;
                JsonObject o = mods.getAsJsonObject(m.id);
                if (o.has("enabled")) m.setEnabled(o.get("enabled").getAsBoolean());
                if (o.has("favorite")) m.favorite = o.get("favorite").getAsBoolean();
                if (o.has("x")) m.fx = clamp(o.get("x").getAsFloat());
                if (o.has("y")) m.fy = clamp(o.get("y").getAsFloat());
                if (o.has("settings") && o.get("settings").isJsonObject()) {
                    JsonObject so = o.getAsJsonObject("settings");
                    for (Setting s : m.settings) {
                        if (s.persist && so.has(s.name)) {
                            try {
                                s.fromJson(so.get(s.name));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                m.syncPosition();
            } catch (Exception e) {
                PulseClient.LOG.warn("Skipping damaged settings of module " + m.id, e);
            }
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("spotifyClientId", SpotifyClient.clientId == null ? "" : SpotifyClient.clientId);
            JsonObject mods = new JsonObject();
            for (Module m : ModuleManager.all()) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", m.enabled);
                o.addProperty("favorite", m.favorite);
                if (m.hud) {
                    o.addProperty("x", Math.round(m.fx * 10000f) / 10000f);
                    o.addProperty("y", Math.round(m.fy * 10000f) / 10000f);
                }
                JsonObject so = new JsonObject();
                for (Setting s : m.settings) if (s.persist) so.add(s.name, s.toJson());
                o.add("settings", so);
                mods.add(m.id, o);
            }
            root.add("modules", mods);
            // Write to a temp file and move it over the real one, so a crash mid-write can never leave a broken config.
            Path target = path(profile);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save " + path(profile).getFileName(), e);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
