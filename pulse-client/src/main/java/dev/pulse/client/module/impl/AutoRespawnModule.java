package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

public final class AutoRespawnModule extends Module {
    private final SliderSetting delay = add(new SliderSetting("Delay", 0, 5, 0.25, 1, "s"));
    private int deadTicks;

    public AutoRespawnModule() {
        super("autorespawn", "Auto Respawn", "Respawns for you after a short delay. Hardcore worlds are left alone.",
                Category.GAMEPLAY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !(mc.screen instanceof DeathScreen) || mc.level.getLevelData().isHardcore()) {
            deadTicks = 0;
            return;
        }
        if (++deadTicks >= delay.value * 20) {
            mc.player.respawn();
            deadTicks = 0;
        }
    }
}
