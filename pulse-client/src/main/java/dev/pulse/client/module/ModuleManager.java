package dev.pulse.client.module;

import dev.pulse.client.module.impl.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();
    private static ComboModule combo;
    private static ReachModule reach;
    private static SpotifyModule spotify;
    private static TargetHudModule target;
    private static CrosshairModule crosshair;
    private static WaypointsModule waypoints;
    private static CapeModule capeModule;
    private static ThemeModule themeModule;
    private static ClientModule client;
    private static StunDetectorModule stun;
    private static CustomBrightnessModule brightness;

    private ModuleManager() {}

    public static void init() {
        MODULES.clear();
        FpsModule fps = new FpsModule();
        CpsModule cps = new CpsModule();
        KeystrokesModule keys = new KeystrokesModule();
        ArmorModule armor = new ArmorModule();
        PotionModule potions = new PotionModule();
        CoordsModule coords = new CoordsModule();
        ZoomModule zoom = new ZoomModule();
        for (Module m : new Module[]{fps, cps, keys, armor, potions, coords, zoom}) m.enabled = true;

        combo = new ComboModule();
        reach = new ReachModule();
        spotify = new SpotifyModule();

        target = new TargetHudModule();
        crosshair = new CrosshairModule();
        waypoints = new WaypointsModule();
        capeModule = new CapeModule();
        capeModule.enabled = true;
        ThemeModule theme = new ThemeModule();
        theme.enabled = true;
        themeModule = theme;
        client = new ClientModule();
        LowHealthModule lowHealth = new LowHealthModule();
        lowHealth.enabled = true;
        FpsBoostModule boost = new FpsBoostModule();
        boost.enabled = true;
        DeathInfoModule deathInfo = new DeathInfoModule();
        deathInfo.enabled = true; // history is only recorded while the module is on
        CustomF3Module customF3 = new CustomF3Module();
        DropPreventionModule dropPrevention = new DropPreventionModule();
        dropPrevention.enabled = true;
        CustomFogModule customFog = new CustomFogModule();
        HorseStatsModule horseStats = new HorseStatsModule();
        JumpResetModule jumpReset = new JumpResetModule();
        stun = new StunDetectorModule();
        brightness = new CustomBrightnessModule();
        ShulkerTooltipModule shulkerTooltip = new ShulkerTooltipModule();
        ItemPhysicsModule itemPhysics = new ItemPhysicsModule();
        UhcOverlayModule uhcOverlay = new UhcOverlayModule();

        Collections.addAll(MODULES, fps, new PingModule(), coords, new ClockModule(), keys, potions,
                cps, combo, reach, armor, new TotemModule(), target,
                zoom, new ToggleSprintModule(), boost,
                theme, capeModule, crosshair, lowHealth,
                spotify,
                new WatermarkModule(), new InventoryHudModule(), new CooldownModule(),
                new ItemCountersModule(), waypoints, deathInfo, customF3, dropPrevention, customFog, horseStats, jumpReset, stun, brightness, shulkerTooltip, itemPhysics, uhcOverlay, client);
        for (Module m : MODULES) {
            m.defaultEnabled = m.enabled;
            m.syncPosition();
        }
    }

    public static List<Module> all() {
        return MODULES;
    }

    /** Every module shown in the menu (hides the client-settings holder). */
    public static List<Module> listed() {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) if (!m.hidden) out.add(m);
        return out;
    }

    public static List<Module> byCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) if (!m.hidden && m.category == c) out.add(m);
        return out;
    }

    public static StunDetectorModule stun() {
        return stun;
    }

    public static CustomBrightnessModule brightness() {
        return brightness;
    }

    public static ClientModule client() {
        return client;
    }

    public static ThemeModule theme() {
        return themeModule;
    }

    public static Module byId(String id) {
        for (Module m : MODULES) if (m.id.equals(id)) return m;
        return null;
    }

    public static CrosshairModule crosshair() {
        return crosshair;
    }

    public static CapeModule cape() {
        return capeModule;
    }

    public static WaypointsModule waypoints() {
        return waypoints;
    }

    public static SpotifyModule spotify() {
        return spotify;
    }

    public static void tick(Minecraft mc) {
        for (Module m : MODULES) if (m.enabled) m.tick(mc);
        if (brightness != null) brightness.tickAlways(mc); // also while off: fade-out and the quick toggle key
    }

    public static void onAttack(Player p, Entity target) {
        if (combo != null && combo.enabled) combo.hit();
        if (reach != null && reach.enabled) reach.record(p, target);
        if (stun != null && stun.enabled) stun.onAttack(p, target);
        if (ModuleManager.target != null && ModuleManager.target.enabled
                && target instanceof net.minecraft.world.entity.LivingEntity le) ModuleManager.target.set(le);
    }

    public static void shutdown() {
        for (Module m : MODULES) if (m.enabled) m.onDisable();
    }

    public static void resetLayout() {
        for (Module m : MODULES) m.resetPosition();
    }
}
