package dev.pulse.client.module.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.DeathInfoScreen;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import dev.pulse.client.util.KeyNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Records every death (where, when, why, last health) and keeps a history that survives restarts
 * (config/pulse-deaths.json). Shows the latest one on the HUD; the full list lives in {@link DeathInfoScreen}.
 */
public final class DeathInfoModule extends Module {
    public static final class Death {
        public long time;
        public double x, y, z;
        /** Raw dimension key, same format Waypoints stores. */
        public String dimRaw = "";
        public String dim = "";
        public String cause = "Unknown";
        public float health;
        public String where = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int ROW = 11;

    private final List<Death> history = new ArrayList<>(); // newest first

    private final BoolSetting showCoords = add(new BoolSetting("Show coordinates", true), "Display");
    private final BoolSetting showDim = add(new BoolSetting("Show dimension", true), "Display");
    private final BoolSetting showTime = add(new BoolSetting("Show time", true), "Display");
    private final BoolSetting showCause = add(new BoolSetting("Show cause", true), "Display");
    private final BoolSetting showHealth = add(new BoolSetting("Show last health", false), "Display");
    private final BoolSetting showDistance = add(new BoolSetting("Show distance", true), "Display");
    private final SliderSetting hideAfter = add(new SliderSetting("Hide HUD after", 0, 60, 1, 0, " min").zero("Never"), "Display");
    private final SliderSetting maxEntries = add(new SliderSetting("History size", 5, 100, 5, 30, ""), "History");
    private final KeybindSetting openKey = add(new KeybindSetting("Open Death Info key", -1), "History");
    private final ActionSetting open = add(new ActionSetting("Death Info", "Open", () -> openScreen(null)), "Manage");
    private final ActionSetting copy = add(new ActionSetting("Last death coordinates", "Copy", () -> copyCoords(latest())), "Manage");
    private final ActionSetting waypoint = add(new ActionSetting("Last death waypoint", "Create", () -> createWaypoint(latest())), "Manage");
    private final ActionSetting clear = add(new ActionSetting("Death history", "Clear", this::clearHistory), "Manage");

    private boolean dead;
    private boolean keyWasDown;
    private float lastHealth = 20f;

    public DeathInfoModule() {
        super("deathinfo", "Death Info", "Remembers where, when and how you died. Keeps a history.",
                Category.UTILITY, true, 0.992f, 0.30f, true);
        load();
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-deaths.json");
    }

    private void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            List<Death> in = GSON.fromJson(r, new TypeToken<List<Death>>() {}.getType());
            if (in != null) history.addAll(in);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read pulse-deaths.json", e);
        }
    }

    private void save() {
        try {
            Files.writeString(file(), GSON.toJson(history));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-deaths.json", e);
        }
    }

    // ------------------------------------------------------------------ tracking

    @Override
    public void tick(Minecraft mc) {
        Player p = mc.player;
        if (p == null || mc.level == null) {
            dead = false;
            return;
        }
        boolean nowDead = p.getHealth() <= 0f || p.isDeadOrDying();
        if (nowDead && !dead) record(mc, p);
        if (!nowDead) lastHealth = p.getHealth();
        dead = nowDead;

        boolean down = mc.screen == null && openKey.isDown(mc);
        if (down && !keyWasDown) openScreen(null);
        keyWasDown = down;
    }

    private void record(Minecraft mc, Player p) {
        Death d = new Death();
        d.time = System.currentTimeMillis();
        d.x = p.getX();
        d.y = p.getY();
        d.z = p.getZ();
        d.dimRaw = WaypointsModule.currentDim(mc);
        d.dim = KeyNames.prettyKey(mc.level.dimension());
        d.cause = cause(mc, p);
        d.health = lastHealth;
        d.where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "Singleplayer";
        history.add(0, d);
        trim();
        save();
    }

    private void trim() {
        int max = maxEntries.intValue();
        while (history.size() > max) history.remove(history.size() - 1);
    }

    /** Best effort: the client only knows the last damage source in some situations, so fall back to what it can see. */
    private static String cause(Minecraft mc, Player p) {
        DamageSource ds = p.getLastDamageSource();
        if (ds != null) {
            String s = KeyNames.pretty(ds.type().msgId());
            Entity e = ds.getEntity();
            if (e != null) s += " (" + e.getName().getString() + ")";
            return s;
        }
        if (p.isInLava()) return "Lava";
        if (p.getY() < mc.level.getMinY()) return "Fell out of the world";
        if (p.isOnFire()) return "Fire";
        if (p.getAirSupply() <= 0) return "Drowning";
        if (p.fallDistance > 3) return "Fall";
        return "Unknown";
    }

    // ------------------------------------------------------------------ public API (screen / commands)

    public List<Death> history() {
        return history;
    }

    public Death latest() {
        return history.isEmpty() ? null : history.get(0);
    }

    public void clearHistory() {
        history.clear();
        save();
    }

    public void remove(Death d) {
        if (history.remove(d)) save();
    }

    public void copyCoords(Death d) {
        Minecraft mc = Minecraft.getInstance();
        if (d == null) {
            tell(mc, "No deaths recorded yet.");
            return;
        }
        mc.keyboardHandler.setClipboard(coords(d));
        tell(mc, "Copied " + coords(d));
    }

    public void createWaypoint(Death d) {
        Minecraft mc = Minecraft.getInstance();
        if (d == null) {
            tell(mc, "No deaths recorded yet.");
            return;
        }
        WaypointsModule wp = ModuleManager.waypoints();
        if (wp == null) return;
        String name = "Death-" + DateTimeFormatter.ofPattern("MMdd-HHmm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(d.time));
        wp.addAt(name, d.x, d.y, d.z, d.dimRaw);
        tell(mc, "Waypoint '" + name + "' created. Enable the Waypoints module to see it.");
    }

    public void openScreen(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DeathInfoScreen(parent == null ? mc.screen : parent));
    }

    private static void tell(Minecraft mc, String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), true);
    }

    public static String coords(Death d) {
        return (int) Math.floor(d.x) + " " + (int) Math.floor(d.y) + " " + (int) Math.floor(d.z);
    }

    public static String stamp(long time) {
        return STAMP.format(Instant.ofEpochMilli(time));
    }

    public static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h " + (s % 3600) / 60 + "m ago";
        return s / 86400 + "d ago";
    }

    // ------------------------------------------------------------------ HUD

    private Death shown() {
        Death d = latest();
        if (d != null) {
            long limit = (long) hideAfter.value * 60_000L;
            if (limit > 0 && System.currentTimeMillis() - d.time > limit && !HudRenderer.editing()) return null;
            return d;
        }
        if (!HudRenderer.editing()) return null;
        Death sample = new Death();
        sample.time = System.currentTimeMillis() - 90_000;
        sample.dim = "Overworld";
        sample.cause = "Fall";
        sample.health = 3f;
        return sample;
    }

    private List<String[]> rows(Minecraft mc, Death d) {
        List<String[]> out = new ArrayList<>();
        if (showCoords.value) out.add(new String[]{"XYZ", coords(d)});
        if (showDim.value) out.add(new String[]{"DIM", d.dim});
        if (showTime.value) out.add(new String[]{"WHEN", ago(d.time)});
        if (showCause.value) out.add(new String[]{"CAUSE", d.cause});
        if (showHealth.value) out.add(new String[]{"HP", String.format(Locale.ROOT, "%.1f", d.health / 2f) + " hearts"});
        if (showDistance.value && mc.player != null && d.dimRaw.equals(WaypointsModule.currentDim(mc))) {
            double dx = d.x - mc.player.getX(), dz = d.z - mc.player.getZ();
            out.add(new String[]{"DIST", Math.round(Math.sqrt(dx * dx + dz * dz)) + "m"});
        }
        return out;
    }

    @Override
    public int width(Minecraft mc) {
        Death d = shown();
        if (d == null) return 0;
        int w = 110;
        for (String[] r : rows(mc, d)) w = Math.max(w, 12 + Theme.width(r[0]) + 12 + Theme.width(r[1]));
        return w;
    }

    @Override
    public int height(Minecraft mc) {
        Death d = shown();
        if (d == null) return 0;
        return 6 + ROW + rows(mc, d).size() * ROW;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        Death d = shown();
        if (d == null) return;
        text(g, "LAST DEATH", 6, 4, dimColor());
        int y = 4 + ROW;
        for (String[] r : rows(mc, d)) {
            text(g, r[0], 6, y, dimColor());
            text(g, r[1], w - 6 - Theme.width(r[1]), y, textColor());
            y += ROW;
        }
    }
}
