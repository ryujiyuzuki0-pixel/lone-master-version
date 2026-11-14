package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.ZoomModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets Zoom use the scroll wheel (instead of the hotbar) while you are zoomed in. require = 0: without it zoom still works, only scroll adjust is lost. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen == null && ZoomModule.consumeScroll(yOffset)) ci.cancel();
    }
}
