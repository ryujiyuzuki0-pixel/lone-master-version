package dev.pulse.client.module.impl;

import dev.pulse.client.Brand;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import net.minecraft.client.Minecraft;

/** Puts the client name (and optionally the server) in the window title and taskbar. */
public final class WindowTitleModule extends Module {
    private final BoolSetting server = add(new BoolSetting("Show server", true));
    private int ticks;
    private String applied = "";

    public WindowTitleModule() {
        super("windowtitle", "Window Title", "Shows the client name in the window title.", Category.UTILITY, false, 0f, 0f, false);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.getWindow() == null || ++ticks % 20 != 0) return;
        String t = Brand.versionLine();
        if (server.value && mc.getCurrentServer() != null && !StreamerModeModule.active()) t += " | " + mc.getCurrentServer().ip;
        if (!t.equals(applied)) {
            mc.getWindow().setTitle(t);
            applied = t;
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        applied = "";
        if (mc.getWindow() != null) mc.updateTitle();
    }
}
