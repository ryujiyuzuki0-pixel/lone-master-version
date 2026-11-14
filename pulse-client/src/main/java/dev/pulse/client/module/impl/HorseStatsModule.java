package dev.pulse.client.module.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.StatCard;
import dev.pulse.client.ui.StatCard.Row;
import dev.pulse.client.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Shows the real attributes of the horse you ride or look at (movement speed, jump strength, max health) plus
 * its current health, an estimated jump height, rating bars, a comparison against another horse and a history.
 * Speed, jump strength and health are read straight from the entity's attributes (the server syncs them);
 * only the jump height is an estimate, and it is labelled as one.
 * The history lives in config/pulse-horses.json.
 */
public final class HorseStatsModule extends Module {
    /** One remembered horse. Base attribute values, so potions and modifiers do not skew comparisons. */
    public static final class Entry {
        public String id = "";
        public String name = "";
        public String type = "";
        public double speed;
        public double jump;
        public double maxHealth;
        public long time;
    }

    /** What is written to config/pulse-horses.json. */
    public static final class Store {
        public String pinned = "";
        public List<Entry> horses = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Attribute value to blocks per second (community-measured factor for ridden horses). */
    private static final double SPEED_TO_BPS = 42.1578;
    // Vanilla horse generation ranges, used only to fill the rating bars.
    private static final double SPEED_MIN = 0.1125, SPEED_MAX = 0.3375;
    private static final double JUMP_MIN = 0.4, JUMP_MAX = 1.0;
    private static final double HP_MIN = 15.0, HP_MAX = 30.0;
    /** A horse must be in view this many ticks before it is remembered (avoids logging every horse you sweep past). */
    private static final int DWELL_TICKS = 10;

    private final Store store = new Store();

    private final BoolSetting showRiding = add(new BoolSetting("Show while riding", true), "Display");
    private final BoolSetting showLooking = add(new BoolSetting("Show when looking at one", true), "Display");
    private final SliderSetting linger = add(new SliderSetting("Keep showing after", 0, 10, 1, 3, "s").zero("Off"), "Display");
    private final ChoiceSetting speedUnit = add(new ChoiceSetting("Speed unit", 0, "Blocks/s", "Attribute"), "Display");
    private final BoolSetting showHeight = add(new BoolSetting("Show jump height", true), "Display");
    private final ChoiceSetting heightModel = add(new ChoiceSetting("Jump height model", 0, "Community fit", "Physics sim"), "Display", () -> this.showHeight.value);
    private final BoolSetting showHealth = add(new BoolSetting("Show health", true), "Display");
    private final BoolSetting showBars = add(new BoolSetting("Rating bars (horses)", true), "Display");

    private final ChoiceSetting compare = add(new ChoiceSetting("Compare with", 0, "Best seen", "Previous horse", "Pinned horse", "Off"), "Comparison");
    private final KeybindSetting pinKey = add(new KeybindSetting("Pin horse key", -1), "Comparison");
    private final ActionSetting pinAction = add(new ActionSetting("Last inspected horse", "Pin", this::pinLast), "Comparison");
    private final ActionSetting unpinAction = add(new ActionSetting("Pinned horse", "Unpin", this::unpin), "Comparison");

    private final BoolSetting keepHistory = add(new BoolSetting("Remember horses", true), "History");
    private final SliderSetting historySize = add(new SliderSetting("History size", 5, 100, 5, 25, ""), "History");
    private final SliderSetting historyRows = add(new SliderSetting("History rows on HUD", 0, 8, 1, 0, "").zero("Off"), "History");
    private final ActionSetting clearAction = add(new ActionSetting("Horse history", "Clear", this::clearHistory), "History");

    private LivingEntity current;
    private long lastSeen;
    private int seenTicks;
    private String seenId = "";
    private Entry lastInspected;
    private boolean keyWasDown;
    private StatCard live;

    public HorseStatsModule() {
        super("horsestats", "Horse Stats",
                "Speed, jump strength, jump height and health of the horse you ride or look at, with comparison and history.",
                Category.UTILITY, true, 0.992f, 0.46f, true);
        load();
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-horses.json");
    }

    private void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Store in = GSON.fromJson(r, Store.class);
            if (in == null) return;
            store.pinned = in.pinned == null ? "" : in.pinned;
            if (in.horses != null) store.horses.addAll(in.horses);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read pulse-horses.json", e);
        }
    }

    private void save() {
        try {
            Files.writeString(file(), GSON.toJson(store));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-horses.json", e);
        }
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------------ horse detection

    private static boolean isHorse(Entity e) {
        EntityType<?> t = e.getType();
        return t == EntityType.HORSE || t == EntityType.DONKEY || t == EntityType.MULE
                || t == EntityType.SKELETON_HORSE || t == EntityType.ZOMBIE_HORSE;
    }

    private static double attr(LivingEntity e, Holder<Attribute> a) {
        return e.getAttribute(a) == null ? 0.0 : e.getAttributeBaseValue(a);
    }

    private static Entry snapshot(LivingEntity e) {
        Entry s = new Entry();
        s.id = e.getUUID().toString();
        s.name = e.hasCustomName() ? e.getName().getString() : "";
        s.type = e.getType().getDescription().getString();
        s.speed = attr(e, Attributes.MOVEMENT_SPEED);
        s.jump = attr(e, Attributes.JUMP_STRENGTH);
        s.maxHealth = attr(e, Attributes.MAX_HEALTH);
        s.time = System.currentTimeMillis();
        return s;
    }

    @Override
    public void tick(Minecraft mc) {
        Player p = mc.player;
        if (p == null || mc.level == null) {
            current = null;
            live = null;
            seenTicks = 0;
            return;
        }
        LivingEntity found = null;
        if (showRiding.value && p.getVehicle() instanceof LivingEntity v && isHorse(v)) {
            found = v;
        } else if (showLooking.value && mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof LivingEntity e && isHorse(e)) {
            found = e;
        }
        long now = System.currentTimeMillis();
        if (found != null) {
            current = found;
            lastSeen = now;
            String id = found.getUUID().toString();
            seenTicks = id.equals(seenId) ? seenTicks + 1 : 1;
            seenId = id;
        } else {
            seenTicks = 0;
        }

        boolean down = mc.screen == null && pinKey.isDown(mc);
        if (down && !keyWasDown) pinLast();
        keyWasDown = down;

        boolean show = current != null && !current.isRemoved()
                && (found != null || now - lastSeen < linger.value * 1000.0);
        if (!show) {
            current = null;
            live = null;
            return;
        }
        Entry s = snapshot(current);
        boolean synced = s.speed > 0 && s.jump > 0 && s.maxHealth > 0;
        if (synced) {
            lastInspected = s;
            if (keepHistory.value && seenTicks >= DWELL_TICKS && current.tickCount > 5) remember(s);
        }
        live = synced ? build(s, current.getHealth(), horseBars(current)) : null;
    }

    private boolean horseBars(LivingEntity e) {
        return showBars.value && e.getType() == EntityType.HORSE;
    }

    // ------------------------------------------------------------------ history

    private Entry find(String id) {
        for (Entry e : store.horses) if (e.id.equals(id)) return e;
        return null;
    }

    private void remember(Entry s) {
        Entry old = find(s.id);
        if (old != null) {
            old.time = s.time;
            old.name = s.name;
            return;
        }
        store.horses.add(0, s);
        trim();
        save();
    }

    private void trim() {
        store.horses.sort(Comparator.comparingLong((Entry e) -> e.time).reversed());
        int max = historySize.intValue();
        while (store.horses.size() > max) {
            Entry last = store.horses.get(store.horses.size() - 1);
            // The pinned horse is kept as long as there is any other entry to drop.
            if (last.id.equals(store.pinned) && store.horses.size() > 1) {
                store.horses.remove(store.horses.size() - 2);
            } else {
                store.horses.remove(store.horses.size() - 1);
            }
        }
    }

    private void pinLast() {
        if (lastInspected == null) return;
        Entry have = find(lastInspected.id);
        if (have == null) {
            store.horses.add(0, lastInspected);
            trim();
        }
        store.pinned = lastInspected.id;
        save();
    }

    private void unpin() {
        store.pinned = "";
        save();
    }

    private void clearHistory() {
        store.horses.clear();
        store.pinned = "";
        save();
    }

    /** The horse the current one is compared against, or null. */
    private Entry reference(Entry cur) {
        switch (compare.index) {
            case 0 -> {
                Entry best = null;
                for (Entry e : store.horses) {
                    if (e.id.equals(cur.id)) continue;
                    if (best == null) best = new Entry();
                    best.speed = Math.max(best.speed, e.speed);
                    best.jump = Math.max(best.jump, e.jump);
                    best.maxHealth = Math.max(best.maxHealth, e.maxHealth);
                }
                return best;
            }
            case 1 -> {
                Entry prev = null;
                for (Entry e : store.horses) {
                    if (e.id.equals(cur.id)) continue;
                    if (prev == null || e.time > prev.time) prev = e;
                }
                return prev;
            }
            case 2 -> {
                if (store.pinned.isEmpty() || store.pinned.equals(cur.id)) return null;
                return find(store.pinned);
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ numbers

    private double speedValue(double attribute) {
        return speedUnit.index == 0 ? attribute * SPEED_TO_BPS : attribute;
    }

    private String speedText(double attribute) {
        return speedUnit.index == 0
                ? String.format(Locale.ROOT, "%.2f b/s", attribute * SPEED_TO_BPS)
                : String.format(Locale.ROOT, "%.4f", attribute);
    }

    /**
     * Estimated peak height of a full-charge jump on flat ground without Jump Boost. "Community fit" is the
     * polynomial fitted to measured jumps; "Physics sim" integrates gravity 0.08 and drag 0.98 per tick.
     * The two disagree by up to about half a block, which is why it is only ever shown as an estimate.
     */
    private double jumpHeight(double s) {
        if (heightModel.index == 1) {
            double h = 0, vy = s;
            for (int i = 0; i < 400 && vy > 0; i++) {
                h += vy;
                vy = (vy - 0.08) * 0.98;
            }
            return h;
        }
        return -0.1817584952 * s * s * s + 3.689713992 * s * s + 2.128599134 * s - 0.343930367;
    }

    private static float frac(double v, double min, double max) {
        return (float) Math.max(0.0, Math.min(1.0, (v - min) / (max - min)));
    }

    private String delta(double diff, String fmt) {
        if (Math.abs(diff) < 0.005) return "=";
        return (diff > 0 ? "+" : "-") + String.format(Locale.ROOT, fmt, Math.abs(diff));
    }

    private int deltaColor(double diff) {
        if (Math.abs(diff) < 0.005) return dimColor();
        return diff > 0 ? Theme.GOOD : Theme.BAD;
    }

    // ------------------------------------------------------------------ layout

    private StatCard build(Entry s, float health, boolean bars) {
        Entry ref = reference(s);
        List<Row> rows = new ArrayList<>();

        String title = s.name.isEmpty() ? s.type : s.name;
        String right = ref != null ? "vs " + compare.get().replace(" horse", "").replace("Previous", "Prev") : (s.name.isEmpty() ? "" : s.type);
        rows.add(StatCard.row(Theme.fit(title, 120), textColor(), right, dimColor()));

        double sp = speedValue(s.speed);
        String dSpeed = ref == null ? "" : delta(sp - speedValue(ref.speed), speedUnit.index == 0 ? "%.2f" : "%.3f");
        double dSpeedRaw = ref == null ? 0 : sp - speedValue(ref.speed);
        rows.add(new Row("Speed", dimColor(), speedText(s.speed), textColor(),
                dSpeed, deltaColor(dSpeedRaw), bars ? frac(s.speed, SPEED_MIN, SPEED_MAX) : -1f, false));

        String dJump = ref == null ? "" : delta(s.jump - ref.jump, "%.3f");
        rows.add(new Row("Jump", dimColor(), String.format(Locale.ROOT, "%.4f", s.jump), textColor(),
                dJump, deltaColor(ref == null ? 0 : s.jump - ref.jump), bars ? frac(s.jump, JUMP_MIN, JUMP_MAX) : -1f, false));

        if (showHeight.value) {
            double h = jumpHeight(s.jump);
            double dh = ref == null ? 0 : h - jumpHeight(ref.jump);
            rows.add(new Row("Height ~", dimColor(), String.format(Locale.ROOT, "%.2f blocks", h), textColor(),
                    ref == null ? "" : delta(dh, "%.2f"), deltaColor(dh), -1f, false));
        }

        if (showHealth.value) {
            String hp = String.format(Locale.ROOT, "%.1f / %.1f", health, s.maxHealth);
            double dm = ref == null ? 0 : s.maxHealth - ref.maxHealth;
            rows.add(new Row("Health", dimColor(), hp, textColor(),
                    ref == null ? "" : delta(dm, "%.1f"), deltaColor(dm), bars ? frac(s.maxHealth, HP_MIN, HP_MAX) : -1f, false));
        }

        int n = historyRows.intValue();
        if (n > 0 && !store.horses.isEmpty()) {
            rows.add(new Row("HISTORY", dimColor(), speedUnit.index == 0 ? "b/s | jump | hp" : "spd | jmp | hp",
                    dimColor(), "", 0, -1f, true));
            List<Entry> sorted = new ArrayList<>(store.horses);
            sorted.sort(Comparator.comparingLong((Entry e) -> e.time).reversed());
            int shown = 0;
            for (Entry e : sorted) {
                if (shown++ >= n) break;
                String name = Theme.fit(e.name.isEmpty() ? e.type : e.name, 60);
                String vals = (speedUnit.index == 0 ? String.format(Locale.ROOT, "%.1f", e.speed * SPEED_TO_BPS)
                        : String.format(Locale.ROOT, "%.3f", e.speed))
                        + " | " + String.format(Locale.ROOT, "%.2f", jumpHeight(e.jump))
                        + " | " + String.format(Locale.ROOT, "%.0f", e.maxHealth);
                boolean pinned = e.id.equals(store.pinned);
                boolean isCurrent = e.id.equals(s.id);
                rows.add(StatCard.row((pinned ? "* " : "") + name,
                        isCurrent ? textColor() : dimColor(), vals, isCurrent ? textColor() : dimColor()));
            }
        }
        return new StatCard(rows, 130);
    }

    private StatCard buildSample() {
        Entry s = new Entry();
        s.id = "sample";
        s.name = "Sample Horse";
        s.type = "Horse";
        s.speed = 0.3;
        s.jump = 0.8;
        s.maxHealth = 28;
        return build(s, 24f, showBars.value);
    }

    private StatCard active() {
        return HudRenderer.editing() ? buildSample() : live;
    }

    @Override
    public int width(Minecraft mc) {
        StatCard c = active();
        return c == null ? 0 : c.w;
    }

    @Override
    public int height(Minecraft mc) {
        StatCard c = active();
        return c == null ? 0 : c.h;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        StatCard c = active();
        if (c != null) c.draw(this, g);
    }
}
