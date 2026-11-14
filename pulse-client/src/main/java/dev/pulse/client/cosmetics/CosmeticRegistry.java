package dev.pulse.client.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every cosmetic the client knows about, read once from assets/pulse/cosmetics/registry.json.
 * <p>
 * Adding a cosmetic needs no Java: put a 64x32 PNG in textures/cape (or textures/elytra) and add a row, or add a
 * trail/aura row that names one of the supported particles. A row that is incomplete or unknown is skipped with a
 * log line instead of breaking the menu.
 * <pre>
 * { "id": "dune", "name": "Dune", "category": "cape", "rarity": "common", "asset": "dune",
 *   "desc": "...", "face": "&lt;160 pixels as RRGGBB hex, 960 chars&gt;" }
 * { "id": "trail_ember", "name": "Ember Trail", "category": "trail", "rarity": "rare",
 *   "particle": "flame", "color": "FF8A2B", "desc": "..." }
 * </pre>
 */
public final class CosmeticRegistry {
    private static final String RESOURCE = "/assets/pulse/cosmetics/registry.json";
    private static final Map<String, Cosmetic> BY_ID = new LinkedHashMap<>();
    private static final Map<CosmeticCategory, List<Cosmetic>> BY_CATEGORY = new EnumMap<>(CosmeticCategory.class);
    private static boolean loaded;

    private CosmeticRegistry() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (CosmeticCategory c : CosmeticCategory.values()) BY_CATEGORY.put(c, new ArrayList<>());
        try (InputStream in = CosmeticRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                PulseClient.LOG.error("Cosmetics registry missing: {}", RESOURCE);
                return;
            }
            JsonArray items = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("items");
            for (JsonElement e : items) {
                try {
                    Cosmetic c = parse(e.getAsJsonObject());
                    if (BY_ID.putIfAbsent(c.id(), c) != null) {
                        PulseClient.LOG.warn("Duplicate cosmetic id '{}' ignored", c.id());
                    } else {
                        BY_CATEGORY.get(c.category()).add(c);
                    }
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    PulseClient.LOG.warn("Skipping cosmetic: {}", ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException ex) {
            PulseClient.LOG.error("Could not read cosmetics registry", ex);
        }
        PulseClient.LOG.info("Loaded {} cosmetics", BY_ID.size());
    }

    private static Cosmetic parse(JsonObject o) {
        String id = str(o, "id"), name = str(o, "name");
        if (id.isEmpty() || !id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("bad id '" + id + "'");
        if (name.isEmpty()) throw new IllegalArgumentException(id + ": missing name");
        CosmeticCategory cat = CosmeticCategory.parse(str(o, "category"));
        if (cat == null) throw new IllegalArgumentException(id + ": unknown category");
        String asset = null, particle = null;
        int[] face = null;
        int color = 0xFFFFFFFF;
        String c = str(o, "color");
        if (c.matches("[0-9a-fA-F]{6}")) color = 0xFF000000 | Integer.parseInt(c, 16);
        if (cat.textured()) {
            asset = str(o, "asset");
            if (!asset.matches("[a-z0-9_]+")) throw new IllegalArgumentException(id + ": bad asset");
            face = decodeFace(str(o, "face"), id);
        } else {
            particle = str(o, "particle");
            if (!CosmeticsManager.supportsParticle(particle)) throw new IllegalArgumentException(id + ": unsupported particle '" + particle + "'");
        }
        return new Cosmetic(id, name, cat, Cosmetic.rarityOf(str(o, "rarity")), asset, particle, color, str(o, "desc"), face);
    }

    private static int[] decodeFace(String hex, String id) {
        if (!hex.matches("[0-9a-fA-F]{960}")) throw new IllegalArgumentException(id + ": face must be 960 hex chars");
        int[] px = new int[160];
        for (int i = 0; i < 160; i++) px[i] = 0xFF000000 | Integer.parseInt(hex.substring(i * 6, i * 6 + 6), 16);
        return px;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString().trim() : "";
    }

    public static List<Cosmetic> list(CosmeticCategory c) {
        load();
        return Collections.unmodifiableList(BY_CATEGORY.get(c));
    }

    public static Cosmetic get(String id) {
        load();
        return id == null ? null : BY_ID.get(id);
    }

    public static int count() {
        load();
        return BY_ID.size();
    }
}
