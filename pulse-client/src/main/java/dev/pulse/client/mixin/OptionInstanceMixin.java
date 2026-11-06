package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.CustomBrightnessModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Custom Brightness hook. The light texture reads the Brightness (gamma) option through OptionInstance#get, so
 * Custom Brightness answers that read with its own value. The option itself, its slider and options.txt are not
 * modified. Only the gamma instance is touched (one identity comparison for every other option). While the
 * Video Settings screen is open the player's real slider value is shown. require = 0: if a Minecraft version
 * changes this, the client still starts and Custom Brightness reports "not supported".
 */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin<T> {
    private static OptionInstance<?> pulse$gamma;

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true, require = 0)
    private void pulse$brightness(CallbackInfoReturnable<T> cir) {
        OptionInstance<?> g = pulse$gamma;
        if (g == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return;
            g = pulse$gamma = mc.options.gamma();
        }
        if ((Object) this != g || !(cir.getReturnValue() instanceof Double vanilla)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null && mc.screen.getClass().getName().contains("VideoSettingsScreen")) return;
        cir.setReturnValue((T) Double.valueOf(CustomBrightnessModule.gamma(vanilla)));
    }
}
