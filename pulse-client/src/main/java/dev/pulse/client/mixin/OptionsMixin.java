package dev.pulse.client.mixin;

import dev.pulse.client.util.OptionGuard;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the time options.txt is written, so Custom Brightness and the FOV Changer never leak their values into the
 * player's saved options. require = 0: without it the hooks still work, only the file could pick up their values.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "save", at = @At("HEAD"), require = 0)
    private void pulse$saveStart(CallbackInfo ci) {
        OptionGuard.saving = true;
    }

    @Inject(method = "save", at = @At("RETURN"), require = 0)
    private void pulse$saveEnd(CallbackInfo ci) {
        OptionGuard.saving = false;
    }
}
