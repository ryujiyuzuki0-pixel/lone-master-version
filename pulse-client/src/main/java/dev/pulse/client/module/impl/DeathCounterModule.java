package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.ActionSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/** Deaths this game session. */
public final class DeathCounterModule extends LineModule {
    private int deaths;
    private boolean wasDead;

    public DeathCounterModule() {
        super("deathcounter", "Death Counter", "Counts your deaths this session.", Category.HUD, 0.992f, 0.22f);
        add(new ActionSetting("Reset counter", "Reset", () -> deaths = 0));
    }

    @Override protected String label() { return "DEATHS"; }

    @Override protected String value(Minecraft mc) { return Integer.toString(deaths); }

    @Override
    public void tick(Minecraft mc) {
        boolean dead = mc.screen instanceof DeathScreen;
        if (dead && !wasDead) deaths++;
        wasDead = dead;
    }
}
