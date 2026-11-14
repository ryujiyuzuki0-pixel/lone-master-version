package dev.pulse.client.cosmetics;

import java.util.Locale;

/** The kinds of cosmetic. One item per category can be equipped at a time. */
public enum CosmeticCategory {
    CAPE("Capes", "cape"),
    WINGS("Wings", "wings"),
    TRAIL("Trails", "trail"),
    AURA("Auras", "aura");

    public final String title;
    public final String key;

    CosmeticCategory(String title, String key) {
        this.title = title;
        this.key = key;
    }

    /** Capes and wings are textures; trails and auras are particles. */
    public boolean textured() {
        return this == CAPE || this == WINGS;
    }

    public static CosmeticCategory parse(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (CosmeticCategory c : values()) if (c.key.equals(k)) return c;
        return null;
    }
}
