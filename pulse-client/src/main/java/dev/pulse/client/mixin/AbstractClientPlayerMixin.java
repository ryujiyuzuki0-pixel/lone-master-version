package dev.pulse.client.mixin;

import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.CapeModule;
import dev.pulse.client.skin.SkinLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the skin worn from the Wardrobe and the cape picked in the Cape module to your own player (local view). */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void pulse$customCape(CallbackInfoReturnable<PlayerSkin> cir) {
        if ((Object) this != Minecraft.getInstance().player) return;
        PlayerSkin skin = SkinLibrary.override(cir.getReturnValue());
        CapeModule cape = ModuleManager.cape();
        if (cape != null && cape.enabled) skin = cape.applyTo(skin);
        cir.setReturnValue(skin);
    }
}
