package dev.pulse.client.module.impl;

import dev.pulse.client.Friends;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import dev.pulse.client.util.FrameClock;
import dev.pulse.client.util.KeyNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UHC overlay: health, absorption, armor, food, coordinates, FPS, ping, a timer, biome and nearby players in one
 * HUD card. Like Custom F3, every value is gathered in {@link #tick} (health and the like every "Update interval"
 * ticks, the entity scan on its own slower interval) into a cached list of rows with pre-measured widths, so the
 * per-frame draw only paints that list. It only reads what the server already sent to your client.
 */
public final class UhcOverlayModule extends Module {
    private record Row(String label, String value, int color, int lw, int vw) {}

    /** One reading of everything the overlay can show. Filled from the game, or with sample values in the HUD editor. */
    private static final class Snap {
        float hp, maxHp, abs, sat;
        int armor, food;
        double x, y, z;
        int bx, by, bz;
        int fps, ping;
        long timerMs;
        String biome = "";
        int near, hostile;
        String nearestName;
        float nearestDist, nearestHp;
        String nearestDir = "";
    }

    private static final int ROW = 11, PAD = 6, GAP = 10;
    private static final int GOLD = 0xFFFFD34D;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    // ---- display
    private final ChoiceSetting layout = add(new ChoiceSetting("Layout", 1, "Classic", "Split", "Two columns"), "Display");
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 0, "Compact", "Detailed"), "Display");
    private final ChoiceSetting healthUnit = add(new ChoiceSetting("Health unit", 0, "Hearts", "HP"), "Display");
    private final BoolSetting dynamicColors = add(new BoolSetting("Dynamic colors", true), "Display");
    private final SliderSetting interval = add(new SliderSetting("Update interval", 1, 20, 1, 4, " ticks"), "Display");

    // ---- information
    private final BoolSetting iHealth = add(new BoolSetting("Health", true), "Information");
    private final BoolSetting iAbsorption = add(new BoolSetting("Absorption", true), "Information");
    private final BoolSetting hideZeroAbs = add(new BoolSetting("Hide absorption when zero", true), "Information", () -> this.iAbsorption.value);
    private final BoolSetting iArmor = add(new BoolSetting("Armor", true), "Information");
    private final BoolSetting iFood = add(new BoolSetting("Food", true), "Information");
    private final BoolSetting iSaturation = add(new BoolSetting("Saturation", false), "Information");
    private final BoolSetting iCoords = add(new BoolSetting("Coordinates", true), "Information");
    private final BoolSetting iFps = add(new BoolSetting("FPS", true), "Information");
    private final BoolSetting iPing = add(new BoolSetting("Ping", true), "Information");
    private final BoolSetting iTimer = add(new BoolSetting("Timer", true), "Information");
    private final BoolSetting iBiome = add(new BoolSetting("Biome", true), "Information");
    private final BoolSetting iNearby = add(new BoolSetting("Nearby players", true), "Information");

    // ---- timer
    private final ChoiceSetting timerMode = add(new ChoiceSetting("Timer mode", 0, "Since joining", "Stopwatch"), "Timer", () -> this.iTimer.value);
    private final KeybindSetting timerKey = add(new KeybindSetting("Start / pause key", -1), "Timer", () -> this.iTimer.value && this.timerMode.index == 1);
    private final KeybindSetting timerResetKey = add(new KeybindSetting("Reset key", -1), "Timer", () -> this.iTimer.value && this.timerMode.index == 1);
    private final ActionSetting timerToggle = add(new ActionSetting("Start / pause stopwatch", "Toggle", this::toggleStopwatch), "Timer", () -> this.iTimer.value && this.timerMode.index == 1);
    private final ActionSetting timerReset = add(new ActionSetting("Reset stopwatch", "Reset", this::resetStopwatch), "Timer", () -> this.iTimer.value && this.timerMode.index == 1);

    // ---- nearby
    private final SliderSetting radius = add(new SliderSetting("Radius", 8, 256, 8, 64, " blocks"), "Nearby", () -> this.iNearby.value);
    private final BoolSetting showNearest = add(new BoolSetting("Show nearest player", true), "Nearby", () -> this.iNearby.value);
    private final BoolSetting countHostile = add(new BoolSetting("Count hostile mobs", false), "Nearby", () -> this.iNearby.value);
    private final BoolSetting ignoreFriends = add(new BoolSetting("Ignore friends", true), "Nearby", () -> this.iNearby.value);
    private final SliderSetting scanInterval = add(new SliderSetting("Scan interval", 2, 40, 1, 10, " ticks"), "Nearby", () -> this.iNearby.value);

    // ---- colors
    private final ColorSetting absorptionColor = add(new ColorSetting("Absorption color", GOLD, false, true), "Colors", () -> this.iAbsorption.value);

    private final ActionSetting reset = add(new ActionSetting("Reset configuration", "Reset", this::resetSettings), "Manage");

    // ---- cached state
    private final List<Row> rows = new ArrayList<>();
    private int baseW, baseH;
    private int counter, scanCounter;
    private boolean sampleShown;

    // world tracking (cleared when there is no world, so an old level or connection is never kept alive).
    // The "since joining" timer follows the server connection, so changing dimension does not restart it.
    private Object lastLevel, lastConn;
    private long joinNs = System.nanoTime();

    // entity scan results (updated on the scan interval, not every frame)
    private int nearCount, hostileCount;
    private String nearestName;
    private float nearestDist, nearestHp;
    private String nearestDir = "";

    // stopwatch
    private boolean running;
    private long accumNs, startedNs;
    private boolean startKeyDown, resetKeyDown;

    public UhcOverlayModule() {
        super("uhcoverlay", "UHC Overlay",
                "Health, absorption, armor, food, coordinates, FPS, ping, a timer, biome and nearby players at a glance.",
                Category.HUD, true, 0.008f, 0.42f, true);
    }

    @Override
    public void onEnable() {
        counter = 0;
        scanCounter = 0;
        rows.clear();
        sampleShown = false;
        lastLevel = null;
        lastConn = null;
        joinNs = System.nanoTime();
        clearNearby();
    }

    @Override
    public void onDisable() {
        rows.clear();
        sampleShown = false;
        lastLevel = null;
        lastConn = null;
        clearNearby();
        startKeyDown = false;
        resetKeyDown = false;
    }

    // ------------------------------------------------------------------ tick (all data gathering lives here)

    @Override
    public void tick(Minecraft mc) {
        Player p = mc.player;
        if (p == null || mc.level == null) {
            lastLevel = null;
            lastConn = null;
            if (!sampleShown) rows.clear();
            return;
        }
        if (mc.getConnection() != lastConn) {
            lastConn = mc.getConnection();
            joinNs = System.nanoTime();
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            scanCounter = Integer.MAX_VALUE / 2; // scan on the very next check
            clearNearby();
        }
        pollKeys(mc);
        if (++scanCounter >= scanInterval.intValue()) {
            scanCounter = 0;
            scan(mc, p);
        }
        if (++counter >= interval.intValue() || rows.isEmpty() || sampleShown) {
            counter = 0;
            sampleShown = false;
            rebuild(mc, p);
        }
    }

    private void pollKeys(Minecraft mc) {
        boolean start = mc.screen == null && timerKey.isDown(mc);
        if (start && !startKeyDown && timerMode.index == 1) toggleStopwatch();
        startKeyDown = start;
        boolean rst = mc.screen == null && timerResetKey.isDown(mc);
        if (rst && !resetKeyDown && timerMode.index == 1) resetStopwatch();
        resetKeyDown = rst;
    }

    // ------------------------------------------------------------------ stopwatch

    private void toggleStopwatch() {
        long now = System.nanoTime();
        if (running) {
            accumNs += now - startedNs;
            running = false;
        } else {
            startedNs = now;
            running = true;
        }
        counter = Integer.MAX_VALUE / 2; // refresh the row on the next tick
    }

    private void resetStopwatch() {
        running = false;
        accumNs = 0;
        counter = Integer.MAX_VALUE / 2;
    }

    private long timerMillis() {
        long now = System.nanoTime();
        if (timerMode.index == 1) return (accumNs + (running ? now - startedNs : 0)) / 1_000_000L;
        return (now - joinNs) / 1_000_000L;
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    // ------------------------------------------------------------------ entity scan (slow interval)

    private void clearNearby() {
        nearCount = 0;
        hostileCount = 0;
        nearestName = null;
        nearestDist = 0;
        nearestHp = 0;
        nearestDir = "";
    }

    private void scan(Minecraft mc, Player p) {
        if (!iNearby.value) {
            clearNearby();
            return;
        }
        double r = radius.value, r2 = r * r;
        int n = 0;
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player o : mc.level.players()) {
            if (o == p || o.isSpectator()) continue;
            double d2 = o.distanceToSqr(p);
            if (d2 > r2) continue;
            if (ignoreFriends.value && Friends.isFriend(o.getName().getString())) continue;
            n++;
            if (d2 < bestD) {
                bestD = d2;
                best = o;
            }
        }
        nearCount = n;
        if (best == null) {
            nearestName = null;
        } else {
            nearestName = best.getName().getString();
            nearestDist = (float) Math.sqrt(bestD);
            nearestHp = best.getHealth();
            nearestDir = compass(best.getX() - p.getX(), best.getZ() - p.getZ());
        }
        int h = 0;
        if (countHostile.value) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof Enemy && e.distanceToSqr(p) <= r2) h++;
            }
        }
        hostileCount = h;
    }

    /** N / NE / E ... for an offset on the ground plane (north is -Z, east is +X). */
    private static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        int i = (int) Math.round(((deg % 360 + 360) % 360) / 45.0) % 8;
        return COMPASS[i];
    }

    // ------------------------------------------------------------------ build the cached rows

    private void rebuild(Minecraft mc, Player p) {
        Snap s = new Snap();
        if (iHealth.value || iAbsorption.value) {
            s.hp = p.getHealth();
            s.maxHp = Math.max(1f, p.getMaxHealth());
            s.abs = p.getAbsorptionAmount();
        }
        if (iArmor.value) s.armor = p.getArmorValue();
        if (iFood.value || iSaturation.value) {
            s.food = p.getFoodData().getFoodLevel();
            s.sat = p.getFoodData().getSaturationLevel();
        }
        BlockPos bp = p.blockPosition();
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.bx = bp.getX();
        s.by = bp.getY();
        s.bz = bp.getZ();
        if (iFps.value) s.fps = FrameClock.fps();
        if (iPing.value) s.ping = ping(mc, p);
        if (iTimer.value) s.timerMs = timerMillis();
        if (iBiome.value) s.biome = mc.level.getBiome(bp).unwrapKey().map(KeyNames::prettyKey).orElse("Unknown");
        s.near = nearCount;
        s.hostile = hostileCount;
        s.nearestName = nearestName;
        s.nearestDist = nearestDist;
        s.nearestHp = nearestHp;
        s.nearestDir = nearestDir;
        build(s);
    }

    /** Fixed values so the element can be placed in the HUD editor even before you are in a world. */
    private void buildSample() {
        Snap s = new Snap();
        s.hp = 17f;
        s.maxHp = 20f;
        s.abs = 4f;
        s.sat = 5.2f;
        s.armor = 14;
        s.food = 18;
        s.x = 128.4;
        s.y = 64;
        s.z = -302.7;
        s.bx = 128;
        s.by = 64;
        s.bz = -303;
        s.fps = 240;
        s.ping = 32;
        s.timerMs = 754_000L;
        s.biome = "Plains";
        s.near = 2;
        s.hostile = 1;
        s.nearestName = "Player";
        s.nearestDist = 21.5f;
        s.nearestHp = 15f;
        s.nearestDir = "NE";
        build(s);
        sampleShown = true;
    }

    private void build(Snap s) {
        boolean det = mode.index == 1;
        boolean hearts = healthUnit.index == 0;
        rows.clear();

        if (iHealth.value) {
            float ratio = s.hp / s.maxHp;
            String v = hearts
                    ? (det ? num(s.hp / 2f) + " / " + num(s.maxHp / 2f) + " hearts" : num(s.hp / 2f))
                    : (det ? num(s.hp) + " / " + num(s.maxHp) : num(s.hp));
            put(det ? "Health" : "HP", v, dyn(ratio > 0.6f ? Theme.GOOD : ratio > 0.3f ? Theme.WARN : Theme.BAD));
        }
        if (iAbsorption.value && (s.abs > 0.05f || !hideZeroAbs.value)) {
            String v = "+" + (hearts ? num(s.abs / 2f) + (det ? " hearts" : "") : num(s.abs));
            put(det ? "Absorption" : "Abs", v, absorptionColor.color());
        }
        if (iArmor.value) put(det ? "Armor" : "Arm", det ? s.armor + " / 20" : String.valueOf(s.armor), 0);
        if (iFood.value) {
            put("Food", det ? s.food + " / 20" : String.valueOf(s.food), dyn(s.food <= 6 ? Theme.BAD : s.food <= 10 ? Theme.WARN : 0));
        }
        if (iSaturation.value) put(det ? "Saturation" : "Sat", String.format(Locale.ROOT, "%.1f", s.sat), 0);
        if (iCoords.value) {
            put(det ? "Position" : "XYZ", det
                    ? String.format(Locale.ROOT, "%.1f / %.1f / %.1f", s.x, s.y, s.z)
                    : s.bx + " " + s.by + " " + s.bz, 0);
        }
        if (iFps.value) put("FPS", String.valueOf(s.fps), dyn(s.fps >= 100 ? Theme.GOOD : s.fps >= 45 ? 0 : s.fps >= 25 ? Theme.WARN : Theme.BAD));
        if (iPing.value) put("Ping", s.ping + " ms", dyn(s.ping < 80 ? Theme.GOOD : s.ping < 160 ? Theme.WARN : Theme.BAD));
        if (iTimer.value) put(det ? "Timer" : "Time", clock(s.timerMs), 0);
        if (iBiome.value) put("Biome", s.biome, 0);
        if (iNearby.value) {
            String v = s.near + (s.near == 1 ? " player" : " players");
            if (countHostile.value) v += ", " + s.hostile + " hostile";
            put(det ? "Nearby" : "Near", v, dyn(s.near >= 3 ? Theme.BAD : s.near > 0 ? Theme.WARN : Theme.GOOD));
            if (showNearest.value && s.nearestName != null) {
                String n = Theme.fit(s.nearestName, 90);
                String d = Math.round(s.nearestDist) + "m";
                String v2 = det
                        ? n + " " + d + " " + s.nearestDir + " " + (hearts ? num(s.nearestHp / 2f) + " hearts" : num(s.nearestHp) + " hp")
                        : n + " " + d;
                put(det ? "Nearest" : "Closest", v2, 0);
            }
        }
        measure();
    }

    private int dyn(int color) {
        return dynamicColors.value ? color : 0;
    }

    private void put(String label, String value, int color) {
        rows.add(new Row(label, value, color, Theme.width(label), Theme.width(value)));
    }

    /** 9 -> "9", 9.5 -> "9.5". */
    private static String num(float v) {
        float r = Math.round(v * 10f) / 10f;
        return r == (int) r ? String.valueOf((int) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    private static int ping(Minecraft mc, Player p) {
        if (mc.getConnection() == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Pre-computes the unscaled box size for the chosen layout. */
    private void measure() {
        int n = rows.size();
        if (n == 0) {
            baseW = baseH = 0;
            return;
        }
        int colon = Theme.width(": ");
        switch (layout.index) {
            case 1 -> { // Split
                int ml = 0, mv = 0;
                for (Row r : rows) {
                    ml = Math.max(ml, r.lw());
                    mv = Math.max(mv, r.vw());
                }
                baseW = PAD * 2 + ml + GAP + mv;
                baseH = PAD + n * ROW;
            }
            case 2 -> { // Two columns
                int cw = 0;
                for (Row r : rows) cw = Math.max(cw, r.lw() + colon + r.vw());
                int perCol = (n + 1) / 2;
                baseW = PAD * 2 + cw * (n > 1 ? 2 : 1) + (n > 1 ? GAP * 2 : 0);
                baseH = PAD + perCol * ROW;
            }
            default -> { // Classic
                int cw = 0;
                for (Row r : rows) cw = Math.max(cw, r.lw() + colon + r.vw());
                baseW = PAD * 2 + cw;
                baseH = PAD + n * ROW;
            }
        }
    }

    // ------------------------------------------------------------------ HUD

    private void ensureSample() {
        if (rows.isEmpty() && HudRenderer.editing()) buildSample();
    }

    @Override
    public int width(Minecraft mc) {
        ensureSample();
        return rows.isEmpty() ? 0 : baseW;
    }

    @Override
    public int height(Minecraft mc) {
        ensureSample();
        return rows.isEmpty() ? 0 : baseH;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int n = rows.size();
        if (n == 0) return;
        int colon = Theme.width(": ");
        switch (layout.index) {
            case 1 -> {
                int y = PAD - 1;
                for (Row r : rows) {
                    text(g, r.label(), PAD, y, dimColor());
                    text(g, r.value(), baseW - PAD - r.vw(), y, valueColor(r));
                    y += ROW;
                }
            }
            case 2 -> {
                int perCol = (n + 1) / 2;
                int cw = (baseW - PAD * 2 - GAP * 2) / 2;
                for (int i = 0; i < n; i++) {
                    int col = i / perCol, row = i % perCol;
                    drawInline(g, rows.get(i), PAD + col * (cw + GAP * 2), PAD - 1 + row * ROW, colon);
                }
            }
            default -> {
                int y = PAD - 1;
                for (Row r : rows) {
                    drawInline(g, r, PAD, y, colon);
                    y += ROW;
                }
            }
        }
    }

    private void drawInline(GuiGraphics g, Row r, int x, int y, int colon) {
        text(g, r.label() + ": ", x, y, dimColor());
        text(g, r.value(), x + r.lw() + colon, y, valueColor(r));
    }

    private int valueColor(Row r) {
        return r.color() == 0 ? textColor() : r.color();
    }
}
