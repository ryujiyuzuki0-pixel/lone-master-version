package dev.pulse.client.skin;

import dev.pulse.client.PulseClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Built-in skins (assets/pulse/textures/skins) plus the player's own PNGs in the "lone-skins" folder next to
 * the game's config. Also remembers which skin is selected and which one is worn locally in game.
 */
public final class SkinLibrary {
    public static final class Entry {
        public final String id, name;
        public final boolean builtin;
        public final Path file;
        SkinTex tex;
        boolean failed;

        Entry(String id, String name, boolean builtin, Path file) {
            this.id = id;
            this.name = name;
            this.builtin = builtin;
            this.file = file;
        }
    }

    private static final String[][] BUILTIN = {
            {"lone_blue", "Lone Blue"}, {"frost", "Frost"}, {"ember", "Ember"}, {"shadow", "Shadow"},
            {"forest", "Forest"}, {"rose", "Rose"}, {"gold", "Gold"}, {"midnight", "Midnight"}};

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static String selectedId = "builtin:lone_blue";
    private static String wornId = "";
    private static boolean slim;
    private static PlayerSkin.Patch patch;
    private static Entry patchFor;
    private static volatile String status = "";
    private static long customCounter;

    private SkinLibrary() {}

    // ------------------------------------------------------------------ setup / persistence

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve("lone-skins");
    }

    private static Path settingsFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-skin.properties");
    }

    public static void init() {
        try {
            Properties p = new Properties();
            Path f = settingsFile();
            if (Files.exists(f)) {
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                }
            }
            selectedId = p.getProperty("selected", selectedId);
            wornId = p.getProperty("worn", "");
            slim = Boolean.parseBoolean(p.getProperty("slim", "false"));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read pulse-skin.properties", e);
        }
        rescan();
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("selected", selectedId);
            p.setProperty("worn", wornId);
            p.setProperty("slim", String.valueOf(slim));
            try (var out = Files.newOutputStream(settingsFile())) {
                p.store(out, "Lone Client skin settings");
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-skin.properties", e);
        }
    }

    /** Rebuilds the list: built-in skins first, then every PNG in the skins folder. */
    public static void rescan() {
        ENTRIES.clear();
        for (String[] b : BUILTIN) ENTRIES.add(new Entry("builtin:" + b[0], b[1], true, null));
        try {
            Files.createDirectories(folder());
            try (Stream<Path> s = Files.list(folder())) {
                s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .sorted()
                        .forEach(p -> {
                            String n = p.getFileName().toString();
                            ENTRIES.add(new Entry("file:" + n, n.substring(0, n.length() - 4), false, p));
                        });
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read the lone-skins folder", e);
        }
        if (byId(selectedId) == null) selectedId = ENTRIES.get(0).id;
        if (!wornId.isEmpty() && byId(wornId) == null) wornId = "";
    }

    // ------------------------------------------------------------------ queries

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Entry byId(String id) {
        for (Entry e : ENTRIES) if (e.id.equals(id)) return e;
        return null;
    }

    public static Entry selected() {
        Entry e = byId(selectedId);
        return e != null ? e : ENTRIES.get(0);
    }

    public static void select(Entry e) {
        selectedId = e.id;
        save();
    }

    /** The skin currently worn locally in game (null = your account's own skin). */
    public static Entry worn() {
        return wornId.isEmpty() ? null : byId(wornId);
    }

    public static boolean slim() {
        return slim;
    }

    public static void setSlim(boolean s) {
        slim = s;
        save();
    }

    public static String status() {
        return status;
    }

    public static void setStatus(String s) {
        status = s;
    }

    // ------------------------------------------------------------------ actions

    public static void wear(Entry e) {
        wornId = e == null ? "" : e.id;
        patch = null;
        save();
    }

    public static byte[] bytes(Entry e) throws Exception {
        if (e.builtin) {
            String name = e.id.substring("builtin:".length());
            try (InputStream in = SkinLibrary.class.getResourceAsStream("/assets/pulse/textures/skins/" + name + ".png")) {
                if (in == null) throw new IllegalStateException("missing built-in skin " + name);
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(e.file);
    }

    /** Adds a PNG (copies it into the skins folder). Returns an error message, or null when it worked. */
    public static String addFromPath(String raw) {
        try {
            String s = raw.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            Path src = Path.of(s);
            if (!Files.isRegularFile(src)) return "File not found: " + s;
            if (!src.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) return "Skins must be .png files.";
            byte[] data = Files.readAllBytes(src);
            String problem = SkinUploader.validate(data);
            if (!problem.isEmpty()) return problem;
            Files.createDirectories(folder());
            String base = src.getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
            Path dest = folder().resolve(base);
            if (Files.exists(dest) && !Files.isSameFile(src, dest)) {
                String stem = base.substring(0, base.length() - 4);
                dest = folder().resolve(stem + "_" + (++customCounter) + ".png");
            }
            if (!Files.exists(dest) || !Files.isSameFile(src, dest)) Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            rescan();
            String id = "file:" + dest.getFileName();
            if (byId(id) != null) selectedId = id;
            save();
            return null;
        } catch (Exception e) {
            return "Could not add that skin (" + e.getClass().getSimpleName() + ").";
        }
    }

    public static void delete(Entry e) {
        if (e.builtin || e.file == null) return;
        try {
            Files.deleteIfExists(e.file);
        } catch (Exception ex) {
            PulseClient.LOG.warn("Could not delete skin", ex);
        }
        if (wornId.equals(e.id)) wornId = "";
        patch = null;
        rescan();
        save();
    }

    public static void openFolder() {
        try {
            Files.createDirectories(folder());
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String dir = folder().toAbsolutePath().toString();
            if (os.contains("win")) new ProcessBuilder("explorer.exe", dir).start();
            else if (os.contains("mac")) new ProcessBuilder("open", dir).start();
            else new ProcessBuilder("xdg-open", dir).start();
        } catch (Exception e) {
            status = "Could not open the folder. It is: " + folder().toAbsolutePath();
        }
    }

    /** Uploads the entry to the signed-in Minecraft account (so everyone sees it) and wears it locally. */
    public static void applyToAccount(Entry e) {
        final boolean wasSlim = slim;
        byte[] data;
        try {
            data = bytes(e);
        } catch (Exception ex) {
            status = "Could not read that skin file.";
            return;
        }
        String token = Minecraft.getInstance().getUser().getAccessToken();
        if (!wasSlim) wear(e);
        SkinUploader.upload(data, wasSlim, token, msg -> status = msg);
    }

    // ------------------------------------------------------------------ textures

    /** The texture for an entry (loads custom files on first use). Null if it can't be loaded. */
    public static SkinTex tex(Entry e) {
        if (e.tex != null || e.failed) return e.tex;
        try {
            if (e.builtin) {
                e.tex = SkinTex.of(PulseClient.ID, "textures/skins/" + e.id.substring("builtin:".length()) + ".png");
            } else {
                NativeImage img;
                try (InputStream in = Files.newInputStream(e.file)) {
                    img = NativeImage.read(in);
                }
                AbstractTexture dyn = makeDynamic(img);
                SkinTex t = SkinTex.of(PulseClient.ID, "custom_skin/" + Integer.toHexString(e.id.hashCode()) + "_" + (++customCounter));
                Minecraft.getInstance().getTextureManager().register(t.id, dyn);
                e.tex = t;
            }
        } catch (Throwable t) {
            PulseClient.LOG.warn("Could not load skin " + e.id, t);
            e.failed = true;
        }
        return e.tex;
    }

    /** DynamicTexture's constructor differs between Minecraft versions, so pick the one that takes a NativeImage. */
    @SuppressWarnings("unchecked")
    private static AbstractTexture makeDynamic(NativeImage img) throws Exception {
        for (Constructor<?> c : DynamicTexture.class.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            boolean hasImage = false, ok = true;
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == NativeImage.class) {
                    args[i] = img;
                    hasImage = true;
                } else if (Supplier.class.isAssignableFrom(types[i])) {
                    Supplier<String> label = () -> "lone_skin";
                    args[i] = label;
                } else {
                    ok = false;
                }
            }
            if (hasImage && ok) return (AbstractTexture) c.newInstance(args);
        }
        throw new IllegalStateException("No usable DynamicTexture constructor");
    }

    // ------------------------------------------------------------------ in-game override (called by the player mixin)

    public static PlayerSkin override(PlayerSkin base) {
        Entry e = worn();
        if (e == null) return base;
        SkinTex t = tex(e);
        if (t == null) return base;
        if (patch == null || patchFor != e) {
            patch = new PlayerSkin.Patch(Optional.of(new ClientAsset.ResourceTexture(t.id, t.id)),
                    Optional.empty(), Optional.empty(), Optional.empty());
            patchFor = e;
        }
        return base.with(patch);
    }
}
