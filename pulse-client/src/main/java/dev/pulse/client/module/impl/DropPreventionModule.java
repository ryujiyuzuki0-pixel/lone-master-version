package dev.pulse.client.module.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.DropConfirmScreen;
import dev.pulse.client.ui.DropPreventionScreen;
import dev.pulse.client.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stops you from dropping items by accident.
 * <p>
 * Two vanilla paths are guarded (see LocalPlayerMixin and MultiPlayerGameModeMixin):
 * the drop key (Q / Ctrl+Q) and drops made inside inventory screens (Q over a slot, clicking outside the
 * window with an item on the cursor). Moving, swapping, shift-clicking and every other inventory
 * operation is never touched.
 * <p>
 * An item counts as protected when, in this order:
 * <ol>
 *   <li>it matches the <b>Protected</b> list: always protected;</li>
 *   <li>it matches the <b>Allowed</b> list: never protected (overrides the categories);</li>
 *   <li>it falls in one of the enabled categories (enchanted, weapons, tools, armor, valuables, ...).</li>
 * </ol>
 * List entries are an item id ("minecraft:diamond_sword"), a short name ("diamond_sword") or a wildcard
 * ("netherite*", "*_shulker_box") which acts as a custom category.
 * Protected drops are blocked, need a confirmation, or need the drop key held, depending on Mode. Holding the
 * override key lets any drop through.
 */
public final class DropPreventionModule extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One Protected / Allowed list entry. */
    private static final class Rule {
        final boolean namespaced;
        final String exact;
        final Pattern glob;

        Rule(String raw) {
            String p = raw.toLowerCase(Locale.ROOT).trim();
            namespaced = p.indexOf(':') >= 0;
            if (p.indexOf('*') >= 0) {
                StringBuilder sb = new StringBuilder();
                String[] parts = p.split("\\*", -1);
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) sb.append(".*");
                    if (!parts[i].isEmpty()) sb.append(Pattern.quote(parts[i]));
                }
                glob = Pattern.compile(sb.toString());
                exact = null;
            } else {
                glob = null;
                exact = p;
            }
        }

        boolean matches(String id, String path) {
            String t = namespaced ? id : path;
            return glob != null ? glob.matcher(t).matches() : t.equals(exact);
        }
    }

    // ---- protection
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Confirm", "Hold to drop", "Block"), "Protection");
    private final SliderSetting holdTime = add(new SliderSetting("Hold time", 0.3, 3.0, 0.1, 1.0, "s"), "Protection", () -> mode.index == 1);
    private final BoolSetting guiDrops = add(new BoolSetting("Protect inventory drops", true), "Protection");
    private final BoolSetting overrideOn = add(new BoolSetting("Emergency override", true), "Protection");
    private final KeybindSetting overrideKey = add(new KeybindSetting("Override key (hold)", GLFW.GLFW_KEY_LEFT_ALT), "Protection", () -> overrideOn.value);
    private final BoolSetting notify = add(new BoolSetting("Show blocked message", true), "Protection");

    // ---- categories
    private final BoolSetting cEnchanted = add(new BoolSetting("Enchanted items", true), "Categories");
    private final BoolSetting cWeapons = add(new BoolSetting("Weapons", true), "Categories");
    private final BoolSetting cTools = add(new BoolSetting("Tools", true), "Categories");
    private final BoolSetting cArmor = add(new BoolSetting("Armor", true), "Categories");
    private final BoolSetting cValuables = add(new BoolSetting("Valuables (diamond, netherite, ...)", true), "Categories");
    private final BoolSetting cSpecial = add(new BoolSetting("Totems, elytra, shields", true), "Categories");
    private final BoolSetting cContainers = add(new BoolSetting("Shulker boxes, ender chests", false), "Categories");
    private final BoolSetting cNamed = add(new BoolSetting("Renamed items", false), "Categories");

    // ---- lists
    private final ActionSetting open = add(new ActionSetting("Protected / allowed lists", "Open", () -> openScreen(null)), "Lists");
    private final ActionSetting protectHeld = add(new ActionSetting("Protect held item", "Protect", () -> addHeld(true)), "Lists");
    private final ActionSetting allowHeld = add(new ActionSetting("Always allow held item", "Allow", () -> addHeld(false)), "Lists");

    private final List<String> protect = new ArrayList<>();
    private final List<String> allow = new ArrayList<>();
    private final List<Rule> protectRules = new ArrayList<>();
    private final List<Rule> allowRules = new ArrayList<>();

    /** True while a confirmed or held drop is being replayed, so our own hooks let it through. */
    private static boolean bypass;

    // confirmation request, opened from tick() so no screen is swapped in the middle of a click or key handler
    private ItemStack pendingStack;
    private Runnable pendingAction;

    // hold-to-drop state
    private boolean holding, holdFired, holdFull;
    private long holdStart;
    private ItemStack holdItem = ItemStack.EMPTY;

    public DropPreventionModule() {
        super("dropprevention", "Drop Prevention", "Stops you dropping your good items by accident.",
                Category.UTILITY, false, 0f, 0f, false);
        load();
    }

    // ------------------------------------------------------------------ hooks (called from the mixins)

    /** Drop key. Returns true when the drop must be cancelled. */
    public static boolean interceptKeyDrop(boolean fullStack) {
        return instance() instanceof DropPreventionModule m && m.enabled && m.onKeyDrop(fullStack);
    }

    /** Inventory click. Returns true when the click must be cancelled. */
    public static boolean interceptClick(MultiPlayerGameMode gm, int containerId, int slotId, int button,
                                         ClickType type, Player player) {
        return instance() instanceof DropPreventionModule m && m.enabled
                && m.onClick(gm, containerId, slotId, button, type, player);
    }

    private static Module instance() {
        return ModuleManager.byId("dropprevention");
    }

    private boolean onKeyDrop(boolean full) {
        Minecraft mc = Minecraft.getInstance();
        if (bypass || mc.player == null || mc.player.isSpectator()) return false;
        ItemStack held = mc.player.getMainHandItem();
        if (!isProtected(held) || overridden(mc)) return false;
        switch (mode.index) {
            case 2 -> blocked(mc, held);
            case 1 -> startHold(mc, held, full);
            default -> ask(held, () -> keyDropNow(mc, full));
        }
        return true;
    }

    private boolean onClick(MultiPlayerGameMode gm, int containerId, int slotId, int button, ClickType type, Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (bypass || !guiDrops.value || player != mc.player || player.isSpectator()) return false;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != containerId) return false;
        ItemStack stack;
        if (type == ClickType.THROW) {
            // Vanilla only throws when the cursor is empty and a real slot was hit.
            if (slotId < 0 || slotId >= menu.slots.size() || !menu.getCarried().isEmpty()) return false;
            stack = menu.getSlot(slotId).getItem();
        } else if (type == ClickType.PICKUP && slotId == -999) { // -999 = clicked outside the window
            stack = menu.getCarried();
        } else {
            return false;
        }
        if (!isProtected(stack) || overridden(mc)) return false;
        if (mode.index == 2) {
            blocked(mc, stack);
        } else {
            // "Hold to drop" needs the key held, which does not apply to mouse drops: they get the confirmation.
            ask(stack, () -> {
                bypass = true;
                try {
                    gm.handleInventoryMouseClick(containerId, slotId, button, type, player);
                } finally {
                    bypass = false;
                }
            });
        }
        return true;
    }

    private boolean overridden(Minecraft mc) {
        return overrideOn.value && overrideKey.isDown(mc);
    }

    private void blocked(Minecraft mc, ItemStack stack) {
        if (!notify.value || mc.player == null) return;
        String hint = overrideOn.value ? " (hold " + overrideKey.display() + " to override)" : "";
        mc.player.displayClientMessage(Component.literal("Protected: " + stack.getHoverName().getString() + hint), true);
    }

    private void ask(ItemStack stack, Runnable action) {
        pendingStack = stack.copy();
        pendingAction = action;
    }

    private static void keyDropNow(Minecraft mc, boolean full) {
        if (mc.player == null) return;
        bypass = true;
        try {
            if (mc.player.drop(full)) mc.player.swing(InteractionHand.MAIN_HAND);
        } finally {
            bypass = false;
        }
    }

    // ------------------------------------------------------------------ hold to drop

    private void startHold(Minecraft mc, ItemStack held, boolean full) {
        if (holding) return; // already counting (or already fired and the key is still down)
        holding = true;
        holdFired = false;
        holdFull = full;
        holdItem = held.copy();
        holdStart = System.currentTimeMillis();
    }

    @Override
    public void tick(Minecraft mc) {
        if (pendingAction != null && !(mc.screen instanceof DropConfirmScreen) && mc.player != null) {
            Screen parent = mc.screen;
            ItemStack s = pendingStack;
            Runnable a = pendingAction;
            pendingStack = null;
            pendingAction = null;
            mc.setScreen(new DropConfirmScreen(parent, s, a));
        }
        if (!holding) return;
        if (mc.player == null || mc.screen != null || !mc.options.keyDrop.isDown()
                || !ItemStack.isSameItemSameComponents(holdItem, mc.player.getMainHandItem())) {
            holding = false;
            return;
        }
        if (!holdFired && System.currentTimeMillis() - holdStart >= (long) (holdTime.value * 1000)) {
            holdFired = true;
            keyDropNow(mc, holdFull);
        }
    }

    @Override
    public void onDisable() {
        holding = false;
        pendingAction = null;
        pendingStack = null;
    }

    @Override
    public void drawOverlay(GuiGraphics g, Minecraft mc) {
        if (!holding || holdFired || mc.screen != null) return;
        float t = Math.min(1f, (System.currentTimeMillis() - holdStart) / (float) Math.max(1, holdTime.value * 1000));
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 20;
        int w = 84;
        Theme.centered(g, "Hold to drop", cx, y - 11, 0xFFFFFFFF, true);
        Theme.roundRect(g, cx - w / 2 - 1, y - 1, w + 2, 7, 3, 0xB0000000);
        int fill = Math.max(2, (int) (w * t));
        Theme.roundRect(g, cx - w / 2, y, fill, 5, 2, Theme.ACCENT);
    }

    // ------------------------------------------------------------------ rules

    public boolean isProtected(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        String path = id.substring(id.indexOf(':') + 1);
        if (matchesAny(protectRules, id, path)) return true;
        if (matchesAny(allowRules, id, path)) return false;
        return byCategory(s, path);
    }

    private static boolean matchesAny(List<Rule> rules, String id, String path) {
        for (Rule r : rules) if (r.matches(id, path)) return true;
        return false;
    }

    private boolean byCategory(ItemStack s, String path) {
        if (cEnchanted.value && (!s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty()
                || !s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty())) return true;
        if (cWeapons.value && isWeapon(path)) return true;
        if (cTools.value && isTool(path)) return true;
        if (cArmor.value && isArmor(path)) return true;
        if (cValuables.value && isValuable(path)) return true;
        if (cSpecial.value && isSpecial(path)) return true;
        if (cContainers.value && (path.endsWith("shulker_box") || path.equals("ender_chest"))) return true;
        return cNamed.value && s.has(DataComponents.CUSTOM_NAME);
    }

    // Matching on the registry path keeps this independent of item class and tag changes between versions.
    private static boolean isWeapon(String p) {
        return p.endsWith("_sword") || p.endsWith("_spear") || p.equals("bow") || p.equals("crossbow")
                || p.equals("trident") || p.equals("mace");
    }

    private static boolean isTool(String p) {
        return p.endsWith("_pickaxe") || p.endsWith("_axe") || p.endsWith("_shovel") || p.endsWith("_hoe")
                || p.equals("fishing_rod") || p.equals("shears") || p.equals("flint_and_steel") || p.equals("brush");
    }

    private static boolean isArmor(String p) {
        return p.endsWith("_helmet") || p.endsWith("_chestplate") || p.endsWith("_leggings") || p.endsWith("_boots")
                || p.endsWith("_horse_armor") || p.endsWith("_nautilus_armor") || p.equals("wolf_armor");
    }

    private static boolean isValuable(String p) {
        return p.contains("netherite") || p.contains("diamond") || p.equals("enchanted_golden_apple")
                || p.equals("nether_star") || p.equals("beacon") || p.equals("dragon_egg")
                || p.equals("heart_of_the_sea") || p.equals("conduit");
    }

    private static boolean isSpecial(String p) {
        return p.equals("totem_of_undying") || p.equals("elytra") || p.equals("shield");
    }

    // ------------------------------------------------------------------ lists (used by the manager screen)

    public List<String> protectedList() {
        return Collections.unmodifiableList(protect);
    }

    public List<String> allowedList() {
        return Collections.unmodifiableList(allow);
    }

    /** Adds an entry to the Protected (true) or Allowed (false) list. Returns false for blank or duplicate entries. */
    public boolean addEntry(boolean toProtect, String raw) {
        String e = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        if (e.isEmpty() || e.length() > 64 || e.contains(" ")) return false;
        List<String> list = toProtect ? protect : allow;
        if (list.contains(e)) return false;
        list.add(e);
        rebuild();
        save();
        return true;
    }

    public void removeEntry(boolean fromProtect, int index) {
        List<String> list = fromProtect ? protect : allow;
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        rebuild();
        save();
    }

    /** Registry id of the item in your main hand, or null when the hand is empty. */
    public static String heldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ItemStack s = mc.player.getMainHandItem();
        return s.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private void addHeld(boolean toProtect) {
        Minecraft mc = Minecraft.getInstance();
        String id = heldId();
        if (mc.player == null) return;
        if (id == null) {
            mc.player.displayClientMessage(Component.literal("Hold the item you want to add first."), true);
            return;
        }
        boolean ok = addEntry(toProtect, id);
        mc.player.displayClientMessage(Component.literal(ok
                ? id + (toProtect ? " is now protected." : " can always be dropped.")
                : id + " is already on that list."), true);
    }

    public void openScreen(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DropPreventionScreen(parent == null ? mc.screen : parent));
    }

    private void rebuild() {
        protectRules.clear();
        allowRules.clear();
        for (String s : protect) protectRules.add(new Rule(s));
        for (String s : allow) allowRules.add(new Rule(s));
    }

    // ------------------------------------------------------------------ persistence (config/pulse-dropprotect.json)

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-dropprotect.json");
    }

    private void load() {
        Path p = file();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                read(root, "protect", protect);
                read(root, "allow", allow);
            } catch (Exception e) {
                PulseClient.LOG.warn("Could not read pulse-dropprotect.json", e);
            }
        }
        rebuild();
    }

    private static void read(JsonObject root, String key, List<String> out) {
        if (!root.has(key)) return;
        for (JsonElement e : root.getAsJsonArray(key)) {
            String s = e.getAsString().toLowerCase(Locale.ROOT).trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            JsonArray a = new JsonArray(), b = new JsonArray();
            protect.forEach(a::add);
            allow.forEach(b::add);
            root.add("protect", a);
            root.add("allow", b);
            Files.writeString(file(), GSON.toJson(root));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-dropprotect.json", e);
        }
    }
}
