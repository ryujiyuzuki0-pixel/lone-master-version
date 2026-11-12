package dev.pulse.client.packs;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Takes a resource pack .zip the player downloaded, checks that it really is a pack, copies it into the game's
 * resourcepacks folder and (optionally) switches it on and reloads. All file work runs on a worker thread; only the
 * pack-repository calls run on the game thread, where Minecraft requires them.
 * <p>
 * It also watches the player's downloads folder for a short time after they press Download on a pack's page, so the
 * whole thing needs no further click: the browser saves the file, the client picks it up.
 */
public final class PackImporter {
    public record Result(boolean ok, String message) {}

    private static final long MAX_ZIP_BYTES = 1_500_000_000L;
    private static final long WATCH_LIMIT_MS = 15 * 60 * 1000L;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lone-packsmc-io");
        t.setDaemon(true);
        return t;
    });

    private PackImporter() {}

    public static Path packsDir(Minecraft mc) {
        Path p = mc.getResourcePackDirectory();
        return p != null ? p : mc.gameDirectory.toPath().resolve("resourcepacks");
    }

    // ------------------------------------------------------------------ validation

    /** Returns null when the file is a usable resource pack, otherwise a message for the player. */
    public static String validate(Path zip) {
        try {
            if (!Files.isRegularFile(zip)) return "That file isn't there anymore.";
            long size = Files.size(zip);
            if (size < 22) return "That file is empty, the download probably didn't finish.";
            if (size > MAX_ZIP_BYTES) return "That file is too big to be a resource pack.";
            try (ZipFile z = new ZipFile(zip.toFile())) {
                if (z.size() > 200_000) return "That zip has an unreasonable number of files.";
                ZipEntry meta = z.getEntry("pack.mcmeta");
                if (meta == null) {
                    boolean nested = z.stream().anyMatch(e -> e.getName().endsWith("/pack.mcmeta"));
                    return nested
                            ? "pack.mcmeta is inside an extra folder, so Minecraft can't read this zip. Re-zip the folder's contents."
                            : "Not a resource pack: the zip has no pack.mcmeta.";
                }
                if (meta.isDirectory() || meta.getSize() < 2 || meta.getSize() > 262_144) {
                    return "This pack's pack.mcmeta looks damaged.";
                }
                try (InputStream in = z.getInputStream(meta)) {
                    JsonElement j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    if (!j.isJsonObject() || !j.getAsJsonObject().has("pack")) {
                        return "This pack's pack.mcmeta has no \"pack\" section.";
                    }
                }
            }
            return null;
        } catch (ZipException e) {
            return "That isn't a valid zip. It may be a partial download.";
        } catch (IOException | RuntimeException e) {
            return "Couldn't read that file (" + e.getClass().getSimpleName() + ").";
        }
    }

    // ------------------------------------------------------------------ install

    /** Validates, copies into resourcepacks, then (game thread) registers and optionally enables the pack. */
    public static CompletableFuture<Result> install(Path source, boolean activate) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<Result> done = new CompletableFuture<>();
        IO.execute(() -> {
            String bad = validate(source);
            if (bad != null) {
                done.complete(new Result(false, bad));
                return;
            }
            final Path dest;
            try {
                dest = copyIntoPacks(mc, source);
            } catch (IOException e) {
                PulseClient.LOG.warn("Could not copy pack {}", source, e);
                done.complete(new Result(false, "Couldn't copy the pack into your resourcepacks folder."));
                return;
            }
            mc.execute(() -> {
                try {
                    done.complete(register(mc, dest, activate));
                } catch (Throwable t) {
                    PulseClient.LOG.warn("Could not activate pack {}", dest, t);
                    done.complete(new Result(false, "Installed " + dest.getFileName() + ", but couldn't switch it on. Enable it under Resource Packs."));
                }
            });
        });
        return done;
    }

    private static Path copyIntoPacks(Minecraft mc, Path source) throws IOException {
        Path dir = packsDir(mc);
        Files.createDirectories(dir);
        Path real = source.toAbsolutePath().normalize();
        if (real.startsWith(dir.toAbsolutePath().normalize())) return real; // already in place
        String name = sanitize(source.getFileName().toString());
        Path dest = dir.resolve(name);
        long size = Files.size(source);
        if (Files.exists(dest) && Files.size(dest) == size) return dest; // same file again: reuse it
        for (int i = 1; Files.exists(dest); i++) {
            dest = dir.resolve(name.substring(0, name.length() - 4) + "-" + i + ".zip");
        }
        Path tmp = dir.resolve(dest.getFileName() + ".part");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest);
        }
        return dest;
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[^A-Za-z0-9._()+\\- ]", "_").trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n.substring(0, n.length() - 4);
        if (n.isEmpty() || n.startsWith(".")) n = "pack" + n;
        return n + ".zip";
    }

    /** Game thread. Makes Minecraft rescan the folder, then enables the pack on top and reloads. */
    private static Result register(Minecraft mc, Path file, boolean activate) {
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        String id = "file/" + file.getFileName();
        Pack pack = repo.getPack(id);
        if (pack == null) {
            return new Result(false, "Minecraft didn't recognise " + file.getFileName() + " as a resource pack.");
        }
        boolean compatible = pack.getCompatibility().isCompatible();
        if (!activate) {
            return new Result(true, "Installed " + file.getFileName() + ". Switch it on under Resource Packs.");
        }
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        if (!selected.contains(id)) selected.add(id); // last = highest priority
        repo.setSelected(selected);
        mc.options.updateResourcePacks(repo); // saves options.txt and reloads the game's resources if the list changed
        return new Result(true, (compatible ? "Applied " : "Applied (made for another Minecraft version) ") + file.getFileName());
    }

    // ------------------------------------------------------------------ download watcher

    private static ScheduledFuture<?> watch;
    private static long armedAt;
    private static final Set<Path> handled = new HashSet<>();
    private static final Map<Path, long[]> sizes = new HashMap<>(); // path -> {lastSize, stablePolls}

    public static synchronized boolean armed() {
        return watch != null;
    }

    /**
     * Starts watching the downloads folder for a pack the player is about to download. The first new, finished,
     * valid pack zip is installed; onResult runs on the game thread. Returns a message if it can't watch.
     */
    public static synchronized String arm(boolean activate, Consumer<Result> onResult) {
        disarm();
        Path dir = PacksSettings.downloadsDir();
        if (!Files.isDirectory(dir)) {
            return "Couldn't find your downloads folder. Drop the .zip onto this window instead.";
        }
        armedAt = System.currentTimeMillis();
        handled.clear();
        sizes.clear();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(PackImporter::isZip).forEach(handled::add); // everything already there is not ours
        } catch (IOException e) {
            return "Couldn't read your downloads folder. Drop the .zip onto this window instead.";
        }
        Minecraft mc = Minecraft.getInstance();
        watch = IO.scheduleWithFixedDelay(() -> poll(dir, activate, mc, onResult), 1, 1, TimeUnit.SECONDS);
        return null;
    }

    public static synchronized void disarm() {
        if (watch != null) watch.cancel(false);
        watch = null;
        handled.clear();
        sizes.clear();
    }

    private static boolean isZip(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(p);
    }

    private static void poll(Path dir, boolean activate, Minecraft mc, Consumer<Result> onResult) {
        try {
            if (System.currentTimeMillis() - armedAt > WATCH_LIMIT_MS) {
                disarm();
                return;
            }
            List<Path> found = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(PackImporter::isZip).forEach(found::add);
            }
            for (Path p : found) {
                synchronized (PackImporter.class) {
                    if (handled.contains(p)) continue;
                }
                if (isStillDownloading(p)) continue;
                long size = Files.size(p);
                long[] st;
                synchronized (PackImporter.class) {
                    st = sizes.computeIfAbsent(p, k -> new long[]{-1, 0});
                }
                if (st[0] == size && size > 0) st[1]++; else { st[0] = size; st[1] = 0; }
                if (st[1] < 2) continue; // size must hold still for two polls: the browser has finished writing
                synchronized (PackImporter.class) {
                    handled.add(p);
                }
                install(p, activate).thenAccept(res -> {
                    if (res.ok()) disarm();
                    mc.execute(() -> onResult.accept(res));
                });
            }
        } catch (Throwable t) {
            PulseClient.LOG.debug("download watcher: {}", t.toString());
        }
    }

    private static boolean isStillDownloading(Path zip) {
        String n = zip.getFileName().toString();
        for (String ext : new String[]{".crdownload", ".part", ".download", ".tmp", ".opdownload"}) {
            if (Files.exists(zip.resolveSibling(n + ext))) return true;
        }
        return false;
    }
}
