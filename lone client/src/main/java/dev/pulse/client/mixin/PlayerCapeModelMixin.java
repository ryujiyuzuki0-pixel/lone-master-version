package dev.pulse.client.mixin;

import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.CapeModule;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a flowing sway on top of the vanilla cape physics. Works whether vanilla rewrites the cape
 * rotation every frame or leaves it on the part: we compare the pose before and after setupAnim.
 * require = 0 so a future Minecraft change disables the effect instead of crashing the game.
 */
@Mixin(targets = "net.minecraft.client.model.PlayerCapeModel")
public abstract class PlayerCapeModelMixin {
    @Unique private int pulse$depth;
    @Unique private float pulse$preX, pulse$preZ, pulse$lastX, pulse$lastZ;

    @Inject(method = "setupAnim", at = @At("HEAD"), require = 0)
    private void pulse$head(@Coerce Object state, CallbackInfo ci) {
        if (pulse$depth++ != 0) return;
        ModelPart cape = pulse$cape();
        if (cape == null) return;
        pulse$preX = cape.xRot;
        pulse$preZ = cape.zRot;
    }

    @Inject(method = "setupAnim", at = @At("RETURN"), require = 0)
    private void pulse$tail(@Coerce Object state, CallbackInfo ci) {
        if (--pulse$depth != 0) return;
        ModelPart cape = pulse$cape();
        CapeModule mod = ModuleManager.cape();
        if (cape == null) return;

        float baseX = cape.xRot, baseZ = cape.zRot;
        if (baseX == pulse$preX && baseZ == pulse$preZ) {
            // vanilla left the part alone, so our previous offset is still on it
            baseX -= pulse$lastX;
            baseZ -= pulse$lastZ;
        }
        float dx = 0f, dz = 0f;
        if (mod != null && mod.enabled && mod.waveInGame()) {
            float[] o = mod.waveOffset(state instanceof LivingEntityRenderState l ? l.walkAnimationSpeed : 0f);
            dx = o[0];
            dz = o[1];
        }
        cape.xRot = baseX + dx;
        cape.zRot = baseZ + dz;
        pulse$lastX = dx;
        pulse$lastZ = dz;
    }

    @Unique
    private ModelPart pulse$cape() {
        try {
            return ((Model) (Object) this).root().getChild("cape");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
