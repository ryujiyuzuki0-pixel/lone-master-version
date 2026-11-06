package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BoolSetting extends Setting {
    public boolean value;
    public float anim;
    private final boolean def;

    public BoolSetting(String name, boolean value) {
        super(name);
        this.value = value;
        this.def = value;
        this.anim = value ? 1f : 0f;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement e) {
        value = e.getAsBoolean();
        anim = value ? 1f : 0f;
    }

    @Override
    public void reset() {
        value = def;
    }
}
