package dev.pulse.client.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** A keyboard key (GLFW key code). -1 means unbound. */
public final class KeybindSetting extends Setting {
    public int key;
    private final int def;

    public KeybindSetting(String name, int key) {
        super(name);
        this.key = key;
        this.def = key;
    }

    public boolean isDown(Minecraft mc) {
        return key > 0 && mc.getWindow() != null
                && GLFW.glfwGetKey(mc.getWindow().handle(), key) == GLFW.GLFW_PRESS;
    }

    public String display() {
        return keyName(key);
    }

    public static String keyName(int key) {
        if (key <= 0) return "None";
        switch (key) {
            case GLFW.GLFW_KEY_SPACE: return "Space";
            case GLFW.GLFW_KEY_ENTER: return "Enter";
            case GLFW.GLFW_KEY_TAB: return "Tab";
            case GLFW.GLFW_KEY_BACKSPACE: return "Backspace";
            case GLFW.GLFW_KEY_INSERT: return "Insert";
            case GLFW.GLFW_KEY_DELETE: return "Delete";
            case GLFW.GLFW_KEY_HOME: return "Home";
            case GLFW.GLFW_KEY_END: return "End";
            case GLFW.GLFW_KEY_PAGE_UP: return "Page Up";
            case GLFW.GLFW_KEY_PAGE_DOWN: return "Page Down";
            case GLFW.GLFW_KEY_UP: return "Up";
            case GLFW.GLFW_KEY_DOWN: return "Down";
            case GLFW.GLFW_KEY_LEFT: return "Left";
            case GLFW.GLFW_KEY_RIGHT: return "Right";
            case GLFW.GLFW_KEY_LEFT_SHIFT: return "L Shift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return "R Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL: return "L Ctrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return "R Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT: return "L Alt";
            case GLFW.GLFW_KEY_RIGHT_ALT: return "R Alt";
            case GLFW.GLFW_KEY_CAPS_LOCK: return "Caps Lock";
            default: break;
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        String n = GLFW.glfwGetKeyName(key, 0);
        if (n != null && !n.isEmpty()) return n.toUpperCase();
        return "Key " + key;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(key);
    }

    @Override
    public void fromJson(JsonElement e) {
        key = e.getAsInt();
    }

    @Override
    public void reset() {
        key = def;
    }
}
