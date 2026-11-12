package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.JumpResetModule;
import dev.pulse.client.module.impl.StunDetectorModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Jump Reset Counter the moment the local player's jump routine runs, so a jump is observed rather than
 * guessed from the jump key. require = 0: if a Minecraft version renames this method the client still starts and
 * the module falls back to estimated results.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), require = 0)
    private void pulse$onJump(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer) JumpResetModule.onPlayerJump();
    }

    /**
     * Entity event 30 is the game's "shield disabled" signal (an axe hit knocked the shield down). Stun Detector
     * uses it as the confirmed result for shield stuns. require = 0: without it, shield stuns are only estimated.
     */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"), require = 0)
    private void pulse$entityEvent(byte id, CallbackInfo ci) {
        if (id == 30 && ((LivingEntity) (Object) this).level().isClientSide()) {
            StunDetectorModule.onShieldDisabled((LivingEntity) (Object) this);
        }
    }
}
