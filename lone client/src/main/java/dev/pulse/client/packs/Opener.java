package dev.pulse.client.packs;

import java.nio.file.Path;
import java.util.Locale;

/** Opens a web page or a folder with the operating system's default handler (no Minecraft internals involved). */
public final class Opener {
    private Opener() {}

    /** Only https://packsmc.com pages are ever opened. */
    public static boolean openSite(String url) {
        if (url == null || !(url.equals(PacksMcApi.SITE) || url.startsWith(PacksMcApi.SITE + "/"))) return false;
        return run(url, true);
    }

    public static boolean openFolder(Path dir) {
        return run(dir.toAbsolutePath().toString(), false);
    }

    private static boolean run(String target, boolean url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = url ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target)
                        : new ProcessBuilder("explorer.exe", target);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", target);
            } else {
                pb = new ProcessBuilder("xdg-open", target);
            }
            pb.start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
