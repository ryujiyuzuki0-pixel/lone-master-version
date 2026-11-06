package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class ClockModule extends LineModule {
    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter H24S = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter H12S = DateTimeFormatter.ofPattern("h:mm:ss a");
    private final BoolSetting hour24 = add(new BoolSetting("24-hour", true));
    private final BoolSetting seconds = add(new BoolSetting("Show seconds", false));

    public ClockModule() {
        super("clock", "Clock", "Your real-world time.", Category.HUD, 0.008f, 0.28f);
    }

    @Override protected String label() { return "TIME"; }

    @Override
    protected String value(Minecraft mc) {
        DateTimeFormatter f = hour24.value ? (seconds.value ? H24S : H24) : (seconds.value ? H12S : H12);
        return LocalTime.now().format(f);
    }
}
