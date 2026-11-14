package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.ItemPhysicsModule;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item Physics: the angle a dropped item spins at. Visual only, the entity is untouched. require = 0 with an
 * explicit descriptor: if a Minecraft version changes this method the client still starts and items spin as in
 * vanilla.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "getSpin(FF)F", at = @At("RETURN"), cancellable = true, require = 0)
    private static void pulse$spin(float age, float bobOffset, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(ItemPhysicsModule.spin(age, bobOffset, cir.getReturnValueF()));
    }
}
