package dev.pulse.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.pulse.client.module.impl.ViewmodelModule;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Viewmodel hooks on the first-person hand renderer. All four are picked by argument type or position, not by an
 * exact descriptor, and are require = 0: if a Minecraft version reshapes renderArmWithItem the client still starts,
 * the hand is drawn as in vanilla and the Viewmodel module reports "not supported".
 * <ul>
 *   <li>hand (the only InteractionHand argument): remembered for the other hooks, returned unchanged;</li>
 *   <li>swing / equip progress (the third and fourth float arguments after partial tick and pitch);</li>
 *   <li>pose: changed right after the method's own pushPose, so the method's own popPose undoes it.</li>
 * </ul>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, require = 0)
    private InteractionHand pulse$hand(InteractionHand hand) {
        return ViewmodelModule.hand(hand);
    }

    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private float pulse$swing(float swingProgress) {
        return ViewmodelModule.swing(swingProgress);
    }

    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private float pulse$equip(float equipProgress) {
        return ViewmodelModule.equip(equipProgress);
    }

    @ModifyVariable(method = "renderArmWithItem",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0),
            argsOnly = true, require = 0)
    private PoseStack pulse$pose(PoseStack pose) {
        ViewmodelModule.apply(pose);
        return pose;
    }
}
