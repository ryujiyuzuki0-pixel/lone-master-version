package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.CustomF3Module;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla F3 text while Custom F3 is on. Targets by name and uses require = 0, so if a Minecraft
 * version moves this method the client still starts (the vanilla text would then simply stay visible).
 */
@Mixin(targets = "net.minecraft.client.gui.components.DebugScreenOverlay")
public abstract class DebugOverlayMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$hideVanilla(GuiGraphics graphics, CallbackInfo ci) {
        if (CustomF3Module.suppressVanilla()) ci.cancel();
    }
}
