package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Counts of the usual PvP consumables in your inventory. */
public final class ItemCountersModule extends Module {
    private static final Item[][] GROUPS = {
            {Items.ENDER_PEARL},
            {Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE},
            {Items.END_CRYSTAL},
            {Items.EXPERIENCE_BOTTLE}};
    private static final ItemStack[] ICONS = {
            new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.GOLDEN_APPLE),
            new ItemStack(Items.END_CRYSTAL), new ItemStack(Items.EXPERIENCE_BOTTLE)};

    private final BoolSetting pearls = add(new BoolSetting("Ender pearls", true), "Tracked items");
    private final BoolSetting apples = add(new BoolSetting("Golden apples", true), "Tracked items");
    private final BoolSetting crystals = add(new BoolSetting("End crystals", true), "Tracked items");
    private final BoolSetting xp = add(new BoolSetting("XP bottles", true), "Tracked items");
    private final BoolSetting hideZero = add(new BoolSetting("Hide when zero", false));
    private final BoolSetting prefix = add(new BoolSetting("Show \"x\" prefix", true));
    private final ChoiceSetting zeroColor = add(new ChoiceSetting("Zero count color", 0, "Red", "Normal"));
    private final SliderSetting rowGap = add(new SliderSetting("Row spacing", 0, 8, 1, 0, "px"), "Layout");

    public ItemCountersModule() {
        super("item_counters", "Item Counters", "Pearls, gapples, crystals and XP bottles.", Category.HUD,
                true, 0.008f, 0.25f, true);
    }

    private boolean tracked(int group) {
        return switch (group) {
            case 0 -> pearls.value;
            case 1 -> apples.value;
            case 2 -> crystals.value;
            default -> xp.value;
        };
    }

    private int count(Minecraft mc, int group) {
        if (mc.player == null) return 0;
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            for (Item it : GROUPS[group]) if (s.is(it)) n += s.getCount();
        }
        return n;
    }

    private int rows(Minecraft mc) {
        int n = 0;
        for (int i = 0; i < GROUPS.length; i++) if (tracked(i) && (!hideZero.value || count(mc, i) > 0)) n++;
        return n;
    }

    private int row() { return 19 + rowGap.intValue(); }

    @Override public int width(Minecraft mc) { return 56; }

    @Override
    public int height(Minecraft mc) {
        int n = rows(mc);
        return n == 0 ? 0 : n * row() + 3 - rowGap.intValue();
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int r = 0;
        for (int i = 0; i < GROUPS.length; i++) {
            if (!tracked(i)) continue;
            int c = count(mc, i);
            if (hideZero.value && c == 0) continue;
            int y = 3 + r++ * row();
            g.renderItem(ICONS[i], 4, y);
            int color = c == 0 && zeroColor.index == 0 ? Theme.BAD : textColor();
            text(g, (prefix.value ? "x" : "") + c, 26, y + 4, color);
        }
    }
}
