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
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Named waypoints (managed with /pulse waypoint ...). The HUD lists the nearest ones in the
 * current dimension with an arrow showing which way to go. Saved to config/pulse-waypoints.json.
 */
public final class WaypointsModule extends Module {
    public static final class Waypoint {
        public String name;
        public double x, y, z;
        public String dim;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
    private static final int ROW = 13;

    private final List<Waypoint> points = new ArrayList<>();
    private final SliderSetting rows = add(new SliderSetting("Rows", 1, 8, 1, 3, ""));
    private final BoolSetting showArrow = add(new BoolSetting("Show arrow", true));
    private final BoolSetting showDistance = add(new BoolSetting("Show distance", true));
    private final ChoiceSetting sort = add(new ChoiceSetting("Sort", 0, "Nearest", "Name"));
    private final SliderSetting maxDistance = add(new SliderSetting("Max distance", 0, 5000, 50, 0, "m").zero("Unlimited"));
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 110, 240, 2, 150, "px"), "Layout");
    private final ColorSetting arrowColor = add(new ColorSetting("Arrow color", 0xFFFFFFFF, false, true).withMode(1), "Colors");
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
            if (!dim.equals(w.dim)) continue;
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
            if (showArrow.value) text(g, arrow, 6, y, arrowColor.color());
            int nameW = w - 26 - (showDistance.value ? 44 : 0);
            text(g, Theme.fit(wp.name, nameW), showArrow.value ? 20 : 6, y, textColor());
            if (showDistance.value) text(g, dist, w - 6 - Theme.width(dist), y, dimColor());
        }
    }
}
