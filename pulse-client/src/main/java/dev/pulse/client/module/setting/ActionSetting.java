package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/** A button row in a module's settings. Nothing is stored. */
public final class ActionSetting extends Setting {
    public final String label;
    private final Runnable action;

    public ActionSetting(String name, String label, Runnable action) {
        super(name);
        this.label = label;
        this.action = action;
        this.persist = false;
    }

    public void run() {
        action.run();
    }

    @Override
    public JsonElement toJson() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void fromJson(JsonElement e) {
    }

    @Override
    public void reset() {
    }
}
