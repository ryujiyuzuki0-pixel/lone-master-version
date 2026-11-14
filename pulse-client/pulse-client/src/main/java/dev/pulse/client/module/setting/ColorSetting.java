package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.pulse.client.ui.Theme;

import java.util.Locale;

/**
 * A fully editable color: hue / saturation / brightness picker, optional opacity, and optional "modes"
 * that follow the client theme or cycle through the rainbow.
 */
public final class ColorSetting extends Setting {
    public static final String[] MODES = {"Custom", "Accent", "Accent 2", "Rainbow"};

    public int rgb;
    public int alpha;
    /** 0 = custom color, 1 = theme accent, 2 = theme accent 2, 3 = rainbow. */
    public int mode;
    public float hue, sat, val;
    public final boolean hasAlpha, hasModes;
    private final int defRgb, defAlpha;
    private int defMode;

    public ColorSetting(String name, int argb, boolean hasAlpha, boolean hasModes) {
        super(name);
        this.hasAlpha = hasAlpha;
        this.hasModes = hasModes;
        this.alpha = hasAlpha ? (argb >>> 24) & 0xFF : 255;
        this.defAlpha = alpha;
        this.defRgb = argb & 0xFFFFFF;
        setRgb(argb);
    }

    /** Sets the starting mode (also the value Reset goes back to). */
    public ColorSetting withMode(int m) {
        this.mode = m;
        this.defMode = m;
        return this;
    }

    public void setRgb(int c) {
        rgb = c & 0xFFFFFF;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        float max = Math.max(r, Math.max(g, b)) / 255f, min = Math.min(r, Math.min(g, b)) / 255f;
        float d = max - min;
        float h;
        if (d < 1e-5f) {
            h = hue;
        } else if (max == r / 255f) {
            h = 60f * (((g - b) / 255f / d) % 6f);
        } else if (max == g / 255f) {
            h = 60f * ((b - r) / 255f / d + 2f);
        } else {
            h = 60f * ((r - g) / 255f / d + 4f);
        }
        if (h < 0) h += 360f;
        hue = h;
        sat = max <= 0f ? 0f : d / max;
        val = max;
    }

    public void setHsv(float h, float s, float v) {
        hue = ((h % 360f) + 360f) % 360f;
        sat = Math.max(0f, Math.min(1f, s));
        val = Math.max(0f, Math.min(1f, v));
        rgb = Theme.hsb(hue, sat, val) & 0xFFFFFF;
    }

    /** The color to draw with, as ARGB. Animated for the rainbow mode. */
    public int color() {
        int c;
        switch (mode) {
            case 1 -> c = Theme.ACCENT;
            case 2 -> c = Theme.ACCENT2;
            case 3 -> c = Theme.hsb((System.currentTimeMillis() / 12L) % 360, 0.65f, 1f);
            default -> c = rgb;
        }
        return (alpha << 24) | (c & 0xFFFFFF);
    }

    public String label() {
        if (mode != 0) return MODES[mode];
        return String.format(Locale.ROOT, "#%06X", rgb);
    }

    @Override
    public JsonElement toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("color", String.format(Locale.ROOT, "#%06X", rgb));
        if (hasAlpha) o.addProperty("alpha", alpha);
        return o;
    }

    @Override
    public void fromJson(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("color")) setRgb(Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16));
            if (o.has("alpha") && hasAlpha) alpha = Math.max(0, Math.min(255, o.get("alpha").getAsInt()));
            if (o.has("mode") && hasModes) mode = Math.max(0, Math.min(MODES.length - 1, o.get("mode").getAsInt()));
        } else if (e instanceof JsonPrimitive p && p.isNumber()) {
            setRgb(p.getAsInt());
        }
    }

    @Override
    public void reset() {
        setRgb(defRgb);
        alpha = defAlpha;
        mode = defMode;
    }
}
