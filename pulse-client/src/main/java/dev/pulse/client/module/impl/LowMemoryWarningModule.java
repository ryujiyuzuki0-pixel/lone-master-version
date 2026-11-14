package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import net.minecraft.client.Minecraft;

public final class LowMemoryWarningModule extends AlertModule {
    public LowMemoryWarningModule() {
        super("lowmemory", "Low Memory Warning", "Warns before Java runs out of memory, which causes lag spikes and crashes.", Category.UTILITY);
    }

    @Override
    protected String check(Minecraft mc) {
        Runtime r = Runtime.getRuntime();
        float used = (r.totalMemory() - r.freeMemory()) / (float) r.maxMemory();
        return used > 0.92f ? "Memory almost full (" + Math.round(used * 100) + "%). Allocate more RAM." : null;
    }
}
