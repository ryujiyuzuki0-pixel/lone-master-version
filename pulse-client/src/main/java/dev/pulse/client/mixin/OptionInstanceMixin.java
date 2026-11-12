package dev.pulse.client.mixin;

import dev.pulse.client.module.impl.CustomBrightnessModule;
import dev.pulse.client.module.impl.FovModule;
import dev.pulse.client.util.OptionGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Option read hooks. Two options are answered by the client while the player's own value stays untouched:
 * <ul>
 *   <li>Brightness (gamma): Custom Brightness. The light texture reads it through OptionInstance#get.</li>
 *   <li>Field of view: FOV Changer. The game multiplies the value by its dynamic effects (sprint, speed, bow)
 *       afterwards, so those effects keep working.</li>
 * </ul>
 * The option itself, its slider and options.txt are never modified: reads are passed through while Options#save
 * runs and while the Video Settings screen is open (see OptionGuard). Only those two instances are touched (two
 * identity comparisons for every other option). require = 0: if a Minecraft version changes this, the client still
 * starts and the modules report "not supported" or simply do nothing.
 */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin<T> {
    private static OptionInstance<?> pulse$gamma;
    private static OptionInstance<?> pulse$fov;

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true, require = 0)
    private void pulse$brightness(CallbackInfoReturnable<T> cir) {
        OptionInstance<?> g = pulse$gamma;
        OptionInstance<?> f = pulse$fov;
        if (g == null || f == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return;
            g = pulse$gamma = mc.options.gamma();
            f = pulse$fov = mc.options.fov();
        }
        Object self = this;
        if (self != g && self != f) return;
        Object value = cir.getReturnValue();
        if (OptionGuard.passThrough()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || OptionGuard.inVideoSettings(mc)) return;
        if (self == g) {
            if (value instanceof Double vanilla) cir.setReturnValue((T) Double.valueOf(CustomBrightnessModule.gamma(vanilla)));
        } else if (value instanceof Integer) {
            int fov = FovModule.override();
            if (fov > 0) cir.setReturnValue((T) Integer.valueOf(fov));
        }
    }
}
