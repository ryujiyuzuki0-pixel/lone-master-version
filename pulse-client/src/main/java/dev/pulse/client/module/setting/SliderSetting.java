package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public final class SliderSetting extends Setting {
    public final double min, max, step;
    public final String suffix;
    public double value;
    /** Shown instead of the number when the slider sits at its minimum (e.g. "Off", "Never"). */
    public String zeroText;
    /** Runs when the player moves the slider in the menu (not when the config is loaded). */
    public Runnable onChange;
    private final double def;

    public SliderSetting(String name, double min, double max, double step, double value, String suffix) {
        super(name);
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix;
        set(value);
        this.def = this.value;
    }

    public SliderSetting zero(String text) {
        this.zeroText = text;
        return this;
    }

    public void set(double v) {
        v = Math.max(min, Math.min(max, v));
        v = Math.round((v - min) / step) * step + min;
        value = Math.round(v * 10000.0) / 10000.0;
    }

    public double normalized() {
        return (value - min) / (max - min);
    }

    public void setNormalized(double t) {
        set(min + Math.max(0, Math.min(1, t)) * (max - min));
        if (onChange != null) onChange.run();
    }

    /** Moves by whole steps (mouse wheel). */
    public void nudge(int steps) {
        set(value + steps * step);
        if (onChange != null) onChange.run();
    }

    public int intValue() {
        return (int) Math.round(value);
    }

    public String format() {
        if (zeroText != null && value <= min) return zeroText;
        if (step % 1.0 == 0) return (int) Math.round(value) + suffix;
        int decimals = Math.abs(step * 10 - Math.round(step * 10)) < 1e-9 ? 1 : 2;
        return String.format(Locale.ROOT, "%." + decimals + "f", value) + suffix;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement e) {
        set(e.getAsDouble());
    }

    @Override
    public void reset() {
        set(def);
        if (onChange != null) onChange.run();
    }
}
