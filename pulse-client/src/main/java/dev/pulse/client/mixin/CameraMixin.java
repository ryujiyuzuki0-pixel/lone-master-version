package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.FreeLookModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free Look: every camera rotation goes through Camera#setRotation, so this is the one place the view angles can be
 * swapped without touching the player. require = 0 and an explicit descriptor: if a Minecraft version changes this
 * method the client still starts and Free Look reports "not supported" instead of activating.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static boolean pulse$reentry;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setRotation(FF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$freeLook(float yRot, float xRot, CallbackInfo ci) {
        if (pulse$reentry) return;
        if (!FreeLookModule.rotation(yRot, xRot)) return;
        pulse$reentry = true;
        try {
            setRotation(FreeLookModule.outYaw, FreeLookModule.outPitch);
        } finally {
            pulse$reentry = false;
        }
        ci.cancel();
    }
}
