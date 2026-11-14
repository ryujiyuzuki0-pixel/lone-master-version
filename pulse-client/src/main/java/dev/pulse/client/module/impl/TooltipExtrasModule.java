package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/** Adds exact durability and the item's registry id to item tooltips. */
public final class TooltipExtrasModule extends Module {
    private static boolean registered;
    private final BoolSetting durability = add(new BoolSetting("Exact durability", true));
    private final BoolSetting itemId = add(new BoolSetting("Item id", false));

    public TooltipExtrasModule() {
        super("tooltipextras", "Tooltip Extras", "Exact durability and item ids in tooltips.", Category.UTILITY, false, 0f, 0f, false);
        if (!registered) {
            registered = true;
            ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
                if (!enabled) return;
                if (durability.value && stack.isDamageableItem()) {
                    lines.add(Component.literal("Durability: " + (stack.getMaxDamage() - stack.getDamageValue()) + " / "
                            + stack.getMaxDamage()).withStyle(ChatFormatting.GRAY));
                }
                if (itemId.value) lines.add(Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).withStyle(ChatFormatting.DARK_GRAY));
            });
        }
    }
}
