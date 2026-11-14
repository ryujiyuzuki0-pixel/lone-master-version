package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import net.minecraft.client.Minecraft;

/** Clears rain and thunder on your screen only; the server's weather is untouched. */
public final class NoRainModule extends Module {
    public NoRainModule() {
        super("norain", "Clear Weather", "Hides rain, snow and thunder visuals.", Category.VISUAL, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.level == null) return;
        mc.level.setRainLevel(0f);
        mc.level.setThunderLevel(0f);
    }
}
