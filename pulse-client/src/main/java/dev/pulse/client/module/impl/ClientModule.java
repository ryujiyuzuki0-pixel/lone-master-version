package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import org.lwjgl.glfw.GLFW;

/** Hidden module that holds the client-wide settings (the "Settings" page). */
public final class ClientModule extends Module {
    private final KeybindSetting menuKey = add(new KeybindSetting("Menu key", GLFW.GLFW_KEY_RIGHT_SHIFT), "Interface");
    private final SliderSetting animSpeed = add(new SliderSetting("Animation speed", 0.5, 2.0, 0.1, 1.0, "x"), "Interface");
    private final SliderSetting dim = add(new SliderSetting("Background dim", 0, 90, 5, 55, "%"), "Interface");
    private final BoolSetting loneTitle = add(new BoolSetting("Lone title screen", true), "Title screen");
    private final BoolSetting titleAnim = add(new BoolSetting("Animated background", true), "Title screen", () -> this.loneTitle.value);
    private final SliderSetting titleSpeed = add(new SliderSetting("Background speed", 0.2, 3.0, 0.1, 1.0, "x"), "Title screen", () -> this.loneTitle.value);
    private final BoolSetting titleSnow = add(new BoolSetting("Falling snow", true), "Title screen", () -> this.loneTitle.value);
    private final BoolSetting titlePlayer = add(new BoolSetting("Show player", true), "Title screen", () -> this.loneTitle.value);
    private final BoolSetting titleVersion = add(new BoolSetting("Show version", true), "Title screen", () -> this.loneTitle.value);

    private final BoolSetting showLogos = add(new BoolSetting("Show Lone logo on users", true), "Lone users");
    private final BoolSetting shareUse = add(new BoolSetting("Let others see I use Lone Client", true), "Lone users");

    public ClientModule() {
        super("client", "Client Settings", "Menu key, animations and the title screen.", Category.UTILITY, false, 0f, 0f, false);
        hidden = true;
        enabled = true;
        defaultEnabled = true;
    }

    public boolean showLogos() { return showLogos.value; }

    public boolean shareUse() { return shareUse.value; }

    public int menuKey() { return menuKey.key; }

    public float animSpeed() { return (float) animSpeed.value; }

    public float dim() { return (float) (dim.value / 100.0); }

    public boolean loneTitle() { return loneTitle.value; }

    public boolean titleAnimated() { return titleAnim.value; }

    public float titleSpeed() { return (float) titleSpeed.value; }

    public boolean titleSnow() { return titleSnow.value; }

    public boolean titlePlayer() { return titlePlayer.value; }

    public boolean titleVersion() { return titleVersion.value; }
}
