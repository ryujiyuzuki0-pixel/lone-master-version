package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting {
    public final String name;
    /** Heading this setting is listed under in the menu (null = "General"). */
    public String group;
    /** False for settings that are derived or are just buttons (never written to the config). */
    public boolean persist = true;
    /** When set and it returns false, the setting is hidden in the menu. */
    public BooleanSupplier visible;

    protected Setting(String name) {
        this.name = name;
    }

    public boolean isVisible() {
        return visible == null || visible.getAsBoolean();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement e);

    /** Back to the default value. */
    public abstract void reset();
}
