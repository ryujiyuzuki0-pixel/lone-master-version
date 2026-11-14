package dev.pulse.client.module.impl;

import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The family of one-line HUD readouts. Each is an {@link InfoLineModule}; settings are attached where they matter. */
public final class InfoModules {
    private static final int GOOD = 0xFF4ADE80, WARN = 0xFFFBBF24, BAD = 0xFFF87171;
    private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final String[] MOON = {"Full moon", "Waning gibbous", "Last quarter", "Waning crescent", "New moon",
            "Waxing crescent", "First quarter", "Waxing gibbous"};

    private static long sessionStart;

    private InfoModules() {}

    /** Default spots: columns of 14 down the right edge, so enabling several never stacks them off screen. */
    private static float slotX(int i) {
        return 0.992f - (i / 14) * 0.17f;
    }

    private static float slotY(int i) {
        return 0.30f + (i % 14) * 0.04f;
    }

    public static List<Module> create() {
        List<Module> out = new ArrayList<>();
        int slot = 0;

        ChoiceSetting[] unit = new ChoiceSetting[1];
        InfoLineModule speed = new InfoLineModule("speed", "Speed", "How fast you are moving.", slotX(slot), slotY(slot++), "SPEED", true, mc -> {
            double bps = Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo) * 20.0;
            return unit[0].index == 0 ? String.format(Locale.ROOT, "%.1f b/s", bps) : String.format(Locale.ROOT, "%.1f km/h", bps * 3.6);
        }, null);
        unit[0] = speed.option(new ChoiceSetting("Unit", 0, "Blocks/s", "km/h"));
        out.add(speed);

        BoolSetting[] deg = new BoolSetting[1];
        InfoLineModule dir = new InfoLineModule("direction", "Direction", "Which way you are facing.", slotX(slot), slotY(slot++), "FACING", true, mc -> {
            float yaw = Mth.wrapDegrees(mc.player.getYRot());
            String name = COMPASS[Math.floorMod(Math.round(yaw / 45f), 8)];
            return deg[0].value ? name + " " + Math.round(yaw < 0 ? yaw + 360 : yaw) + "\u00B0" : name;
        }, null);
        deg[0] = dir.option(new BoolSetting("Show degrees", true));
        out.add(dir);

        out.add(new InfoLineModule("day", "Day Counter", "How many in-game days this world has seen.", slotX(slot), slotY(slot++), "DAY", true,
                mc -> Long.toString(mc.level.getDayTime() / 24000L + 1), null));

        out.add(new InfoLineModule("worldtime", "World Time", "The in-game clock (06:00 is sunrise).", slotX(slot), slotY(slot++), "WORLD", true, mc -> {
            long t = mc.level.getDayTime() % 24000L;
            return String.format(Locale.ROOT, "%02d:%02d", (t / 1000 + 6) % 24, (t % 1000) * 60 / 1000);
        }, null));

        out.add(new InfoLineModule("moon", "Moon Phase", "The current moon phase.", slotX(slot), slotY(slot++), "MOON", true,
                mc -> MOON[(int) (mc.level.getDayTime() / 24000L % 8L)], null)); // same formula the game uses

        out.add(new InfoLineModule("weather", "Weather", "Clear, rain or thunder.", slotX(slot), slotY(slot++), "WEATHER", true,
                mc -> mc.level.isThundering() ? "Thunder" : mc.level.isRaining() ? "Rain" : "Clear", null));

        BoolSetting[] pct = new BoolSetting[1];
        InfoLineModule mem = new InfoLineModule("memory", "Memory", "Java memory in use.", slotX(slot), slotY(slot++), "MEM", false, mc -> {
            Runtime r = Runtime.getRuntime();
            long used = (r.totalMemory() - r.freeMemory()) >> 20, max = r.maxMemory() >> 20;
            return pct[0].value ? Math.round(used * 100f / max) + "%" : used + "/" + max + " MB";
        }, mc -> {
            Runtime r = Runtime.getRuntime();
            float f = (r.totalMemory() - r.freeMemory()) / (float) r.maxMemory();
            return f > 0.9f ? BAD : f > 0.75f ? WARN : 0;
        });
        pct[0] = mem.option(new BoolSetting("Show percent", false));
        out.add(mem);

        BoolSetting[] hideIp = new BoolSetting[1];
        InfoLineModule server = new InfoLineModule("serveraddress", "Server Address", "The server you are connected to.", slotX(slot), slotY(slot++), "SERVER", true, mc -> {
            if (mc.getCurrentServer() == null) return "Singleplayer";
            return hideIp[0].value || StreamerModeModule.active() ? "Hidden" : mc.getCurrentServer().ip;
        }, null);
        hideIp[0] = server.option(new BoolSetting("Hide address", false));
        out.add(server);

        out.add(new InfoLineModule("session", "Session Time", "How long you have been in this world.", slotX(slot), slotY(slot++), "PLAYED", false, mc -> {
            if (mc.player == null) {
                sessionStart = 0;
                return "00:00:00";
            }
            if (sessionStart == 0) sessionStart = System.currentTimeMillis();
            long s = (System.currentTimeMillis() - sessionStart) / 1000;
            return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }, null));

        out.add(new InfoLineModule("helddurability", "Held Durability", "Durability left on the item in your hand.", slotX(slot), slotY(slot++), "TOOL", true, mc -> {
            ItemStack s = mc.player.getMainHandItem();
            return s.isDamageableItem() ? (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage() : "--";
        }, mc -> {
            ItemStack s = mc.player.getMainHandItem();
            if (!s.isDamageableItem()) return 0;
            float f = 1f - s.getDamageValue() / (float) s.getMaxDamage();
            return f < 0.1f ? BAD : f < 0.25f ? WARN : 0;
        }));

        out.add(new InfoLineModule("hunger", "Hunger", "Food and saturation.", slotX(slot), slotY(slot++), "FOOD", true,
                mc -> mc.player.getFoodData().getFoodLevel() + " / " + String.format(Locale.ROOT, "%.1f", mc.player.getFoodData().getSaturationLevel()),
                mc -> mc.player.getFoodData().getFoodLevel() <= 6 ? WARN : 0));

        out.add(new InfoLineModule("health", "Health", "Hearts plus absorption.", slotX(slot), slotY(slot++), "HP", true, mc -> {
            float hp = mc.player.getHealth(), ab = mc.player.getAbsorptionAmount();
            return String.format(Locale.ROOT, ab > 0 ? "%.1f +%.1f" : "%.1f", hp, ab).replace(",", ".");
        }, mc -> mc.player.getHealth() <= 6f ? BAD : 0));

        out.add(new InfoLineModule("xp", "Experience", "Your level and progress to the next one.", slotX(slot), slotY(slot++), "XP", true,
                mc -> "Lv " + mc.player.experienceLevel + " (" + Math.round(mc.player.experienceProgress * 100f) + "%)", null));

        out.add(new InfoLineModule("armorpoints", "Armor Points", "Total armor protection.", slotX(slot), slotY(slot++), "ARMOR", true,
                mc -> Integer.toString(mc.player.getArmorValue()), null));

        out.add(new InfoLineModule("light", "Light Level", "Block light where you stand. 0 means hostile mobs can spawn.", slotX(slot), slotY(slot++), "LIGHT", true, mc -> {
            BlockPos p = mc.player.blockPosition();
            return mc.level.getBrightness(LightLayer.BLOCK, p) + " / sky " + mc.level.getBrightness(LightLayer.SKY, p);
        }, mc -> mc.level.getBrightness(LightLayer.BLOCK, mc.player.blockPosition()) == 0 ? WARN : 0));

        out.add(new InfoLineModule("chunk", "Chunk", "The chunk you are in.", slotX(slot), slotY(slot++), "CHUNK", true, mc -> {
            BlockPos p = mc.player.blockPosition();
            return (p.getX() >> 4) + ", " + (p.getZ() >> 4);
        }, null));

        out.add(new InfoLineModule("rotation", "Rotation", "Your yaw and pitch.", slotX(slot), slotY(slot++), "ROT", true,
                mc -> String.format(Locale.ROOT, "%.1f / %.1f", Mth.wrapDegrees(mc.player.getYRot()), mc.player.getXRot()), null));

        BoolSetting[] dist = new BoolSetting[1];
        InfoLineModule look = new InfoLineModule("lookingat", "Looking At", "The block or entity under your crosshair.", slotX(slot), slotY(slot++), "TARGET", true, mc -> {
            HitResult h = mc.hitResult;
            if (h == null || h.getType() == HitResult.Type.MISS) return "--";
            String name;
            if (h instanceof BlockHitResult b) name = mc.level.getBlockState(b.getBlockPos()).getBlock().getName().getString();
            else if (h instanceof EntityHitResult e) name = e.getEntity().getName().getString();
            else return "--";
            return dist[0].value ? name + " " + String.format(Locale.ROOT, "%.1fm", mc.player.getEyePosition().distanceTo(h.getLocation())) : name;
        }, null);
        dist[0] = look.option(new BoolSetting("Show distance", true));
        out.add(look);

        out.add(new InfoLineModule("falldistance", "Fall Distance", "How far you have fallen.", slotX(slot), slotY(slot++), "FALL", true,
                mc -> String.format(Locale.ROOT, "%.1f", (double) mc.player.fallDistance),
                mc -> (double) mc.player.fallDistance >= 3.0 ? WARN : 0));

        out.add(new InfoLineModule("dimension", "Dimension", "Overworld, Nether or End.", slotX(slot), slotY(slot++), "DIM", true, mc -> {
            String d = mc.level.dimension().toString();
            return d.contains("the_nether") ? "Nether" : d.contains("the_end") ? "End" : d.contains("overworld") ? "Overworld" : "Custom";
        }, null));

        out.add(new InfoLineModule("biome", "Biome", "The biome you are standing in.", slotX(slot), slotY(slot++), "BIOME", true, mc ->
                mc.level.getBiome(mc.player.blockPosition()).unwrapKey().map(k -> {
                    String s = k.toString();
                    int i = s.lastIndexOf("/ ");
                    String n = (i >= 0 ? s.substring(i + 2) : s).replace("]", "").replace("minecraft:", "").replace('_', ' ');
                    return n.isEmpty() ? "--" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
                }).orElse("--"), null));

        out.add(new InfoLineModule("gamemode", "Game Mode", "Your current game mode.", slotX(slot), slotY(slot++), "MODE", true, mc -> {
            if (mc.gameMode == null) return "--";
            String n = mc.gameMode.getPlayerMode().getName();
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }, null));

        out.add(new InfoLineModule("netherconvert", "Nether Coordinates", "Where you are in the other dimension.", slotX(slot), slotY(slot++), "OTHER", true, mc -> {
            if (StreamerModeModule.active()) return "Hidden";
            boolean nether = mc.level.dimension().toString().contains("the_nether");
            BlockPos p = mc.player.blockPosition();
            return nether ? p.getX() * 8 + ", " + p.getZ() * 8 : Math.floorDiv(p.getX(), 8) + ", " + Math.floorDiv(p.getZ(), 8);
        }, null));

        SliderSetting[] radius = new SliderSetting[1];
        InfoLineModule near = new InfoLineModule("nearbyplayers", "Nearby Players", "How many other players are close.", slotX(slot), slotY(slot++), "NEARBY", true, mc -> {
            int n = 0;
            for (Player p : mc.level.players()) if (p != mc.player && p.distanceTo(mc.player) <= radius[0].value) n++;
            return Integer.toString(n);
        }, mc -> {
            for (Player p : mc.level.players()) if (p != mc.player && p.distanceTo(mc.player) <= radius[0].value) return WARN;
            return 0;
        });
        radius[0] = near.option(new SliderSetting("Radius", 8, 128, 4, 32, "m"));
        out.add(near);

        out.add(new InfoLineModule("onlineplayers", "Player Count", "Players on the server.", slotX(slot), slotY(slot++), "ONLINE", true,
                mc -> mc.getConnection() == null ? "--" : Integer.toString(mc.getConnection().getOnlinePlayers().size()), null));

        out.add(new InfoLineModule("freeslots", "Free Slots", "Empty slots in your inventory.", slotX(slot), slotY(slot++), "SLOTS", true, mc -> {
            int free = 0;
            for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) free++;
            return free + "/36";
        }, mc -> {
            int free = 0;
            for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) free++;
            return free == 0 ? BAD : free <= 3 ? WARN : 0;
        }));

        out.add(new InfoLineModule("movement", "Movement", "Sprinting, sneaking, swimming or flying.", slotX(slot), slotY(slot++), "STATE", true, mc -> {
            Player p = mc.player;
            return p.isFallFlying() ? "Gliding" : p.getAbilities().flying ? "Flying" : p.isSwimming() ? "Swimming"
                    : p.isSprinting() ? "Sprinting" : p.isCrouching() ? "Sneaking" : "Walking";
        }, null));

        out.add(new InfoLineModule("airsupply", "Air Supply", "Breath left while underwater.", slotX(slot), slotY(slot++), "AIR", true, mc -> {
            int max = mc.player.getMaxAirSupply();
            return Math.max(0, Math.round(mc.player.getAirSupply() * 100f / max)) + "%";
        }, mc -> mc.player.getAirSupply() < mc.player.getMaxAirSupply() / 3 ? BAD : 0));

        out.add(new InfoLineModule("firetimer", "Fire Timer", "Seconds left on fire.", slotX(slot), slotY(slot++), "FIRE", true, mc -> {
            int t = mc.player.getRemainingFireTicks();
            return t > 0 ? String.format(Locale.ROOT, "%.1fs", t / 20.0) : "--";
        }, mc -> mc.player.getRemainingFireTicks() > 0 ? BAD : 0));

        out.add(new InfoLineModule("worldborder", "World Border", "Distance to the world border.", slotX(slot), slotY(slot++), "BORDER", true, mc -> {
            double d = mc.level.getWorldBorder().getDistanceToBorder(mc.player.getX(), mc.player.getZ());
            return d > 9999 ? "--" : Math.round(d) + "m";
        }, mc -> mc.level.getWorldBorder().getDistanceToBorder(mc.player.getX(), mc.player.getZ()) < 20 ? WARN : 0));

        out.add(new InfoLineModule("frametime", "Frame Time", "Milliseconds per frame.", slotX(slot), slotY(slot++), "FRAME", false, mc -> {
            int f = dev.pulse.client.util.FrameClock.fps();
            return f <= 0 ? "--" : String.format(Locale.ROOT, "%.1f ms", 1000f / f);
        }, null));

        return out;
    }
}
