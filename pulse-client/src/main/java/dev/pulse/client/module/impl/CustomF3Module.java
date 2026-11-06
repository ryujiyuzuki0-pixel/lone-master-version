package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import dev.pulse.client.util.FrameClock;
import dev.pulse.client.util.KeyNames;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Replacement for the vanilla F3 screen. All values are gathered in {@link #tick} every N ticks (the "Update
 * interval" setting) into a cached list with pre-measured widths; the per-frame draw only paints that list.
 * The vanilla overlay is hidden by DebugOverlayMixin while this module is on.
 */
public final class CustomF3Module extends Module {
    private record Line(String label, String value, int color, int lw, int vw) {}

    private static final int ROW = 11, PAD = 6, GAP = 10;

    // ---- display
    private final ChoiceSetting mode = add(new ChoiceSetting("Mode", 1, "Compact", "Detailed"), "Display");
    private final ChoiceSetting layout = add(new ChoiceSetting("Layout", 0, "Classic", "Split", "Two columns", "Inline strip"), "Display");
    private final ChoiceSetting visibility = add(new ChoiceSetting("Visible", 0, "Toggle with F3", "Always"), "Display");
    private final BoolSetting hideVanilla = add(new BoolSetting("Hide vanilla F3 text", true), "Display");
    private final BoolSetting dynamicColors = add(new BoolSetting("Dynamic colors (FPS, ping, TPS)", true), "Display");
    private final SliderSetting textSize = add(new SliderSetting("Text size", 0.6, 2.0, 0.1, 1.0, "x"), "Display");
    private final SliderSetting interval = add(new SliderSetting("Update interval", 1, 20, 1, 5, " ticks"), "Display");

    // ---- information
    private final BoolSetting iFps = add(new BoolSetting("FPS", true), "Information");
    private final BoolSetting iXyz = add(new BoolSetting("XYZ coordinates", true), "Information");
    private final BoolSetting iBlock = add(new BoolSetting("Block coordinates", false), "Information");
    private final BoolSetting iFacing = add(new BoolSetting("Facing", true), "Information");
    private final BoolSetting iBiome = add(new BoolSetting("Biome", true), "Information");
    private final BoolSetting iDim = add(new BoolSetting("Dimension", true), "Information");
    private final BoolSetting iLight = add(new BoolSetting("Light level", false), "Information");
    private final BoolSetting iPing = add(new BoolSetting("Ping", true), "Information");
    private final BoolSetting iTps = add(new BoolSetting("TPS", false), "Information");
    private final BoolSetting iMem = add(new BoolSetting("Memory", true), "Information");
    private final BoolSetting iEnt = add(new BoolSetting("Entity count", false), "Information");
    private final BoolSetting iChunk = add(new BoolSetting("Chunk info", false), "Information");
    private final BoolSetting iSpeed = add(new BoolSetting("Player speed", false), "Information");
    private final BoolSetting iServer = add(new BoolSetting("Server", false), "Information");
    private final BoolSetting iWorld = add(new BoolSetting("World", false), "Information");
    private final BoolSetting iVersion = add(new BoolSetting("Game version", false), "Information");
    private final BoolSetting iJava = add(new BoolSetting("Java version", false), "Information");

    private final ActionSetting reset = add(new ActionSetting("Reset configuration", "Reset", this::resetSettings), "Manage");

    // ---- cached state
    private final List<Line> lines = new ArrayList<>();
    private int baseW, baseH;
    private int counter;
    private boolean shown;

    // F3 key edge detection
    private boolean f3Down, f3Combo;

    // speed (blocks/second, smoothed, sampled every tick)
    private double px, py, pz, speed;
    private boolean hasPrev;

    // TPS estimate from server time corrections
    private long lastGameTime = Long.MIN_VALUE;
    private long serverTicks;
    private long tpsWindowStart;
    private double tps = -1;

    private static volatile boolean suppress;

    public CustomF3Module() {
        super("customf3", "Custom F3", "A configurable replacement for the F3 debug screen.",
                Category.HUD, true, 0.25f, 0.02f, true);
    }

    /** Read by DebugOverlayMixin (render thread). */
    public static boolean suppressVanilla() {
        return suppress;
    }

    @Override
    public void onEnable() {
        counter = 0;
        lines.clear();
        hasPrev = false;
        lastGameTime = Long.MIN_VALUE;
        suppress = hideVanilla.value;
    }

    @Override
    public void onDisable() {
        suppress = false;
        shown = false;
    }

    // ------------------------------------------------------------------ tick (all data gathering lives here)

    @Override
    public void tick(Minecraft mc) {
        suppress = hideVanilla.value;
        Player p = mc.player;
        if (p == null || mc.level == null || mc.getWindow() == null) {
            hasPrev = false;
            lastGameTime = Long.MIN_VALUE;
            return;
        }
        pollKey(mc);
        sampleSpeed(p);
        sampleTps(mc);
        if (++counter >= interval.intValue() || lines.isEmpty()) {
            counter = 0;
            rebuild(mc, p);
        }
    }

    private void pollKey(Minecraft mc) {
        boolean down = mc.screen == null && GLFW.glfwGetKey(mc.getWindow().handle(), GLFW.GLFW_KEY_F3) == GLFW.GLFW_PRESS;
        if (down && !f3Down) f3Combo = false;
        if (down && !f3Combo) f3Combo = otherKeyDown(mc);
        // like vanilla: a bare F3 tap toggles, F3+key is a debug shortcut and does not
        if (!down && f3Down && !f3Combo) shown = !shown;
        f3Down = down;
    }

    private static boolean otherKeyDown(Minecraft mc) {
        long w = mc.getWindow().handle();
        for (int k = GLFW.GLFW_KEY_0; k <= GLFW.GLFW_KEY_Z; k++) {
            if (k > GLFW.GLFW_KEY_9 && k < GLFW.GLFW_KEY_A) continue;
            if (GLFW.glfwGetKey(w, k) == GLFW.GLFW_PRESS) return true;
        }
        for (int k = GLFW.GLFW_KEY_F1; k <= GLFW.GLFW_KEY_F12; k++) {
            if (k != GLFW.GLFW_KEY_F3 && GLFW.glfwGetKey(w, k) == GLFW.GLFW_PRESS) return true;
        }
        return false;
    }

    private void sampleSpeed(Player p) {
        if (hasPrev) {
            double dx = p.getX() - px, dz = p.getZ() - pz;
            double now = Math.sqrt(dx * dx + dz * dz) * 20.0;
            speed += (now - speed) * 0.3;
        }
        px = p.getX();
        py = p.getY();
        pz = p.getZ();
        hasPrev = true;
    }

    /**
     * Clients cannot read the server TPS directly. The server corrects the client's game time about once a second,
     * so (client ticks + corrections) / real time approximates it. Only available after a few seconds, and not
     * while the game is paused.
     */
    private void sampleTps(Minecraft mc) {
        long gt = mc.level.getGameTime();
        long now = System.nanoTime();
        if (mc.isPaused() || lastGameTime == Long.MIN_VALUE) {
            lastGameTime = gt;
            serverTicks = 0;
            tpsWindowStart = now;
            return;
        }
        serverTicks += gt - lastGameTime;
        lastGameTime = gt;
        long dt = now - tpsWindowStart;
        if (dt >= 5_000_000_000L) {
            tps = Math.max(0, Math.min(20, serverTicks * 1e9 / dt));
            serverTicks = 0;
            tpsWindowStart = now;
        }
    }

    // ------------------------------------------------------------------ build the cached lines

    private void rebuild(Minecraft mc, Player p) {
        boolean det = mode.index == 1;
        lines.clear();
        BlockPos bp = p.blockPosition();

        if (iFps.value) {
            int f = FrameClock.fps();
            put(det ? "FPS" : "FPS", String.valueOf(f), dyn(f >= 100 ? Theme.GOOD : f >= 45 ? 0 : f >= 25 ? Theme.WARN : Theme.BAD));
        }
        if (iXyz.value) {
            put(det ? "Position" : "XYZ", det
                    ? String.format(Locale.ROOT, "%.3f / %.3f / %.3f", p.getX(), p.getY(), p.getZ())
                    : String.format(Locale.ROOT, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()), 0);
        }
        if (iBlock.value) put(det ? "Block" : "Blk", bp.getX() + " " + bp.getY() + " " + bp.getZ(), 0);
        if (iFacing.value) {
            String n = p.getDirection().getName();
            String name = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            float yaw = ((p.getYRot() % 360f) + 540f) % 360f - 180f;
            put(det ? "Facing" : "Dir", det ? String.format(Locale.ROOT, "%s (%.1f / %.1f)", name, yaw, p.getXRot()) : name, 0);
        }
        if (iBiome.value) {
            String b = mc.level.getBiome(bp).unwrapKey().map(KeyNames::prettyKey).orElse("Unknown");
            put("Biome", b, 0);
        }
        if (iDim.value) put(det ? "Dimension" : "Dim", KeyNames.prettyKey(mc.level.dimension()), 0);
        if (iLight.value) {
            int bl = mc.level.getBrightness(LightLayer.BLOCK, bp), sl = mc.level.getBrightness(LightLayer.SKY, bp);
            put("Light", det ? bl + " block / " + sl + " sky" : bl + " / " + sl, 0);
        }
        if (iPing.value) {
            int ms = ping(mc);
            put("Ping", ms + " ms", dyn(ms < 80 ? Theme.GOOD : ms < 160 ? Theme.WARN : Theme.BAD));
        }
        if (iTps.value) {
            if (tps < 0) put("TPS", "...", 0);
            else put("TPS", String.format(Locale.ROOT, "%.1f", tps), dyn(tps >= 19 ? Theme.GOOD : tps >= 15 ? Theme.WARN : Theme.BAD));
        }
        if (iMem.value) {
            Runtime r = Runtime.getRuntime();
            long max = r.maxMemory() >> 20, used = (r.totalMemory() - r.freeMemory()) >> 20;
            put(det ? "Memory" : "Mem", det ? used + " / " + max + " MB (" + used * 100 / Math.max(1, max) + "%)" : used * 100 / Math.max(1, max) + "%", 0);
        }
        if (iEnt.value) {
            int n = 0;
            for (Entity ignored : mc.level.entitiesForRendering()) n++;
            put(det ? "Entities" : "Ent", String.valueOf(n), 0);
        }
        if (iChunk.value) {
            int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
            String v = det
                    ? "chunk " + cx + ", " + cz + " (in-chunk " + (bp.getX() & 15) + " " + (bp.getY() & 15) + " " + (bp.getZ() & 15)
                    + ")  loaded " + mc.level.getChunkSource().getLoadedChunksCount() + "  render " + mc.options.renderDistance().get()
                    : cx + " " + cz;
            put("Chunk", v, 0);
        }
        if (iSpeed.value) put("Speed", String.format(Locale.ROOT, det ? "%.2f blocks/s" : "%.1f b/s", speed), 0);
        if (iServer.value) {
            String s = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : mc.getSingleplayerServer() != null ? "Integrated" : "None";
            put("Server", s, 0);
        }
        if (iWorld.value) {
            String w = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getWorldData().getLevelName() : "Multiplayer";
            put("World", w, 0);
        }
        if (iVersion.value) {
            String v = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            put(det ? "Game version" : "MC", v, 0);
        }
        if (iJava.value) put(det ? "Java version" : "Java", System.getProperty("java.version", "?"), 0);

        measure();
    }

    private int dyn(int color) {
        return dynamicColors.value ? color : 0;
    }

    private void put(String label, String value, int color) {
        lines.add(new Line(label, value, color, Theme.width(label), Theme.width(value)));
    }

    private static int ping(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Pre-computes the unscaled box size for the chosen layout. */
    private void measure() {
        int n = lines.size();
        if (n == 0) {
            baseW = baseH = 0;
            return;
        }
        int colon = Theme.width(": ");
        switch (layout.index) {
            case 1 -> { // Split
                int ml = 0, mv = 0;
                for (Line l : lines) {
                    ml = Math.max(ml, l.lw());
                    mv = Math.max(mv, l.vw());
                }
                baseW = PAD * 2 + ml + GAP + mv;
                baseH = PAD + n * ROW;
            }
            case 2 -> { // Two columns
                int cw = 0;
                for (Line l : lines) cw = Math.max(cw, l.lw() + colon + l.vw());
                int rows = (n + 1) / 2;
                baseW = PAD * 2 + cw * (n > 1 ? 2 : 1) + (n > 1 ? GAP * 2 : 0);
                baseH = PAD + rows * ROW;
            }
            case 3 -> { // Inline strip
                int w = PAD * 2;
                for (Line l : lines) w += l.lw() + colon + l.vw();
                baseW = w + (n - 1) * GAP;
                baseH = PAD * 2 + 5;
            }
            default -> { // Classic
                int cw = 0;
                for (Line l : lines) cw = Math.max(cw, l.lw() + colon + l.vw());
                baseW = PAD * 2 + cw;
                baseH = PAD + n * ROW;
            }
        }
    }

    // ------------------------------------------------------------------ HUD

    private boolean visible() {
        return HudRenderer.editing() || visibility.index == 1 || shown;
    }

    @Override
    public int width(Minecraft mc) {
        return visible() ? Math.round(baseW * (float) textSize.value) : 0;
    }

    @Override
    public int height(Minecraft mc) {
        return visible() ? Math.round(baseH * (float) textSize.value) : 0;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        if (lines.isEmpty()) return;
        float s = (float) textSize.value;
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        int colon = Theme.width(": ");
        int n = lines.size();
        switch (layout.index) {
            case 1 -> {
                int ml = 0;
                for (Line l : lines) ml = Math.max(ml, l.lw());
                int y = PAD - 1;
                for (Line l : lines) {
                    text(g, l.label(), PAD, y, dimColor());
                    text(g, l.value(), baseW - PAD - l.vw(), y, valueColor(l));
                    y += ROW;
                }
            }
            case 2 -> {
                int rows = (n + 1) / 2;
                int cw = (baseW - PAD * 2 - GAP * 2) / 2;
                for (int i = 0; i < n; i++) {
                    int col = i / rows, row = i % rows;
                    drawInline(g, lines.get(i), PAD + col * (cw + GAP * 2), PAD - 1 + row * ROW, colon);
                }
            }
            case 3 -> {
                int x = PAD;
                for (Line l : lines) {
                    drawInline(g, l, x, PAD, colon);
                    x += l.lw() + colon + l.vw() + GAP;
                }
            }
            default -> {
                int y = PAD - 1;
                for (Line l : lines) {
                    drawInline(g, l, PAD, y, colon);
                    y += ROW;
                }
            }
        }
        g.pose().popMatrix();
    }

    private void drawInline(GuiGraphics g, Line l, int x, int y, int colon) {
        text(g, l.label() + ": ", x, y, dimColor());
        text(g, l.value(), x + l.lw() + colon, y, valueColor(l));
    }

    private int valueColor(Line l) {
        return l.color() == 0 ? textColor() : l.color();
    }
}
