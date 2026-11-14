package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;

public final class BreakReminderModule extends AlertModule {
    private final SliderSetting minutes = add(new SliderSetting("Remind every", 15, 180, 15, 60, "min"));
    private long nextAt;

    public BreakReminderModule() {
        super("breakreminder", "Break Reminder", "Reminds you to stretch and rest your eyes.", Category.UTILITY);
    }

    @Override
    protected String check(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (nextAt == 0) nextAt = now + (long) (minutes.value * 60_000);
        if (now < nextAt) return null;
        nextAt = now + (long) (minutes.value * 60_000);
        return "Time for a break. Stretch and rest your eyes.";
    }

    @Override
    public void onEnable() {
        nextAt = 0;
    }
}
