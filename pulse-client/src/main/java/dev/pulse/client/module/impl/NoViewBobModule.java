package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class NoViewBobModule extends Module {
    private final OptionHold<Boolean> hold = new OptionHold<>(Options::bobView);

    public NoViewBobModule() {
        super("noviewbob", "No View Bobbing", "Keeps the camera steady while you walk.", Category.VISUAL, false, 0f, 0f, false);
    }

    @Override public void tick(Minecraft mc) { hold.apply(mc, Boolean.FALSE); }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
