package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.ShulkerTooltipModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shulker Tooltip hook. When the hovered slot holds a shulker box with contents, Lone Client draws its own grid
 * tooltip and cancels the vanilla one. If a Minecraft version renames the tooltip method (require = 0, so the
 * client still starts), the grid is drawn at the end of the screen render instead, next to the vanilla tooltip.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin<T extends AbstractContainerMenu> {
    @Shadow protected Slot hoveredSlot;

    @Unique private boolean pulse$drawn;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), require = 0)
    private void pulse$frameStart(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        pulse$drawn = false;
    }

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pulse$shulker(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (ShulkerTooltipModule.tryDraw(graphics, mouseX, mouseY, hoveredSlot, pulse$carriedEmpty())) {
            pulse$drawn = true;
            ci.cancel();
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 0)
    private void pulse$shulkerFallback(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!pulse$drawn) ShulkerTooltipModule.tryDraw(graphics, mouseX, mouseY, hoveredSlot, pulse$carriedEmpty());
    }

    @Unique
    private boolean pulse$carriedEmpty() {
        return ((AbstractContainerScreen<?>) (Object) this).getMenu().getCarried().isEmpty();
    }
}
