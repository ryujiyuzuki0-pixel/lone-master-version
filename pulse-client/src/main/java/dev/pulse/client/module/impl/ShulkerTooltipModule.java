package dev.pulse.client.module.impl;

import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows what is inside a shulker box when you hover it in any container screen, as an inventory-style grid with
 * item icons, stack amounts, enchantment glints and custom names.
 * <p>
 * The box's contents are read from its item data on the client, so this works on every server and shows nothing
 * the server did not already send. Hover a shulker and the vanilla tooltip is replaced (see ContainerScreenMixin);
 * empty boxes keep the vanilla tooltip. The contents are copied once per stack, not every frame.
 */
public final class ShulkerTooltipModule extends Module {
    private static ShulkerTooltipModule instance;
    private static final int SLOTS = 27;
    /** Colour of an undyed shulker box. */
    private static final int DEFAULT_SHULKER = 0x8B668B;

    private final BoolSetting holdKey = add(new BoolSetting("Only while key held", false), "General");
    private final KeybindSetting peekKey = add(new KeybindSetting("Peek key", GLFW.GLFW_KEY_LEFT_ALT), "General", () -> holdKey.value);
    private final BoolSetting animate = add(new BoolSetting("Pop-in animation", true), "General");

    private final SliderSetting scaleS = add(new SliderSetting("Tooltip scale", 0.5, 2.0, 0.1, 1.0, "x"), "Size");
    private final SliderSetting columns = add(new SliderSetting("Columns", 3, 9, 1, 9, ""), "Size");
    private final SliderSetting gap = add(new SliderSetting("Slot gap", 0, 4, 1, 0, "px"), "Size");
    private final SliderSetting padding = add(new SliderSetting("Padding", 2, 10, 1, 5, "px"), "Size");

    private final BoolSetting showTitle = add(new BoolSetting("Show box name", true), "Content");
    private final BoolSetting showCount = add(new BoolSetting("Show slots used", true), "Content");
    private final BoolSetting showNames = add(new BoolSetting("List custom-named items", true), "Content");

    private final ColorSetting bgColor = add(new ColorSetting("Background color", 0xF0100C1E, true, false), "Appearance");
    private final BoolSetting tint = add(new BoolSetting("Tint with shulker color", true), "Appearance");
    private final BoolSetting slotBg = add(new BoolSetting("Slot backgrounds", true), "Appearance");
    private final ColorSetting slotColor = add(new ColorSetting("Slot color", 0x8C1D2240, true, false), "Appearance", () -> slotBg.value);

    // Per-stack cache (render thread only)
    private ItemStack cacheStack;
    private ItemContainerContents cacheContents;
    private NonNullList<ItemStack> cacheItems;
    private int cacheFilled;
    private String cacheTitle = "";
    private int cacheColor = DEFAULT_SHULKER;
    private final List<String> cacheNames = new ArrayList<>();

    // Pop-in animation state
    private ItemStack lastStack;
    private long lastDraw, animStart;
    private boolean warned;

    public ShulkerTooltipModule() {
        super("shulkertooltip", "Shulker Tooltip",
                "Hover a shulker box to see its contents as an inventory grid.",
                Category.UTILITY, false, 0f, 0f, false);
        instance = this;
    }

    /**
     * Draws the tooltip for the hovered slot. Returns true when it drew (the caller then cancels the vanilla
     * tooltip), false when the stack is not a shulker with contents or the module is off.
     */
    public static boolean tryDraw(GuiGraphics g, int mx, int my, Slot slot, boolean carriedEmpty) {
        ShulkerTooltipModule m = instance;
        if (m == null || !m.enabled || slot == null || !carriedEmpty) return false;
        try {
            return m.draw(g, mx, my, slot.getItem());
        } catch (RuntimeException e) {
            if (!m.warned) {
                m.warned = true;
                PulseClient.LOG.warn("Shulker Tooltip failed to draw", e);
            }
            return false;
        }
    }

    private boolean load(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock box)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return false;
        if (stack == cacheStack && contents == cacheContents) return cacheFilled > 0;

        NonNullList<ItemStack> list = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        contents.copyInto(list);
        cacheStack = stack;
        cacheContents = contents;
        cacheItems = list;
        cacheFilled = 0;
        cacheNames.clear();
        int extraNames = 0;
        for (ItemStack s : list) {
            if (s.isEmpty()) continue;
            cacheFilled++;
            if (s.has(DataComponents.CUSTOM_NAME)) {
                if (cacheNames.size() < 4) {
                    cacheNames.add(s.getHoverName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : ""));
                } else {
                    extraNames++;
                }
            }
        }
        if (extraNames > 0) cacheNames.add("+" + extraNames + " more");
        cacheTitle = stack.getHoverName().getString();
        DyeColor dye = box.getColor();
        cacheColor = dye == null ? DEFAULT_SHULKER : dye.getMapColor().col;
        return cacheFilled > 0;
    }

    private boolean draw(GuiGraphics g, int mx, int my, ItemStack stack) {
        if (!load(stack)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (holdKey.value && !peekKey.isDown(mc)) return false;

        int cols = columns.intValue();
        int rows = (SLOTS + cols - 1) / cols;
        int gp = gap.intValue();
        int cell = 18 + gp;
        int gridW = cols * cell - gp;
        int gridH = rows * cell - gp;
        int pad = padding.intValue();

        boolean title = showTitle.value;
        boolean count = showCount.value;
        String countText = cacheFilled + "/" + SLOTS;
        int countW = count ? Theme.width(countText) : 0;
        int headerH = title || count ? 13 : 0;
        boolean names = showNames.value && !cacheNames.isEmpty();
        int namesH = names ? cacheNames.size() * 10 + 3 : 0;

        int titleW = title ? Theme.width(cacheTitle) : 0;
        int w = Math.max(gridW, Math.min(titleW + (count ? countW + 10 : 0), 220)) + pad * 2;
        int h = pad + headerH + gridH + namesH + pad;

        // pop-in
        long now = System.nanoTime();
        if (now - lastDraw > 150_000_000L || stack != lastStack) animStart = now;
        lastDraw = now;
        lastStack = stack;
        float t = animate.value ? Math.min(1f, (now - animStart) / 140_000_000f) : 1f;
        float e = Theme.ease(t);
        float k = 0.92f + 0.08f * e;

        float sc = (float) scaleS.value;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float pw = w * sc, ph = h * sc;
        float x = mx + 12;
        float y = my - 12;
        if (x + pw > sw - 4) x = mx - 12 - pw;
        if (x < 4) x = 4;
        if (y + ph > sh - 4) y = sh - 4 - ph;
        if (y < 4) y = 4;

        int shulker = 0xFF000000 | cacheColor;

        g.pose().pushMatrix();
        try {
            g.pose().translate(x + pw / 2f, y + ph / 2f);
            g.pose().scale(sc * k, sc * k);
            g.pose().translate(-w / 2f, -h / 2f);

            // body: 1px border (shulker colored), then background
            int border = tint.value ? Theme.alpha(shulker, 0.9f * e) : Theme.alpha(Theme.LINE, e);
            Theme.roundRect(g, 0, 0, w, h, 4, border);
            Theme.roundRect(g, 1, 1, w - 2, h - 2, 3, Theme.alpha(bgColor.color(), e));
            if (tint.value) {
                Theme.hGradient(g, 4, 1, Math.max(0, w - 8), 1, shulker, Theme.lerp(shulker, 0xFFFFFFFF, 0.45f));
            }

            int tx = pad;
            int ty = pad;
            if (title) {
                Theme.shadow(g, Theme.fit(cacheTitle, w - pad * 2 - (count ? countW + 8 : 0)), tx, ty + 1,
                        Theme.alpha(Theme.TEXT, e));
            }
            if (count) {
                Theme.shadow(g, countText, w - pad - countW, ty + 1, Theme.alpha(Theme.DIM, e));
            }

            int gy = pad + headerH;
            int slotCol = tint.value ? Theme.lerp(slotColor.color(), (slotColor.color() & 0xFF000000) | (shulker & 0xFFFFFF), 0.22f)
                    : slotColor.color();
            for (int i = 0; i < SLOTS; i++) {
                int sx = pad + (i % cols) * cell;
                int sy = gy + (i / cols) * cell;
                if (slotBg.value) g.fill(sx, sy, sx + 18, sy + 18, Theme.alpha(slotCol, e));
                ItemStack s = cacheItems.get(i);
                if (s.isEmpty()) continue;
                g.renderItem(s, sx + 1, sy + 1);
                g.renderItemDecorations(Theme.font(), s, sx + 1, sy + 1);
            }

            if (names) {
                int ny = gy + gridH + 4;
                for (String n : cacheNames) {
                    Theme.text(g, Theme.fit(n, w - pad * 2), pad, ny, Theme.alpha(Theme.TEXT, e));
                    ny += 10;
                }
            }
        } finally {
            g.pose().popMatrix();
        }
        return true;
    }
}
