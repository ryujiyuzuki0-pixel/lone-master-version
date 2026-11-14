package dev.pulse.client.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** One pack as the PacksMC API describes it (list entries fill the summary fields, the detail call fills the rest). */
public record PackInfo(String id, String slug, String name, String description, String resolution,
                       List<String> mcVersions, List<String> availableVersions, String thumbnailUrl,
                       List<String> gallery, long downloads, long likes, long sizeBytes,
                       String licenseLabel, boolean requiresCredit, String author, boolean authorVerified,
                       String downloadUrl, boolean exclusive, int accent) {

    static PackInfo parse(JsonObject o) {
        JsonObject lic = obj(o, "license");
        JsonObject au = obj(o, "author");
        JsonObject col = obj(o, "colors");
        String licenseCode = str(lic, "code");
        String licenseLabel = str(lic, "label");
        if (licenseLabel.isEmpty()) licenseLabel = licenseCode.replace('_', ' ').toLowerCase();
        List<String> avail = new ArrayList<>();
        if (o.has("versions") && o.get("versions").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("versions")) {
                if (e.isJsonObject()) {
                    String v = str(e.getAsJsonObject(), "mc_version");
                    if (!v.isEmpty()) avail.add(v);
                }
            }
        }
        return new PackInfo(
                str(o, "id"), str(o, "slug"), str(o, "name"), str(o, "description"), str(o, "resolution"),
                strList(o, "mc_versions"), avail, str(o, "thumbnail_url"), strList(o, "gallery"),
                num(o, "downloads"), num(o, "likes"), num(o, "file_size_bytes"),
                licenseLabel, bool(lic, "requires_credit"), str(au, "username"), bool(au, "verified"),
                str(o, "download_url"), bool(o, "is_exclusive"), hex(str(col, "accent")));
    }

    /** The name shown to the player, never empty. */
    public String title() {
        return name.isBlank() ? slug : name;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k)) return "";
        JsonElement e = o.get(k);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static List<String> strList(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(k);
            for (JsonElement e : a) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static int hex(String s) {
        if (s == null || !s.matches("#?[0-9a-fA-F]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
    }
}
