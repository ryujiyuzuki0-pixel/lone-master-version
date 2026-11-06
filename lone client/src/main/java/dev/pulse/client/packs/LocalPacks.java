package dev.pulse.client.packs;

import dev.pulse.client.PulseClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.Pack;

/** The resource packs the game already knows about, plus a safe "import what I just downloaded" helper. */
public final class LocalPacks {
    public record Entry(String id, String title, String description, boolean locked, boolean compatible, boolean builtIn) {}

    public record ImportResult(List<String> imported, int alreadyThere, int notPacks) {}

    private static final Duration RECENT = Duration.ofHours(2);
    private static final long MAX_ZIP = 1L << 30;

    private LocalPacks() {}

    public static PackRepository repo() {
        return Minecraft.getInstance().getResourcePackRepository();
    }

    /** Rescans the resourcepacks folder and returns every pack that can be listed (required/vanilla ones are marked locked). */
    public static List<Entry> scan() {
        PackRepository r = repo();
        r.reload();
        List<Entry> out = new ArrayList<>();
        for (Pack p : r.getAvailablePacks()) {
            out.add(new Entry(p.getId(), p.getTitle().getString(), p.getDescription().getString(),
                    p.isRequired(), p.getCompatibility().isCompatible(), !p.getId().startsWith("file/")));
        }
        out.sort(Comparator.comparing((Entry e) -> e.title().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Selected pack ids, lowest priority first (the order the game stores them in). */
    public static List<String> selected() {
        return new ArrayList<>(repo().getSelectedIds());
    }

    /** Saves the selection to options.txt and reloads the game's resources. */
    public static void apply(List<String> ids) {
        Minecraft mc = Minecraft.getInstance();
        PackRepository r = repo();
        r.setSelected(ids);
        mc.options.updateResourcePacks(r);
        mc.reloadResourcePacks();
    }

    public static Path folder() {
        return Minecraft.getInstance().getResourcePackDirectory();
    }

    /**
     * Copies pack zips you downloaded from packsmc.com into the resourcepacks folder. Only looks in your
     * own Downloads folder, only at .zip files changed in the last two hours that have a pack.mcmeta at the root.
     * The originals are left where they are.
     */
    public static ImportResult importFromDownloads() {
        List<String> imported = new ArrayList<>();
        int already = 0, invalid = 0;
        Path dl = Path.of(System.getProperty("user.home", "."), "Downloads");
        if (!Files.isDirectory(dl)) return new ImportResult(imported, 0, 0);
        Path dest = folder();
        Instant cutoff = Instant.now().minus(RECENT);
        List<Path> zips = new ArrayList<>();
        try (Stream<Path> s = Files.list(dl)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .filter(Files::isRegularFile)
                    .forEach(zips::add);
        } catch (IOException e) {
            PulseClient.LOG.warn("Could not read the Downloads folder");
            return new ImportResult(imported, 0, 0);
        }
        zips.sort(Comparator.comparingLong((Path p) -> lastModified(p)).reversed());
        for (Path z : zips) {
            try {
                if (Files.getLastModifiedTime(z).toInstant().isBefore(cutoff)) continue;
                long size = Files.size(z);
                if (size <= 0 || size > MAX_ZIP) continue;
                if (!isPack(z)) {
                    invalid++;
                    continue;
                }
                String name = z.getFileName().toString();
                Path target = dest.resolve(name);
                if (Files.exists(target) && Files.size(target) == size) {
                    already++;
                    continue;
                }
                int n = 1;
                while (Files.exists(target)) {
                    String base = name.substring(0, name.length() - 4);
                    target = dest.resolve(base + "-" + n++ + ".zip");
                }
                Files.copy(z, target, StandardCopyOption.COPY_ATTRIBUTES);
                imported.add(target.getFileName().toString());
            } catch (IOException e) {
                PulseClient.LOG.warn("Could not import {}", z.getFileName());
            }
        }
        return new ImportResult(imported, already, invalid);
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean isPack(Path zip) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return zf.getEntry("pack.mcmeta") != null;
        } catch (Exception e) {
            return false;
        }
    }
}
