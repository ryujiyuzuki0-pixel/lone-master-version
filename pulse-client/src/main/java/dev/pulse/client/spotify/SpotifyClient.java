package dev.pulse.client.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.PulseConfig;
import dev.pulse.client.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Spotify Web API client using the PKCE flow (no client secret needed). */
public final class SpotifyClient {
    public static final int PORT = 43019;
    public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService POOL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pulse-spotify");
        t.setDaemon(true);
        return t;
    });
    private static final SecureRandom RANDOM = new SecureRandom();

    public static volatile String clientId = "";

    private static volatile String accessToken;
    private static volatile String refreshToken;
    private static volatile long expiresAt;
    private static volatile long backoffUntil;
    private static volatile String status = "";
    private static volatile ServerSocket serverSocket;

    private static volatile boolean hasTrack, playing;
    private static volatile String title = "", artist = "";
    private static volatile long progressMs, durationMs, stamp;

    private SpotifyClient() {}

    // ---------------------------------------------------------------- state

    public static boolean connected() { return refreshToken != null && !refreshToken.isEmpty(); }

    public static boolean hasTrack() { return connected() && hasTrack; }

    public static boolean playing() { return playing; }

    public static String title() { return title; }

    public static String artist() { return artist; }

    public static long duration() { return durationMs; }

    public static long progress() {
        long p = progressMs;
        if (playing) p += System.currentTimeMillis() - stamp;
        return Math.max(0, Math.min(durationMs, p));
    }

    public static String statusLine() {
        if (clientId == null || clientId.isBlank()) return "Set your client ID: /pulse spotify id <id>";
        if (!connected()) return status.isEmpty() ? "Not connected - press Connect" : status;
        return status.isEmpty() ? "Connected" : status;
    }

    // ---------------------------------------------------------------- lifecycle

    public static void init() {
        Path p = tokenPath();
        try {
            if (Files.exists(p)) {
                JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
            }
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read Spotify tokens", e);
        }
        POOL.scheduleWithFixedDelay(SpotifyClient::pollSafe, 2, 2, TimeUnit.SECONDS);
    }

    private static Path tokenPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-spotify.json");
    }

    private static void saveRefresh() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("refresh_token", refreshToken);
            Files.writeString(tokenPath(), o.toString());
        } catch (IOException e) {
            PulseClient.LOG.warn("Could not save Spotify tokens", e);
        }
    }

    public static void logout() {
        accessToken = null;
        refreshToken = null;
        hasTrack = false;
        playing = false;
        status = "Disconnected";
        try {
            Files.deleteIfExists(tokenPath());
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- login (PKCE)

    public static void login() {
        if (clientId == null || clientId.isBlank()) {
            chat(Component.literal("Set your Spotify client ID first: /pulse spotify id <id>").withStyle(ChatFormatting.YELLOW));
            chat(Component.literal("Create an app at developer.spotify.com and add this redirect URI: " + REDIRECT)
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        try {
            String verifier = randomUrlSafe(64);
            String challenge = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String state = randomUrlSafe(16);
            startCallbackServer(state, verifier);

            String url = "https://accounts.spotify.com/authorize?response_type=code"
                    + "&client_id=" + enc(clientId)
                    + "&scope=" + enc("user-read-playback-state user-modify-playback-state")
                    + "&redirect_uri=" + enc(REDIRECT)
                    + "&code_challenge_method=S256&code_challenge=" + challenge
                    + "&state=" + state;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.keyboardHandler.setClipboard(url));
            status = "Waiting for browser login...";
            Component link = Component.literal("[Click here to log in to Spotify]")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
            chat(link);
            chat(Component.literal("The link is also copied to your clipboard.").withStyle(ChatFormatting.GRAY));
        } catch (Exception e) {
            status = "Login failed";
            chat(Component.literal("Could not start Spotify login: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    private static void startCallbackServer(String expectedState, String verifier) throws IOException {
        stopServer();
        ServerSocket ss = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        ss.setSoTimeout(300_000);
        serverSocket = ss;
        Thread t = new Thread(() -> {
            try (ss) {
                String code = null;
                while (code == null) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(4000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String line = in.readLine();
                        if (line == null || !line.startsWith("GET /callback")) {
                            respond(s, 404, "Not found");
                            continue;
                        }
                        String query = line.contains("?") ? line.substring(line.indexOf('?') + 1, line.lastIndexOf(' ')) : "";
                        Map<String, String> q = parseQuery(query);
                        if (q.containsKey("error") || !expectedState.equals(q.get("state")) || q.get("code") == null) {
                            respond(s, 400, "Login was cancelled or invalid. You can close this tab.");
                            status = "Login cancelled";
                            return;
                        }
                        code = q.get("code");
                        respond(s, 200, "Pulse is connected to Spotify. You can close this tab and return to Minecraft.");
                    } catch (SocketTimeoutException ignored) {
                        // a speculative browser connection that never sent anything
                    }
                }
                exchange(code, verifier);
            } catch (SocketTimeoutException e) {
                status = "Login timed out";
            } catch (IOException e) {
                if (!ss.isClosed()) status = "Login failed";
            }
        }, "pulse-spotify-auth");
        t.setDaemon(true);
        t.start();
    }

    private static void stopServer() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void respond(Socket s, int code, String message) throws IOException {
        String body = "<!doctype html><meta charset=utf-8><title>Pulse</title>"
                + "<body style=\"margin:0;height:100vh;display:grid;place-items:center;background:#0e1120;color:#e8eaf6;font:16px system-ui\">"
                + "<div style=\"text-align:center\"><div style=\"font-size:28px;font-weight:700;"
                + "background:linear-gradient(90deg,#7c5cff,#22d3ee);-webkit-background-clip:text;color:transparent\">PULSE</div>"
                + "<p>" + message + "</p></div></body>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Connection: close\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void exchange(String code, String verifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", REDIRECT);
        f.put("client_id", clientId);
        f.put("code_verifier", verifier);
        JsonObject o = tokenRequest(f);
        if (o == null || o.has("error")) {
            status = "Login failed";
            chat(Component.literal("Spotify login failed.").withStyle(ChatFormatting.RED));
            return;
        }
        applyTokens(o);
        status = "Connected";
        chat(Component.literal("Spotify connected.").withStyle(ChatFormatting.GREEN));
    }

    // ---------------------------------------------------------------- tokens

    private static JsonObject tokenRequest(Map<String, String> form) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(form(form)))
                    .build();
            HttpResponse<String> r = HTTP.send(req, BodyHandlers.ofString());
            JsonElement e = JsonParser.parseString(r.body());
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            if (r.statusCode() != 200 && !o.has("error")) o.addProperty("error", "http_" + r.statusCode());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static void applyTokens(JsonObject o) {
        accessToken = o.get("access_token").getAsString();
        long ttl = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
        expiresAt = System.currentTimeMillis() + ttl * 1000;
        if (o.has("refresh_token")) {
            refreshToken = o.get("refresh_token").getAsString();
            saveRefresh();
        }
    }

    private static boolean ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 30_000) return true;
        if (!connected()) return false;
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("client_id", clientId);
        JsonObject o = tokenRequest(f);
        if (o == null) {
            status = "Network error";
            return false;
        }
        if (o.has("error")) {
            if ("invalid_grant".equals(o.get("error").getAsString())) logout();
            status = "Please reconnect";
            return false;
        }
        applyTokens(o);
        return true;
    }

    // ---------------------------------------------------------------- polling

    private static void pollSafe() {
        try {
            poll();
        } catch (Exception e) {
            status = "Connection error";
        }
    }

    private static void poll() throws Exception {
        if (!ModuleManager.spotify().enabled || !connected()) return;
        if (System.currentTimeMillis() < backoffUntil || !ensureToken()) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/currently-playing?additional_types=track,episode"))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        HttpResponse<String> r = HTTP.send(req, BodyHandlers.ofString());
        switch (r.statusCode()) {
            case 200 -> parse(r.body());
            case 204 -> {
                hasTrack = false;
                playing = false;
                status = "Nothing playing";
            }
            case 401 -> expiresAt = 0;
            case 429 -> backoffUntil = System.currentTimeMillis()
                    + 1000L * r.headers().firstValueAsLong("Retry-After").orElse(5);
            default -> status = "Spotify error " + r.statusCode();
        }
    }

    private static void parse(String body) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonElement item = o.get("item");
        if (item == null || item.isJsonNull()) {
            hasTrack = false;
            status = "Nothing playing";
            return;
        }
        JsonObject it = item.getAsJsonObject();
        String name = it.has("name") ? it.get("name").getAsString() : "Unknown";
        String by = "";
        if (it.has("artists") && it.get("artists").isJsonArray()) {
            JsonArray arr = it.getAsJsonArray("artists");
            StringBuilder sb = new StringBuilder();
            for (JsonElement a : arr) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.getAsJsonObject().get("name").getAsString());
            }
            by = sb.toString();
        } else if (it.has("show") && it.get("show").isJsonObject()) {
            by = it.getAsJsonObject("show").get("name").getAsString();
        }
        title = name;
        artist = by;
        durationMs = it.has("duration_ms") ? it.get("duration_ms").getAsLong() : 0;
        progressMs = o.has("progress_ms") && !o.get("progress_ms").isJsonNull() ? o.get("progress_ms").getAsLong() : 0;
        playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
        stamp = System.currentTimeMillis();
        hasTrack = true;
        status = "Connected";
    }

    // ---------------------------------------------------------------- controls

    public static void control(String action) {
        if (!connected()) return;
        POOL.execute(() -> {
            try {
                if (!ensureToken()) return;
                String method = switch (action) {
                    case "next", "previous" -> "POST";
                    default -> "PUT";
                };
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player/" + action))
                        .timeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + accessToken)
                        .method(method, BodyPublishers.noBody()).build();
                HttpResponse<String> r = HTTP.send(req, BodyHandlers.ofString());
                if (r.statusCode() == 403) status = "Playback control needs Spotify Premium";
                else if (r.statusCode() == 404) status = "No active device";
                else {
                    if (action.equals("pause")) playing = false;
                    if (action.equals("play")) {
                        playing = true;
                        stamp = System.currentTimeMillis();
                    }
                    backoffUntil = 0;
                }
            } catch (Exception e) {
                status = "Connection error";
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    public static void setClientId(String id) {
        clientId = id.trim();
        PulseConfig.save();
    }

    private static void chat(Component c) {
        Minecraft mc = Minecraft.getInstance();
        Component msg = Component.literal("[Lone] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(c);
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.getChat().addMessage(msg);
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String form(Map<String, String> m) {
        return m.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
    }

    private static String randomUrlSafe(int len) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return sb.toString();
    }
}
