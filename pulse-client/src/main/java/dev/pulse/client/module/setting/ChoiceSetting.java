package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class ChoiceSetting extends Setting {
    public final String[] options;
    public int index;
    private final int def;
    private Runnable onChange;

    public ChoiceSetting(String name, int index, String... options) {
        super(name);
        this.options = options;
        this.index = Math.max(0, Math.min(options.length - 1, index));
        this.def = this.index;
    }

    /** Runs when the player cycles this setting in the menu (not when the config is loaded). */
    public ChoiceSetting onChange(Runnable r) {
        onChange = r;
        return this;
    }

    public String get() {
        return options[index];
    }

    public void cycle() {
        index = (index + 1) % options.length;
        if (onChange != null) onChange.run();
    }

    public void cycleBack() {
        index = (index - 1 + options.length) % options.length;
        if (onChange != null) onChange.run();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(get());
    }

    @Override
    public void fromJson(JsonElement e) {
        String s = e.getAsString();
        for (int i = 0; i < options.length; i++) if (options[i].equals(s)) index = i;
    }

    @Override
    public void reset() {
        index = def;
    }
}
