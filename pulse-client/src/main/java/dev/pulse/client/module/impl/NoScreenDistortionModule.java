package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class NoScreenDistortionModule extends Module {
    private final SliderSetting strength = add(new SliderSetting("Distortion", 0, 100, 5, 0, "%"));
    private final OptionHold<Double> hold = new OptionHold<>(Options::screenEffectScale);

    public NoScreenDistortionModule() {
        super("noscreendistortion", "Screen Distortion", "Tones down the nausea and portal wobble.", Category.VISUAL, false, 0f, 0f, false);
    }

    @Override public void tick(Minecraft mc) { hold.apply(mc, strength.value / 100.0); }

    @Override public void onDisable() { hold.release(Minecraft.getInstance()); }
}
