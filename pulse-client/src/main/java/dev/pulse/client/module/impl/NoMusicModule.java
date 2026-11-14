package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

public final class NoMusicModule extends Module {
    private final OptionHold<Double> hold = new OptionHold<>(o -> o.getSoundSourceOptionInstance(SoundSource.MUSIC));

    public NoMusicModule() {
        super("nomusic", "No Music", "Silences the background music and keeps every other sound.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override public void tick(Minecraft mc) { hold.apply(mc, 0.0); }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
