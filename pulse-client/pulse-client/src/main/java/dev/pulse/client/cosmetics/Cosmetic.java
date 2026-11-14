package dev.pulse.client.cosmetics;

/**
 * One cosmetic as described by registry.json.
 *
 * @param asset    texture file name (without .png) for capes and wings, null otherwise
 * @param particle particle key for trails and auras (see CosmeticsManager#particleFor), null otherwise
 * @param color    RGB swatch colour; also the dust colour for particle "dust"
 * @param face     10x16 ARGB preview of the item (capes and wings), null otherwise
 */
public record Cosmetic(String id, String name, CosmeticCategory category, Rarity rarity, String asset,
                       String particle, int color, String description, int[] face) {

    public enum Rarity {
        COMMON("Common", 0xFF9AA3B5), RARE("Rare", 0xFF4C8DFF), EPIC("Epic", 0xFFB06BFF), LEGENDARY("Legendary", 0xFFFFB02E);

        public final String label;
        public final int color;

        Rarity(String label, int color) {
            this.label = label;
            this.color = color;
        }

        static Rarity parse(String s) {
            if (s != null) for (Rarity r : values()) if (r.name().equalsIgnoreCase(s.trim())) return r;
            return COMMON;
        }
    }

    static Rarity rarityOf(String s) {
        return Rarity.parse(s);
    }
}
