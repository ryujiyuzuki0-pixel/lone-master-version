package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

public final class PingModule extends LineModule {
    private final BoolSetting unit = add(new BoolSetting("Show unit", true));
    private final BoolSetting dynamic = add(new BoolSetting("Dynamic color", true), "Colors");
    private final SliderSetting good = add(new SliderSetting("Good below", 20, 200, 5, 80, " ms"), "Colors", () -> this.dynamic.value);
    private final SliderSetting warn = add(new SliderSetting("Warn below", 60, 400, 10, 160, " ms"), "Colors", () -> this.dynamic.value);

    public PingModule() {
        super("ping", "Ping", "Latency to the server.", Category.HUD, 0.008f, 0.088f);
    }

    private int ping(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    @Override protected String label() { return "PING"; }

    @Override protected String value(Minecraft mc) { return ping(mc) + (unit.value ? " ms" : ""); }

    @Override
    protected int valueColor(Minecraft mc) {
        if (!dynamic.value) return textColor();
        int p = ping(mc);
        return p < good.value ? Theme.GOOD : p < warn.value ? Theme.WARN : Theme.BAD;
    }
}
