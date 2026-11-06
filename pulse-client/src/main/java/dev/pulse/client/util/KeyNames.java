package dev.pulse.client.util;

import java.util.Locale;

/**
 * Turns registry keys into readable names without touching version-specific ResourceKey APIs
 * (ResourceLocation was renamed to Identifier in 1.21.11). ResourceKey#toString() is stable:
 * "ResourceKey[minecraft:worldgen/biome / minecraft:plains]".
 */
public final class KeyNames {
    private KeyNames() {}

    /** "minecraft:plains" from a ResourceKey (or any object whose toString has that shape). */
    public static String id(Object key) {
        String s = String.valueOf(key);
        int slash = s.lastIndexOf(" / ");
        int end = s.lastIndexOf(']');
        if (slash >= 0 && end > slash) return s.substring(slash + 3, end);
        return s;
    }

    /** "Plains", "Nether Wastes", "The End"... */
    public static String pretty(String id) {
        int colon = id.indexOf(':');
        String p = colon >= 0 ? id.substring(colon + 1) : id;
        StringBuilder out = new StringBuilder();
        for (String w : p.split("[_/]")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    public static String prettyKey(Object key) {
        return pretty(id(key));
    }
}
