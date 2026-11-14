package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.FreeLookModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free Look: mouse movement reaches the player through Entity#turn. While free look is active the movement goes to
 * the camera instead and the player's rotation is left alone. Only the local player is affected. require = 0: if a
 * Minecraft version changes this method the client still starts and Free Look reports "not supported".
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$freeLook(double yRot, double xRot, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer)) return;
        if (FreeLookModule.onTurn(yRot, xRot)) ci.cancel();
    }
}
