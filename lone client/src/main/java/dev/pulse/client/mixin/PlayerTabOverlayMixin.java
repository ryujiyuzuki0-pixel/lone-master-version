package dev.pulse.client.mixin;

import dev.pulse.client.presence.Presence;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lone Client logo in front of fellow Lone Client users in the tab list. */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true, require = 0)
    private void pulse$logo(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (Presence.isUser(info.getProfile().id())) cir.setReturnValue(Presence.tag(cir.getReturnValue()));
    }
}
