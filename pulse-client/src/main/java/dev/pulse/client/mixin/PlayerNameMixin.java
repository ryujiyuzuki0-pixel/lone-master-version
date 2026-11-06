package dev.pulse.client.mixin;

import dev.pulse.client.presence.Presence;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lone Client logo in front of fellow Lone Client users' nametags. */
@Mixin(Player.class)
public abstract class PlayerNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void pulse$logo(CallbackInfoReturnable<Component> cir) {
        if ((Object) this instanceof AbstractClientPlayer p && Presence.isUser(p.getUUID())) {
            cir.setReturnValue(Presence.tag(cir.getReturnValue()));
        }
    }
}
