package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.util.OptionHold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Chat opacity, text size and width in one place, applied only while the module is on. */
public final class ChatTweaksModule extends Module {
    private final SliderSetting opacity = add(new SliderSetting("Opacity", 10, 100, 5, 70, "%"));
    private final SliderSetting size = add(new SliderSetting("Text size", 50, 100, 5, 100, "%"));
    private final SliderSetting width = add(new SliderSetting("Width", 40, 100, 5, 100, "%"));
    private final OptionHold<Double> opacityHold = new OptionHold<>(Options::chatOpacity);
    private final OptionHold<Double> sizeHold = new OptionHold<>(Options::chatScale);
    private final OptionHold<Double> widthHold = new OptionHold<>(Options::chatWidth);

    public ChatTweaksModule() {
        super("chattweaks", "Chat Tweaks", "Chat opacity, text size and width.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        opacityHold.apply(mc, opacity.value / 100.0);
        sizeHold.apply(mc, size.value / 100.0);
        widthHold.apply(mc, width.value / 100.0);
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        opacityHold.release(mc);
        sizeHold.release(mc);
        widthHold.release(mc);
    }
}
