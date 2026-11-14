package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.ActionSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** How many times you have attacked since the counter was last reset. */
public final class HitCounterModule extends LineModule {
    private int hits;

    public HitCounterModule() {
        super("hitcounter", "Hit Counter", "Counts your attacks.", Category.HUD, 0.992f, 0.26f);
        add(new ActionSetting("Reset counter", "Reset", () -> hits = 0));
    }

    @Override protected String label() { return "HITS"; }

    @Override protected String value(Minecraft mc) { return Integer.toString(hits); }

    @Override public void onAttackHit(Player player, Entity target) { hits++; }
}
