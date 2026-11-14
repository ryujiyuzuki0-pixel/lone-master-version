package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import net.minecraft.client.Minecraft;

public final class InventoryAlertModule extends AlertModule {
    public InventoryAlertModule() {
        super("inventoryalert", "Inventory Alert", "Warns when your inventory is full.", Category.UTILITY);
    }

    @Override
    protected String check(Minecraft mc) {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return null;
        return "Inventory full";
    }
}
