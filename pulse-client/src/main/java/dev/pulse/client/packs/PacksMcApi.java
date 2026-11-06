package dev.pulse.client.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Read-only client for the official PacksMC catalog API (https://packsmc.com/docs).
 *
 * The API only returns metadata and a {@code download_url} that points at the pack's page on packsmc.com.
 * It never issues file URLs, so this class never downloads pack files: downloads always go through the site
 * (that is how creator credit, link lockers and Packs+ gates keep working).
 */
public final class PacksMcApi {
    public static final String SITE = "https://packsmc.com";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static volatile String apiKey = "";

    public record PackInfo(String id, String slug, String name, String resolution, List<String> versions,
                           List<String> gamemodes, long downloads, long likes, long sizeBytes, String author,
                           boolean verified, String license, boolean requiresCredit, boolean exclusive, String url) {}

    public record Page(List<PackInfo> packs, String nextCursor) {}

    public static final class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }
    }

    private PacksMcApi() {}

    // ------------------------------------------------------------------ key storage

    private static Path keyFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("lone-packsmc.txt");
    }

    public static void init() {
        try {
            Path f = keyFile();
            if (Files.exists(f)) apiKey = Files.readString(f).trim();
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read the PacksMC key file");
        }
    }

    public static boolean hasKey() {
        return !apiKey.isEmpty();
    }

    public static boolean validKeyFormat(String k) {
        return k != null && k.matches("pmc_[A-Za-z0-9_\\-]{8,200}");
    }

    public static boolean setKey(String k) {
        k = k == null ? "" : k.trim();
        if (!k.isEmpty() && !validKeyFormat(k)) return false;
        apiKey = k;
        try {
            if (k.isEmpty()) Files.deleteIfExists(keyFile());
            else Files.writeString(keyFile(), k);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save the PacksMC key file");
        }
        return true;
    }

    // ------------------------------------------------------------------ requests

    /** sort: recent | downloads | likes. cursor only applies to sort=recent. */
    public static CompletableFuture<Page> list(String query, String sort, String cursor) {
        StringBuilder sb = new StringBuilder(SITE + "/api/v1/packs?limit=40&sort=").append(enc(sort));
        if (query != null && query.trim().length() >= 2) sb.append("&q=").append(enc(query.trim()));
        if (cursor != null && !cursor.isEmpty() && sort.equals("recent")) sb.append("&cursor=").append(enc(cursor));
        return get(sb.toString()).thenApply(PacksMcApi::parsePage);
    }

    private static CompletableFuture<String> get(String url) {
        if (!hasKey()) return CompletableFuture.failedFuture(new ApiException("Add your PacksMC API key first."));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "LoneClient/" + PulseClient.shortVersion())
                .GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            int code = r.statusCode();
            if (code == 200) return r.body();
            String msg = errorMessage(r.body());
            if (code == 401) throw new ApiException("PacksMC rejected the API key. Check it or make a new one.");
            if (code == 429) {
                String wait = r.headers().firstValue("Retry-After").orElse("a bit");
                throw new ApiException("PacksMC rate limit reached. Try again in " + wait + (wait.matches("\\d+") ? "s." : "."));
            }
            throw new ApiException("PacksMC error " + code + (msg.isEmpty() ? "" : ": " + msg));
        });
    }

    public static String message(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof ApiException) return t.getMessage();
        return "Could not reach packsmc.com.";
    }

    // ------------------------------------------------------------------ parsing

    private static String errorMessage(String body) {
        try {
            JsonObject e = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            return str(e, "message");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Page parsePage(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        List<PackInfo> out = new ArrayList<>();
        JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();
        for (JsonElement el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonObject author = o.has("author") && o.get("author").isJsonObject() ? o.getAsJsonObject("author") : new JsonObject();
            JsonObject lic = o.has("license") && o.get("license").isJsonObject() ? o.getAsJsonObject("license") : new JsonObject();
            String slug = str(o, "slug");
            String url = str(o, "download_url");
            // Only ever open pages on packsmc.com, whatever the response says.
            if (!url.startsWith(SITE + "/")) url = slug.isEmpty() ? SITE + "/explore" : SITE + "/pack/" + slug;
            String licenseName = str(lic, "label");
            if (licenseName.isEmpty()) licenseName = str(lic, "code");
            out.add(new PackInfo(str(o, "id"), slug, str(o, "name"), str(o, "resolution"), strings(o, "mc_versions"),
                    strings(o, "gamemodes"), num(o, "downloads"), num(o, "likes"), num(o, "file_size_bytes"),
                    str(author, "username"), bool(author, "verified"), licenseName, bool(lic, "requires_credit"),
                    bool(o, "is_exclusive"), url));
        }
        String next = root.has("next_cursor") && !root.get("next_cursor").isJsonNull() ? root.get("next_cursor").getAsString() : null;
        return new Page(out, next);
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
