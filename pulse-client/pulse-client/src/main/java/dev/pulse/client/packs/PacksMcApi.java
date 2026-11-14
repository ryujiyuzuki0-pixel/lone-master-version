package dev.pulse.client.packs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client for the official PacksMC catalog API v1 (https://packsmc.com/docs).
 * <p>
 * What the API offers: list / search packs, pack details (screenshots, versions, license), and the pack's page URL.
 * What it deliberately does NOT offer: a direct file URL. PacksMC routes every download through its own site so
 * creator credit, download counts, link lockers and Packs+ gates keep working, so this client never tries to get
 * around that: the pack browser sends the player to the pack's page and installs the file the player's browser saves.
 * <p>
 * Every call runs on a small worker pool and returns a CompletableFuture; nothing here ever blocks the game thread.
 * Limits (free key): 60 requests / minute, 1,000 / day, so pages are cached and searches are debounced by the UI.
 */
public final class PacksMcApi {
    public static final String BASE = "https://packsmc.com";
    public static final String KEY_PAGE = BASE + "/api-access";

    /** A page of results; nextCursor is null when there is nothing after it. */
    public record Page(List<PackInfo> packs, String nextCursor) {}

    /** Every failure the UI can show, with a message that is fit to put on screen. */
    public static final class ApiException extends RuntimeException {
        public final String code;

        public ApiException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final AtomicInteger THREAD_NO = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "lone-packsmc-api-" + THREAD_NO.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, PackInfo> DETAILS = new ConcurrentHashMap<>();
    private static volatile long blockedUntil;

    private PacksMcApi() {}

    public static boolean hasKey() {
        return !PacksSettings.apiKey().isEmpty();
    }

    /** Keys look like pmc_ followed by letters and digits. This only catches obvious paste mistakes. */
    public static boolean looksLikeKey(String s) {
        return s != null && s.trim().matches("pmc_[A-Za-z0-9_\\-]{8,}");
    }

    // ------------------------------------------------------------------ URL safety

    /** True for an https URL on packsmc.com (or a subdomain). Everything the API returns is checked with this. */
    public static boolean isPacksMcUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && host != null
                    && (host.equalsIgnoreCase("packsmc.com") || host.toLowerCase(Locale.ROOT).endsWith(".packsmc.com"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ calls

    /**
     * @param sort "recent" (cursor paging), "downloads" or "likes" (one page, the API has no cursor for them)
     * @param resolution "" for any, or e.g. "16x"
     */
    public static CompletableFuture<Page> list(String query, String sort, String resolution, String cursor, int limit) {
        StringBuilder q = new StringBuilder("/api/v1/packs?limit=").append(Math.max(1, Math.min(100, limit)));
        q.append("&sort=").append(enc(sort));
        if (query != null && query.trim().length() >= 2) q.append("&q=").append(enc(query.trim()));
        if (resolution != null && !resolution.isEmpty()) q.append("&resolution=").append(enc(resolution));
        if (cursor != null && !cursor.isEmpty()) q.append("&cursor=").append(enc(cursor));
        return get(q.toString(), PacksSettings.apiKey()).thenApply(o -> {
            List<PackInfo> out = new ArrayList<>();
            if (o.has("data") && o.get("data").isJsonArray()) {
                o.getAsJsonArray("data").forEach(e -> {
                    if (e.isJsonObject()) {
                        PackInfo p = PackInfo.parse(e.getAsJsonObject());
                        if (!p.id().isEmpty() && isPacksMcUrl(p.downloadUrl())) out.add(p);
                    }
                });
            }
            String next = o.has("next_cursor") && o.get("next_cursor").isJsonPrimitive()
                    ? o.get("next_cursor").getAsString() : null;
            return new Page(out, next == null || next.isEmpty() ? null : next);
        });
    }

    /** Full details (description, gallery, per-version availability). Cached for the session. */
    public static CompletableFuture<PackInfo> detail(String idOrSlug) {
        PackInfo cached = DETAILS.get(idOrSlug);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return get("/api/v1/packs/" + enc(idOrSlug), PacksSettings.apiKey()).thenApply(o -> {
            PackInfo p = PackInfo.parse(o);
            DETAILS.put(idOrSlug, p);
            return p;
        });
    }

    /** Checks a key with GET /api/v1/me and stores it if it works. */
    public static CompletableFuture<Void> verifyAndStoreKey(String key) {
        String k = key == null ? "" : key.trim();
        if (!looksLikeKey(k)) {
            return CompletableFuture.failedFuture(new ApiException("bad_key",
                    "That doesn't look like a PacksMC key. It starts with pmc_."));
        }
        return get("/api/v1/me", k).thenAccept(o -> PacksSettings.setApiKey(k));
    }

    // ------------------------------------------------------------------ plumbing

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static CompletableFuture<JsonObject> get(String pathAndQuery, String key) {
        return CompletableFuture.supplyAsync(() -> {
            if (key == null || key.isEmpty()) throw new ApiException("no_key", "Add your free PacksMC API key first.");
            long wait = blockedUntil - System.currentTimeMillis();
            if (wait > 0) {
                throw new ApiException("rate_limited", "PacksMC asked us to slow down. Try again in " + (wait / 1000 + 1) + "s.");
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + pathAndQuery))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .header("User-Agent", "LoneClient/" + PulseClient.shortVersion())
                    .GET().build();
            for (int attempt = 0; ; attempt++) {
                try {
                    HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int sc = r.statusCode();
                    if (sc == 200) return parseObject(r.body());
                    if (sc >= 500 && attempt == 0) { // transient: one quiet retry
                        Thread.sleep(800);
                        continue;
                    }
                    throw error(sc, r);
                } catch (HttpTimeoutException e) {
                    throw new ApiException("timeout", "PacksMC took too long to answer. Try again.");
                } catch (IOException e) {
                    throw new ApiException("network", "Can't reach packsmc.com. Check your internet connection.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("cancelled", "Cancelled.");
                }
            }
        }, POOL);
    }

    private static JsonObject parseObject(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ApiException("bad_response", "PacksMC sent something we couldn't read.");
        }
    }

    private static ApiException error(int status, HttpResponse<String> r) {
        String code = "http_" + status;
        try {
            JsonObject err = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error");
            if (err != null && err.has("code")) code = err.get("code").getAsString();
        } catch (RuntimeException ignored) {
            // body was not the documented error shape
        }
        switch (status) {
            case 401:
                return new ApiException("unauthenticated", "PacksMC rejected your API key. Create a new one on the API Access page.");
            case 403:
                return new ApiException(code, "That needs a Packs+ subscription.");
            case 404:
                return new ApiException("not_found", "That pack is no longer available.");
            case 429: {
                long secs = r.headers().firstValueAsLong("Retry-After").orElse(30);
                blockedUntil = System.currentTimeMillis() + secs * 1000;
                String why = code.equals("daily_quota") ? "You hit today's PacksMC API limit." : "Too many requests.";
                return new ApiException(code, why + " Try again in " + secs + "s.");
            }
            default:
                return new ApiException(code, "PacksMC had a problem (" + status + "). Try again in a moment.");
        }
    }

    /** Turns whatever a future failed with into something the UI can show. */
    public static String message(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof ApiException) return t.getMessage();
        PulseClient.LOG.warn("PacksMC request failed", t);
        return "Something went wrong talking to PacksMC.";
    }
}
