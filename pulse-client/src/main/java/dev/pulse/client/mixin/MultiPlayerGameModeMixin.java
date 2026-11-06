package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.DropPreventionModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every inventory click goes through here. Drop Prevention only cancels clicks that would throw a protected item
 * out of the inventory (Q over a slot, clicking outside the window with the item on the cursor).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick(IIILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$guardDrop(int containerId, int slotId, int button, ClickType clickType, Player player, CallbackInfo ci) {
        if (DropPreventionModule.interceptClick((MultiPlayerGameMode) (Object) this, containerId, slotId, button, clickType, player)) {
            ci.cancel();
        }
    }
}
