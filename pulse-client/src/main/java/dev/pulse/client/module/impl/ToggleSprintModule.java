package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class ToggleSprintModule extends Module {
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Hold forward", "Always"));
    private final BoolSetting showStatus = add(new BoolSetting("Show status on HUD", true));
    private final ChoiceSetting statusText = add(new ChoiceSetting("Status text", 0,
            "Sprinting (Toggled)", "[Sprinting]", "Sprint"), () -> this.showStatus.value);
    private boolean pressedByUs;

    public ToggleSprintModule() {
        super("sprint", "Toggle Sprint", "Sprint automatically while you move forward.",
                Category.GAMEPLAY, true, 0.008f, 0.32f, true);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null) return;
        boolean want = mc.screen == null && (mode.index == 1 || mc.options.keyUp.isDown());
        if (want) {
            mc.options.keySprint.setDown(true);
            pressedByUs = true;
        } else if (pressedByUs) {
            mc.options.keySprint.setDown(false);
            pressedByUs = false;
        }
    }

    private boolean visible() {
        return showStatus.value && (pressedByUs || HudRenderer.editing());
    }

    @Override
    public int width(Minecraft mc) {
        return visible() ? 12 + Theme.width(statusText.get()) : 0;
    }

    @Override
    public int height(Minecraft mc) {
        return visible() ? 17 : 0;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        text(g, statusText.get(), 6, 5, textColor());
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (pressedByUs && mc.options != null) mc.options.keySprint.setDown(false);
        pressedByUs = false;
    }
}
