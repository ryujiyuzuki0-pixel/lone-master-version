package dev.pulse.client.packs;

import com.mojang.blaze3d.platform.NativeImage;
import dev.pulse.client.PulseClient;
import dev.pulse.client.skin.SkinTex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

import javax.imageio.ImageIO;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.IIORegistry;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Pack preview images: downloaded and decoded on a worker thread, turned into a GPU texture on the render thread,
 * kept in a small LRU so browsing a long list never eats memory. {@link #get} never blocks; it returns null until the
 * image is ready (the UI shows a coloured placeholder meanwhile) or if it could not be loaded.
 * <p>
 * Format note: PacksMC serves WebP. Minecraft's own decoder reads PNG/JPEG only, so WebP goes through ImageIO, which
 * needs a WebP reader on the classpath (see INSTALL.md, the optional TwelveMonkeys line). Without it previews simply
 * fall back to the placeholder and everything else keeps working.
 */
public final class PackThumbs {
    public record Thumb(SkinTex tex, int w, int h) {}

    private static final class Entry {
        volatile Thumb thumb;
        volatile boolean failed;
        volatile long lastUse;
    }

    private static final int MAX_ENTRIES = 40;
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final AtomicInteger THREAD_NO = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "lone-packsmc-img-" + THREAD_NO.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static volatile boolean pluginsTried;

    private PackThumbs() {}

    /** Render thread only. Returns the ready texture, or null (loading, failed or no URL). */
    public static Thumb get(String url) {
        if (url == null || url.isEmpty() || !PacksMcApi.isPacksMcUrl(url)) return null;
        Entry e = CACHE.get(url);
        if (e == null) {
            evictIfFull();
            e = new Entry();
            CACHE.put(url, e);
            final Entry entry = e;
            POOL.execute(() -> load(url, entry));
        }
        e.lastUse = System.nanoTime();
        return e.thumb;
    }

    /** Frees every texture (called when the browser closes). */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Entry e : CACHE.values()) {
            Thumb t = e.thumb;
            if (t != null) mc.getTextureManager().release(t.tex().id);
        }
        CACHE.clear();
    }

    private static void evictIfFull() {
        if (CACHE.size() < MAX_ENTRIES) return;
        String oldest = null;
        long age = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> me : CACHE.entrySet()) {
            Entry e = me.getValue();
            if (e.thumb == null && !e.failed) continue; // still loading
            if (e.lastUse < age) {
                age = e.lastUse;
                oldest = me.getKey();
            }
        }
        if (oldest == null) return;
        Entry gone = CACHE.remove(oldest);
        if (gone != null && gone.thumb != null) {
            Minecraft.getInstance().getTextureManager().release(gone.thumb.tex().id);
        }
    }

    // ------------------------------------------------------------------ worker side

    private static void load(String url, Entry entry) {
        try {
            byte[] data = fetch(url);
            NativeImage img = decode(data);
            final int w = img.getWidth(), h = img.getHeight();
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    AbstractTexture dyn = makeDynamic(img);
                    SkinTex id = SkinTex.of(PulseClient.ID, "packsmc/thumb_" + NEXT.incrementAndGet());
                    mc.getTextureManager().register(id.id, dyn);
                    entry.thumb = new Thumb(id, w, h);
                } catch (Throwable t) {
                    img.close();
                    entry.failed = true;
                    PulseClient.LOG.warn("PacksMC preview could not be turned into a texture", t);
                }
            });
        } catch (Throwable t) {
            entry.failed = true;
            PulseClient.LOG.debug("PacksMC preview not loaded: {}", t.toString());
        }
    }

    private static byte[] fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "LoneClient/" + PulseClient.shortVersion())
                .GET().build();
        HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("HTTP " + r.statusCode());
        }
        try (InputStream in = r.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("preview too large");
            }
            return out.toByteArray();
        }
    }

    private static NativeImage decode(byte[] data) throws IOException {
        try { // PNG / JPEG: Minecraft's own decoder, no AWT involved
            return NativeImage.read(new ByteArrayInputStream(data));
        } catch (IOException | RuntimeException ignored) {
            // fall through to ImageIO (WebP and friends)
        }
        registerOptionalPlugins();
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
        if (bi == null) throw new IOException("unsupported image format (no WebP reader installed?)");
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(bi, "png", png)) throw new IOException("could not re-encode preview");
        return NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
    }

    /** Registers the TwelveMonkeys WebP reader if the mod bundles it. ImageIO's own scan can't see Fabric's nested jars. */
    private static synchronized void registerOptionalPlugins() {
        if (pluginsTried) return;
        pluginsTried = true;
        try {
            Class<?> c = Class.forName("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
            IIORegistry.getDefaultInstance().registerServiceProvider((ImageReaderSpi) c.getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            PulseClient.LOG.info("No WebP reader bundled: pack previews fall back to placeholders.");
        }
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
                    Supplier<String> label = () -> "lone_packsmc_preview";
                    args[i] = label;
                } else {
                    ok = false;
                }
            }
            if (hasImage && ok) return (AbstractTexture) c.newInstance(args);
        }
        throw new IllegalStateException("No usable DynamicTexture constructor");
    }
}
