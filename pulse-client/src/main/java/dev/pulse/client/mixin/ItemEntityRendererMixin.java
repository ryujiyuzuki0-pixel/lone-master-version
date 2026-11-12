package dev.pulse.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.pulse.client.module.impl.ItemPhysicsModule;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Item Physics: bob wave, tilt and ground offset of dropped items. Both redirects are require = 0 with explicit
 * descriptors and only touch the first matching call, so if a Minecraft version reshapes the renderer the client
 * still starts and items just look vanilla.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Redirect(method = {"submit", "render"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(F)F", ordinal = 0), require = 0)
    private float pulse$bob(float v) {
        return ItemPhysicsModule.bob(v, Mth.sin(v));
    }

    @Redirect(method = {"submit", "render"},
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0), require = 0)
    private void pulse$translate(PoseStack pose, float x, float y, float z) {
        pose.translate(x, y + ItemPhysicsModule.groundOffset(), z);
        float tilt = ItemPhysicsModule.tiltDegrees();
        if (tilt != 0f) pose.mulPose(Axis.XP.rotationDegrees(tilt));
    }
}
