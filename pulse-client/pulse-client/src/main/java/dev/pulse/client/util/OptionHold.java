package dev.pulse.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.util.Objects;
import java.util.function.Function;

/**
 * Overrides one vanilla option for as long as a module wants and puts the player's own value back afterwards.
 * Releasing also saves options.txt so a clean exit never leaves a changed value behind.
 */
public final class OptionHold<T> {
    private final Function<Options, OptionInstance<T>> option;
    private T original;
    private boolean held;

    public OptionHold(Function<Options, OptionInstance<T>> option) {
        this.option = option;
    }

    /** Sets the option to {@code value}, remembering what it was the first time. */
    public void apply(Minecraft mc, T value) {
        if (mc.options == null) return;
        OptionInstance<T> o = option.apply(mc.options);
        if (!held) {
            original = o.get();
            held = true;
        }
        if (!Objects.equals(o.get(), value)) o.set(value);
    }

    public boolean held() {
        return held;
    }

    /** Restores the remembered value. Safe to call when nothing is held. */
    public void release(Minecraft mc) {
        if (!held || mc.options == null) return;
        held = false;
        option.apply(mc.options).set(original);
        mc.options.save();
    }
}
