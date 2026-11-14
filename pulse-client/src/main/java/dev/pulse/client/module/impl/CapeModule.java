package dev.pulse.client.module.impl;

import dev.pulse.client.cosmetics.Cosmetic;
import dev.pulse.client.cosmetics.CosmeticCategory;
import dev.pulse.client.cosmetics.CosmeticsManager;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Master switch for the cosmetics system (id kept as "cape" so existing configs still apply). What is equipped lives
 * in {@link CosmeticsManager}; this module turns the whole thing on or off, drives the particle cosmetics each tick,
 * and feeds the equipped cape and wings to AbstractClientPlayerMixin.
 */
public final class CapeModule extends Module {
    private final SliderSetting previewSpeed = add(new SliderSetting("Preview wave speed", 0.2, 3.0, 0.1, 1.0, "x"));

    public CapeModule() {
        super("cape", "Cosmetics", "Capes, wings, trails and auras. Pick them on the Cosmetics page.",
                Category.VISUAL, false, 0f, 0f, false);
        CosmeticsManager.init();
    }

    /** 10x16 ARGB front face of the equipped cape for the live previews, or null when no cape is worn. */
    public int[] face() {
        Cosmetic c = CosmeticsManager.equipped(CosmeticCategory.CAPE);
        return c == null ? null : c.face();
    }

    public String equippedName() {
        Cosmetic c = CosmeticsManager.equipped(CosmeticCategory.CAPE);
        return c == null ? "No cape" : c.name();
    }

    public double previewSpeed() {
        return previewSpeed.value;
    }

    /** Returns the given skin with the equipped cape and wings swapped in. */
    public PlayerSkin applyTo(PlayerSkin skin) {
        return CosmeticsManager.applyTo(skin);
    }

    @Override
    public void tick(Minecraft mc) {
        CosmeticsManager.tick(mc);
    }
}
