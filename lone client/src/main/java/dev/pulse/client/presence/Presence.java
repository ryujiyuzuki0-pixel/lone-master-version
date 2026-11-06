package dev.pulse.client.presence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.ClientModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * Knows which players are also running Lone Client, so their name gets the logo (tab list and nametag).
 * Players find each other through a small presence server (see presence-server/). Its address is
 * baked in as SERVER_URL below. Leave it empty and only you get the logo.
 */
public final class Presence {
    private static final Set<UUID> USERS = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** Address of your presence server, e.g. "http://123.45.67.89:8787". Set it once before building. */
    private static final String SERVER_URL = "https://presence-3p80.onrender.com";
    private static volatile String url = SERVER_URL.trim().replaceAll("/+$", "");
    private static ScheduledExecutorService pool;
    private static Component logo;

    private Presence() {}

    public static void init() {
        restart();
    }

    private static synchronized void restart() {
        if (pool != null) pool.shutdownNow();
        pool = null;
        USERS.clear();
        if (url.isEmpty()) return;
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lone-presence");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleWithFixedDelay(Presence::poll, 1, 20, TimeUnit.SECONDS);
    }

    private static UUID self() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? null : mc.getUser().getProfileId();
    }

    private static void poll() {
        try {
            ClientModule cm = ModuleManager.client();
            UUID me = self();
            if (me != null && (cm == null || cm.shareUse())) {
                JsonObject body = new JsonObject();
                body.addProperty("uuid", me.toString());
                body.addProperty("name", Minecraft.getInstance().getUser().getName());
                body.addProperty("version", PulseClient.shortVersion());
                HTTP.send(HttpRequest.newBuilder(URI.create(url + "/hello"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/online"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;
            JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
            Set<UUID> next = new HashSet<>();
            for (JsonElement e : arr) {
                try {
                    next.add(UUID.fromString(e.getAsString()));
                } catch (Exception ignored) {
                }
            }
            USERS.retainAll(next);
            USERS.addAll(next);
        } catch (Exception e) {
            PulseClient.LOG.debug("Presence poll failed: {}", e.toString());
        }
    }

    /** True when this player should wear the logo. You always see your own. */
    public static boolean isUser(UUID id) {
        if (id == null) return false;
        ClientModule cm = ModuleManager.client();
        if (cm != null && !cm.showLogos()) return false;
        return id.equals(self()) || USERS.contains(id);
    }

    /** The logo glyph (custom font pulse:logo, character U+E000) as a chat component. */
    private static Component logo() {
        if (logo == null) {
            JsonObject o = new JsonObject();
            o.addProperty("text", "\uE000");
            o.addProperty("font", "pulse:logo");
            logo = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, o).result().orElse(Component.empty());
        }
        return logo;
    }

    /** Puts the logo in front of a name. */
    public static Component tag(Component name) {
        return Component.empty().append(logo()).append(Component.literal(" ")).append(name);
    }
}
