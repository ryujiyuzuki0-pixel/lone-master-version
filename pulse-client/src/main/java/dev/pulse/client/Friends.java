package dev.pulse.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A simple local friends list (config/pulse-friends.json). */
public final class Friends {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> NAMES = new ArrayList<>();

    private Friends() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-friends.json");
    }

    public static void load() {
        NAMES.clear();
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            List<String> in = GSON.fromJson(r, new TypeToken<List<String>>() {}.getType());
            if (in != null) NAMES.addAll(in);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read pulse-friends.json", e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(NAMES));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-friends.json", e);
        }
    }

    public static List<String> all() {
        return NAMES;
    }

    public static boolean add(String name) {
        name = name.trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return false;
        for (String n : NAMES) if (n.equalsIgnoreCase(name)) return false;
        NAMES.add(name);
        save();
        return true;
    }

    public static void remove(String name) {
        NAMES.removeIf(n -> n.equalsIgnoreCase(name));
        save();
    }
}
