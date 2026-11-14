package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.DropPreventionModule;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drop key (Q / Ctrl+Q). Drop Prevention may cancel it. require = 0 so that if a Minecraft version changes this
 * method the client still starts (protection for the drop key would then simply be inactive).
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$guardDrop(boolean fullStack, CallbackInfoReturnable<Boolean> cir) {
        if (DropPreventionModule.interceptKeyDrop(fullStack)) cir.setReturnValue(false);
    }
}
