package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class NoFovEffectsModule extends Module {
    private final OptionHold<Double> hold = new OptionHold<>(Options::fovEffectScale);

    public NoFovEffectsModule() {
        super("nofoveffects", "No FOV Effects", "Sprinting, speed and flying no longer change your field of view.",
                Category.VISUAL, false, 0f, 0f, false);
    }

    @Override public void tick(Minecraft mc) { hold.apply(mc, 0.0); }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
