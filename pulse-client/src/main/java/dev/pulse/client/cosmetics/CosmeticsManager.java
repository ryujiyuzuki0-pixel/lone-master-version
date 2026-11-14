package dev.pulse.client.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.skin.SkinTex;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the player has equipped, saved to config/lone-cosmetics.json, and how it is shown:
 * capes and wings by swapping the textures on the local player's skin, trails and auras as client-side particles.
 * Everything here is local: other players only see your cosmetics if a server shares them.
 */
public final class CosmeticsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> PARTICLES = Set.of("flame", "soul_fire_flame", "snowflake", "end_rod", "heart",
            "note", "cherry_leaves", "enchant", "electric_spark", "dust", "glow", "portal", "dragon_breath",
            "spore_blossom_air", "witch", "happy_villager", "crimson_spore", "warped_spore", "white_ash",
            "reverse_portal", "cloud", "sculk_soul");
    private static final String DEFAULT_CAPE = "pulse";

    private static final Map<CosmeticCategory, String> EQUIPPED = new EnumMap<>(CosmeticCategory.class);
    private static final Map<String, PlayerSkin.Patch> PATCHES = new HashMap<>();
    private static boolean initialised;
    private static int ticks;

    private CosmeticsManager() {}

    public static boolean supportsParticle(String key) {
        return key != null && PARTICLES.contains(key);
    }

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        CosmeticRegistry.load();
        Path f = file();
        boolean read = false;
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                JsonObject eq = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("equipped");
                for (CosmeticCategory c : CosmeticCategory.values()) {
                    if (eq != null && eq.has(c.key)) {
                        Cosmetic item = CosmeticRegistry.get(eq.get(c.key).getAsString());
                        if (item != null && item.category() == c) EQUIPPED.put(c, item.id());
                    }
                }
                read = true;
            } catch (IOException | RuntimeException e) {
                PulseClient.LOG.warn("Could not read {}: {}", f, e.toString());
            }
        }
        if (!read && CosmeticRegistry.get(DEFAULT_CAPE) != null) EQUIPPED.put(CosmeticCategory.CAPE, DEFAULT_CAPE); // first run
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("lone-cosmetics.json");
    }

    private static synchronized void save() {
        JsonObject eq = new JsonObject();
        EQUIPPED.forEach((c, id) -> eq.addProperty(c.key, id));
        JsonObject root = new JsonObject();
        root.add("equipped", eq);
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PulseClient.LOG.warn("Could not save {}: {}", f, e.toString());
        }
    }

    // ------------------------------------------------------------------ equip state

    public static Cosmetic equipped(CosmeticCategory c) {
        init();
        return CosmeticRegistry.get(EQUIPPED.get(c));
    }

    public static boolean isEquipped(Cosmetic item) {
        init();
        return item.id().equals(EQUIPPED.get(item.category()));
    }

    /** Equips the item, or takes it off if it is already worn. Returns true if it is worn afterwards. */
    public static boolean toggle(Cosmetic item) {
        init();
        boolean now;
        if (isEquipped(item)) {
            EQUIPPED.remove(item.category());
            now = false;
        } else {
            EQUIPPED.put(item.category(), item.id());
            now = true;
        }
        save();
        return now;
    }

    public static void unequip(CosmeticCategory c) {
        init();
        if (EQUIPPED.remove(c) != null) save();
    }

    /** Wears exactly this item (used by the Cape module's index based API). */
    public static void equip(Cosmetic item) {
        init();
        if (!isEquipped(item)) {
            EQUIPPED.put(item.category(), item.id());
            save();
        }
    }

    // ------------------------------------------------------------------ skin textures

    /** Returns the skin with the equipped cape and wings swapped in. Called from AbstractClientPlayerMixin. */
    public static PlayerSkin applyTo(PlayerSkin skin) {
        init();
        Cosmetic cape = equipped(CosmeticCategory.CAPE), wings = equipped(CosmeticCategory.WINGS);
        if (cape == null && wings == null) return skin;
        String key = (cape == null ? "-" : cape.id()) + "|" + (wings == null ? "-" : wings.id());
        PlayerSkin.Patch patch = PATCHES.computeIfAbsent(key, k -> new PlayerSkin.Patch(Optional.empty(),
                cape == null ? Optional.empty() : Optional.of(asset("cape", cape.asset())),
                wings == null ? Optional.empty() : Optional.of(asset("elytra", wings.asset())),
                Optional.empty()));
        return skin.with(patch);
    }

    private static ClientAsset.ResourceTexture asset(String folder, String name) {
        return new ClientAsset.ResourceTexture(SkinTex.of(PulseClient.ID, folder + "/" + name).id,
                SkinTex.of(PulseClient.ID, "textures/" + folder + "/" + name + ".png").id);
    }

    // ------------------------------------------------------------------ particles

    /** Runs every client tick. Trails follow your feet while you move; auras drift around your body. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        init();
        ticks++;
        RandomSource rnd = mc.level.random;
        Cosmetic trail = equipped(CosmeticCategory.TRAIL);
        if (trail != null) {
            Vec3 v = mc.player.getDeltaMovement();
            if (v.x * v.x + v.z * v.z > 0.0004) {
                ParticleOptions p = particleFor(trail);
                for (int i = 0; i < 2; i++) {
                    mc.level.addParticle(p, mc.player.getX() + (rnd.nextDouble() - 0.5) * 0.4, mc.player.getY() + 0.1 + rnd.nextDouble() * 0.2,
                            mc.player.getZ() + (rnd.nextDouble() - 0.5) * 0.4, 0, 0.01, 0);
                }
            }
        }
        Cosmetic aura = equipped(CosmeticCategory.AURA);
        if (aura != null && ticks % 3 == 0) {
            ParticleOptions p = particleFor(aura);
            double a = rnd.nextDouble() * Math.PI * 2;
            double r = 0.55 + rnd.nextDouble() * 0.25;
            mc.level.addParticle(p, mc.player.getX() + Math.cos(a) * r, mc.player.getY() + rnd.nextDouble() * 1.9,
                    mc.player.getZ() + Math.sin(a) * r, 0, 0.02, 0);
        }
    }

    private static ParticleOptions particleFor(Cosmetic c) {
        return switch (c.particle()) {
            case "flame" -> ParticleTypes.FLAME;
            case "soul_fire_flame" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "snowflake" -> ParticleTypes.SNOWFLAKE;
            case "end_rod" -> ParticleTypes.END_ROD;
            case "heart" -> ParticleTypes.HEART;
            case "note" -> ParticleTypes.NOTE;
            case "cherry_leaves" -> ParticleTypes.CHERRY_LEAVES;
            case "enchant" -> ParticleTypes.ENCHANT;
            case "electric_spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "dust" -> new DustParticleOptions(c.color() & 0xFFFFFF, 1.0f);
            case "glow" -> ParticleTypes.GLOW;
            case "portal" -> ParticleTypes.PORTAL;
            case "dragon_breath" -> PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0f);
            case "spore_blossom_air" -> ParticleTypes.SPORE_BLOSSOM_AIR;
            case "witch" -> ParticleTypes.WITCH;
            case "happy_villager" -> ParticleTypes.HAPPY_VILLAGER;
            case "crimson_spore" -> ParticleTypes.CRIMSON_SPORE;
            case "warped_spore" -> ParticleTypes.WARPED_SPORE;
            case "white_ash" -> ParticleTypes.WHITE_ASH;
            case "reverse_portal" -> ParticleTypes.REVERSE_PORTAL;
            case "cloud" -> ParticleTypes.CLOUD;
            case "sculk_soul" -> ParticleTypes.SCULK_SOUL;
            default -> ParticleTypes.END_ROD; // unreachable: the registry only accepts keys listed in PARTICLES
        };
    }
}
