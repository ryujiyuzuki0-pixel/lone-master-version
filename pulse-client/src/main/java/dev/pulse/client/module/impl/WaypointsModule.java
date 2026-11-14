package dev.pulse.client.module.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.pulse.client.PulseClient;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.WaypointScreen;
import dev.pulse.client.util.KeyLatch;
import dev.pulse.client.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;

/**
 * Named, colour-coded waypoints, per dimension. Managed on the Waypoints screen, with /pulse waypoint ..., or with
 * the quick-add key. The HUD lists the nearest ones with an arrow, and in first person each one is also marked in
 * the world with a diamond, its name, distance and a beam.
 * <p>
 * Markers and beams are drawn on the HUD layer by projecting each waypoint onto the screen from your view, so
 * they show through walls and always point at the right place. They follow your view, so they are hidden in third
 * person. Saved to config/pulse-waypoints.json.
 */
public final class WaypointsModule extends Module {
    public static final class Waypoint {
        public String name;
        public double x, y, z;
        public String dim;
        /** RGB colour, or 0 to pick one from the name. */
        public int color;
        /** Hidden waypoints stay saved but are not drawn in the world or on the list. */
        public boolean hidden;

        /** Colour used everywhere this waypoint is drawn. */
        public int rgb() {
            return color != 0 ? color & 0xFFFFFF : PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)];
        }
    }

    /** Colours offered in the waypoint editor. */
    public static final int[] PALETTE = {0xF87171, 0xFB923C, 0xFBBF24, 0xA3E635, 0x4ADE80, 0x2DD4BF,
            0x38BDF8, 0x60A5FA, 0x818CF8, 0xC084FC, 0xF472B6, 0xE5E7EB};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
    private static final int ROW = 13;

    private final List<Waypoint> points = new ArrayList<>();
    private final KeyLatch addLatch = new KeyLatch(), openLatch = new KeyLatch();
    private final SliderSetting rows = add(new SliderSetting("Rows", 1, 8, 1, 3, ""));
    private final BoolSetting showArrow = add(new BoolSetting("Show arrow", true));
    private final BoolSetting showDistance = add(new BoolSetting("Show distance", true));
    private final ChoiceSetting sort = add(new ChoiceSetting("Sort", 0, "Nearest", "Name"));
    private final SliderSetting maxDistance = add(new SliderSetting("Max distance", 0, 5000, 50, 0, "m").zero("Unlimited"));
    private final BoolSetting markers = add(new BoolSetting("In-world markers", true), "Markers");
    private final BoolSetting beams = add(new BoolSetting("Beams", true), "Markers", () -> markers.value);
    private final BoolSetting edge = add(new BoolSetting("Edge indicators", true), "Markers", () -> markers.value);
    private final BoolSetting convert = add(new BoolSetting("Overworld/Nether conversion", false), "Markers", () -> markers.value);
    private final SliderSetting markerLimit = add(new SliderSetting("Max markers", 1, 30, 1, 12, ""), "Markers", () -> markers.value);
    private final SliderSetting markerScale = add(new SliderSetting("Marker size", 0.75, 2, 0.25, 1, "x"), "Markers", () -> markers.value);
    private final KeybindSetting quickAddKey = add(new KeybindSetting("Quick add key", GLFW.GLFW_KEY_UNKNOWN), "Keys");
    private final KeybindSetting managerKey = add(new KeybindSetting("Open manager key", GLFW.GLFW_KEY_UNKNOWN), "Keys");
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 110, 240, 2, 150, "px"), "Layout");
    private final ColorSetting arrowColor = add(new ColorSetting("Arrow color", 0xFFFFFFFF, false, true).withMode(1), "Colors");
    private final ActionSetting openManager = add(new ActionSetting("Manage waypoints", "Open", () -> {
        Minecraft m = Minecraft.getInstance();
        m.setScreen(new WaypointScreen(m.screen));
    }), "Manage");
    private final ActionSetting addHere = add(new ActionSetting("Add waypoint", "Add here", () -> {
        Minecraft m = Minecraft.getInstance();
        if (m.player != null) add(m, "WP" + (points.size() + 1));
    }), "Manage");
    private final ActionSetting clearAll = add(new ActionSetting("Remove all waypoints", "Clear", () -> clear()), "Manage");

    public WaypointsModule() {
        super("waypoints", "Waypoints", "Nearest saved waypoints. Use /pulse waypoint add <name>.",
                Category.UTILITY, true, 0.992f, 0.08f, true);
        load();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pulse-waypoints.json");
    }

    private void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            List<Waypoint> in = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {}.getType());
            if (in != null) points.addAll(in);
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not read pulse-waypoints.json", e);
        }
    }

    private void save() {
        try {
            Files.writeString(file(), GSON.toJson(points));
        } catch (Exception e) {
            PulseClient.LOG.warn("Could not save pulse-waypoints.json", e);
        }
    }

    private static String dimKey(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().toString();
    }

    public boolean add(Minecraft mc, String name) {
        if (mc.player == null || mc.level == null) return false;
        points.removeIf(w -> w.name.equalsIgnoreCase(name));
        Waypoint w = new Waypoint();
        w.name = name;
        w.x = mc.player.getX();
        w.y = mc.player.getY();
        w.z = mc.player.getZ();
        w.dim = dimKey(mc);
        points.add(w);
        save();
        return true;
    }

    /** Adds a waypoint at explicit coordinates (used by Death Info). {@code dim} is the raw dimension key. */
    public boolean addAt(String name, double x, double y, double z, String dim) {
        points.removeIf(w -> w.name.equalsIgnoreCase(name));
        Waypoint w = new Waypoint();
        w.name = name;
        w.x = x;
        w.y = y;
        w.z = z;
        w.dim = dim;
        points.add(w);
        save();
        return true;
    }

    /** The raw dimension key waypoints are stored under for the current level. */
    public static String currentDim(Minecraft mc) {
        return dimKey(mc);
    }

    public boolean remove(String name) {
        boolean r = points.removeIf(w -> w.name.equalsIgnoreCase(name));
        if (r) save();
        return r;
    }

    public void clear() {
        points.clear();
        save();
    }

    public List<Waypoint> all() {
        return points;
    }

    /** Saves after the manager screen edited a waypoint in place. */
    public void changed() {
        save();
    }

    /** Renames {@code old} to {@code name}. Returns false if the name is empty or already used by another waypoint. */
    public boolean rename(Waypoint w, String name) {
        String n = name.trim();
        if (n.isEmpty()) return false;
        for (Waypoint o : points) if (o != w && o.name.equalsIgnoreCase(n)) return false;
        w.name = n;
        save();
        return true;
    }

    /** Creates a waypoint at the player's position with a free default name and returns it, or null with no world. */
    public Waypoint addHere(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        int n = 1;
        while (find("Waypoint " + n) != null) n++;
        return add(mc, "Waypoint " + n) ? find("Waypoint " + n) : null;
    }

    public Waypoint find(String name) {
        for (Waypoint w : points) if (w.name.equalsIgnoreCase(name)) return w;
        return null;
    }

    /** "Overworld", "Nether", "End", or the raw key for other dimensions. */
    public static String dimLabel(String raw) {
        if (raw == null) return "Unknown";
        return raw.contains("the_nether") ? "Nether" : raw.contains("the_end") ? "End" : raw.contains("overworld") ? "Overworld" : raw;
    }

    // ------------------------------------------------------------------ in-world markers

    @Override
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.screen != null) {
            addLatch.pressed(false);
            openLatch.pressed(false);
            return;
        }
        if (addLatch.pressed(quickAddKey.isDown(mc))) {
            Waypoint w = addHere(mc);
            if (w != null && mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Added " + w.name), false);
        }
        if (openLatch.pressed(managerKey.isDown(mc))) mc.setScreen(new WaypointScreen(null));
    }

    /** Where the waypoint is in the current dimension, or null if it does not apply here. */
    private double[] resolve(Waypoint w, String dim) {
        if (dim.equals(w.dim)) return new double[]{w.x, w.y, w.z};
        if (!convert.value) return null;
        boolean here = dim.contains("the_nether"), there = w.dim != null && w.dim.contains("the_nether");
        boolean hereOver = dim.contains("overworld"), thereOver = w.dim != null && w.dim.contains("overworld");
        if (here && thereOver) return new double[]{w.x / 8.0, w.y, w.z / 8.0};
        if (hereOver && there) return new double[]{w.x * 8.0, w.y, w.z * 8.0};
        return null;
    }

    /** Projects a world position to GUI coordinates: {x, y, depth}. depth <= 0 means behind the camera. */
    private static double[] project(double wx, double wy, double wz, Vec3Frame f) {
        double dx = wx - f.ex, dy = wy - f.ey, dz = wz - f.ez;
        double depth = dx * f.fx + dy * f.fy + dz * f.fz;
        double rx = dx * f.rx + dz * f.rz;
        double ux = dx * f.ux + dy * f.uy + dz * f.uz;
        double safe = Math.max(Math.abs(depth), 0.05);
        double ndcX = rx / safe / (f.tan * f.aspect);
        double ndcY = ux / safe / f.tan;
        if (depth < 0) { // behind you: mirror so the edge indicator points the way to turn
            ndcX = -ndcX;
            ndcY = -ndcY;
        }
        return new double[]{ndcX, ndcY, depth};
    }

    private record Vec3Frame(double ex, double ey, double ez, double fx, double fy, double fz, double rx, double rz,
                             double ux, double uy, double uz, double tan, double aspect) {}

    @Override
    public void drawOverlay(GuiGraphics g, Minecraft mc) {
        if (!markers.value || mc.player == null || mc.level == null || !mc.options.getCameraType().isFirstPerson()) return;
        if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) return;
        String dim = dimKey(mc);
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var eye = mc.player.getEyePosition(pt);
        double yaw = Math.toRadians(mc.player.getViewYRot(pt)), pitch = Math.toRadians(mc.player.getViewXRot(pt));
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy; // right x forward
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        Vec3Frame frame = new Vec3Frame(eye.x, eye.y, eye.z, fx, fy, fz, rx, rz, ux, uy, uz,
                Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0), sw / (double) sh);

        record Candidate(Waypoint w, double[] pos, double dist) {}
        List<Candidate> list = new ArrayList<>();
        double maxD = maxDistance.value;
        for (Waypoint w : points) {
            if (w.hidden) continue;
            double[] p = resolve(w, dim);
            if (p == null) continue;
            double d = Math.sqrt(Mth.square(p[0] - eye.x) + Mth.square(p[1] - eye.y) + Mth.square(p[2] - eye.z));
            if (maxD > 0 && d > maxD) continue;
            list.add(new Candidate(w, p, d));
        }
        list.sort(Comparator.comparingDouble(Candidate::dist));
        int shown = 0;
        float s = (float) markerScale.value;
        for (Candidate c : list) {
            if (shown >= markerLimit.value) break;
            double[] pr = project(c.pos[0], c.pos[1] + 0.5, c.pos[2], frame);
            double nx = pr[0], ny = pr[1];
            boolean front = pr[2] > 0.05;
            boolean onScreen = front && Math.abs(nx) <= 0.96 && Math.abs(ny) <= 0.94;
            if (!onScreen && !edge.value) continue;
            if (!onScreen) {
                double m = Math.max(Math.abs(nx), Math.abs(ny));
                if (m < 1e-6) { nx = 0; ny = -1; m = 1; }
                nx = nx / m * 0.93;
                ny = ny / m * 0.9;
            }
            int sx = (int) Math.round(sw / 2.0 + nx * sw / 2.0), sy = (int) Math.round(sh / 2.0 - ny * sh / 2.0);
            int rgb = 0xFF000000 | c.w.rgb();
            float fade = (float) Math.max(0.55, 1.0 - c.dist / 600.0);
            if (onScreen && beams.value) g.fillGradient(sx - 1, 0, sx + 1, sy, Theme.alpha(rgb, 0f), Theme.alpha(rgb, 0.55f * fade));
            drawMarker(g, sx, sy, s, rgb, fade, !onScreen);
            String label = Theme.fit(c.w.name, 90);
            String dist = c.dist >= 1000 ? String.format(Locale.ROOT, "%.1fkm", c.dist / 1000.0) : Math.round(c.dist) + "m";
            Theme.scaledText(g, label, sx - Theme.width(label) / 2, sy - Math.round(10 * s) - 9, 0.9f, Theme.alpha(0xFFFFFFFF, fade), true);
            Theme.scaledText(g, dist, sx - Theme.width(dist) / 2, sy + Math.round(7 * s), 0.8f, Theme.alpha(rgb, fade), true);
            shown++;
        }
    }

    /** A small diamond; edge indicators are drawn dimmer and smaller. */
    private static void drawMarker(GuiGraphics g, int cx, int cy, float scale, int rgb, float fade, boolean edgeMarker) {
        int r = Math.round((edgeMarker ? 4f : 5f) * scale);
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, Theme.alpha(rgb, (edgeMarker ? 0.7f : 1f) * fade));
        }
        int inner = r - 2;
        for (int i = -inner; i <= inner; i++) {
            int half = inner - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, Theme.alpha(0xFFFFFFFF, 0.55f * fade));
        }
    }

    private List<Waypoint> nearest(Minecraft mc) {
        List<Waypoint> out = new ArrayList<>();
        if (HudRenderer.editing() && points.isEmpty()) {
            for (String n : new String[]{"Base", "Spawn"}) {
                Waypoint w = new Waypoint();
                w.name = n;
                w.dim = dimKey(mc);
                out.add(w);
            }
            return out;
        }
        if (mc.player == null) return out;
        String dim = dimKey(mc);
        double maxD = maxDistance.value;
        for (Waypoint w : points) {
            if (w.hidden || !dim.equals(w.dim)) continue;
            if (maxD > 0 && dist2(mc, w) > maxD * maxD) continue;
            out.add(w);
        }
        if (sort.index == 1) out.sort(Comparator.comparing((Waypoint w) -> w.name.toLowerCase()));
        else out.sort(Comparator.comparingDouble(w -> dist2(mc, w)));
        int max = (int) rows.value;
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static double dist2(Minecraft mc, Waypoint w) {
        double dx = w.x - mc.player.getX(), dz = w.z - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    @Override public int width(Minecraft mc) { return boxWidth.intValue(); }

    @Override
    public int height(Minecraft mc) {
        int n = nearest(mc).size();
        return n == 0 ? 0 : n * ROW + 6;
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        int row = 0;
        for (Waypoint wp : nearest(mc)) {
            int y = 4 + row++ * ROW;
            String arrow = "\u2191";
            String dist = "--";
            if (mc.player != null && (wp.x != 0 || wp.z != 0)) {
                double dx = wp.x - mc.player.getX(), dz = wp.z - mc.player.getZ();
                double target = Math.toDegrees(Math.atan2(-dx, dz));
                double rel = ((target - mc.player.getYRot()) % 360 + 360) % 360;
                arrow = ARROWS[(int) Math.round(rel / 45.0) % 8];
                dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz)) + "m";
            }
            if (showArrow.value) text(g, arrow, 6, y, arrowColor.mode == 1 ? 0xFF000000 | wp.rgb() : arrowColor.color());
            int nameW = w - 26 - (showDistance.value ? 44 : 0);
            text(g, Theme.fit(wp.name, nameW), showArrow.value ? 20 : 6, y, textColor());
            if (showDistance.value) text(g, dist, w - 6 - Theme.width(dist), y, dimColor());
        }
    }
}
