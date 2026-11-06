package dev.pulse.client.module.impl;

import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.core.ClientAsset;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Optional;

/**
 * Cape selection. The textures ship in assets/pulse/textures/cape/ (64x32, standard cape layout).
 * The menu shows a live waving preview; AbstractClientPlayerMixin applies the chosen cape to your own player in game,
 * and PlayerCapeModelMixin adds the flowing in-game sway on top of the vanilla cape physics.
 */
public final class CapeModule extends Module {
    private final ChoiceSetting cape = add(new ChoiceSetting("Cape", 0, CapeArt.NAMES));
    private final BoolSetting waveGame = add(new BoolSetting("Wave in game", true));
    private final SliderSetting waveAmount = add(new SliderSetting("Wave strength", 0.2, 3.0, 0.1, 1.0, "x"));
    private final SliderSetting previewSpeed = add(new SliderSetting("Preview wave speed", 0.2, 3.0, 0.1, 1.0, "x"));
    private int patchIndex = -1;
    private PlayerSkin.Patch patch;

    public CapeModule() {
        super("cape", "Cape", "Choose a cape design. Waves in the menu and in game.",
                Category.VISUAL, false, 0f, 0f, false);
    }

    public int[] face() {
        return CapeArt.FACES[cape.index];
    }

    public static int count() {
        return CapeArt.NAMES.length;
    }

    public static String nameOf(int i) {
        return CapeArt.NAMES[i];
    }

    public static int[] faceOf(int i) {
        return CapeArt.FACES[i];
    }

    public int selected() {
        return cape.index;
    }

    public void select(int i) {
        cape.index = Math.max(0, Math.min(CapeArt.NAMES.length - 1, i));
    }

    public double previewSpeed() {
        return previewSpeed.value;
    }

    public boolean waveInGame() {
        return waveGame.value;
    }

    /**
     * Extra cape rotation {xRot, zRot} in radians for the in-game wave. Gentle idle flutter that
     * grows with walking speed (0..1). Speed follows the "Preview wave speed" slider.
     */
    public float[] waveOffset(float walkSpeed) {
        double t = System.currentTimeMillis() / 1000.0 * previewSpeed.value;
        float move = Math.max(0f, Math.min(1f, walkSpeed));
        float amp = (float) waveAmount.value * (0.35f + 0.65f * move);
        float x = (float) (Math.sin(t * 3.2) * 0.07 + Math.sin(t * 5.7 + 1.3) * 0.035) * amp;
        float z = (float) (Math.sin(t * 2.1 + 0.6) * 0.04) * amp;
        return new float[]{x, z};
    }

    /** Returns the given skin with the selected cape swapped in. Called by AbstractClientPlayerMixin. */
    public PlayerSkin applyTo(PlayerSkin skin) {
        if (patch == null || patchIndex != cape.index) {
            String name = CapeArt.FILES[cape.index];
            //? if >=1.21.11 {
            Identifier id = Identifier.fromNamespaceAndPath(PulseClient.ID, "cape/" + name);
            Identifier path = Identifier.fromNamespaceAndPath(PulseClient.ID, "textures/cape/" + name + ".png");
            //?} else {
            /*ResourceLocation id = ResourceLocation.fromNamespaceAndPath(PulseClient.ID, "cape/" + name);
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath(PulseClient.ID, "textures/cape/" + name + ".png");
            *///?}
            patch = new PlayerSkin.Patch(Optional.empty(),
                    Optional.of(new ClientAsset.ResourceTexture(id, path)), Optional.empty(), Optional.empty());
            patchIndex = cape.index;
        }
        return skin.with(patch);
    }
}
