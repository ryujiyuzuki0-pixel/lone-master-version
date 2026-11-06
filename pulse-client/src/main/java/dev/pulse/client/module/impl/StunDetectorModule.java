package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.StatCard;
import dev.pulse.client.ui.StatCard.Row;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells you whether the stun you just tried worked: SUCCESS, FAILED or UNKNOWN.
 *
 * <p>"Stun" means different things on different servers, so there are two detection types:
 * <ul>
 *   <li><b>Shield disable (axe)</b>: you hit a player who was blocking with a shield. <i>Confirmed success</i> is
 *       the game's own "shield disabled" event for that player (entity event 30) arriving within the window.
 *       <i>Failed</i> is the target still holding the shield for the whole window with no such event.
 *       If the shield simply went down with no event, the result is UNKNOWN (or an estimated "~" success when
 *       "Accept estimated results" is on), because the player may have lowered it themselves.</li>
 *   <li><b>Movement (knockback)</b>: for servers where a stun means the target is frozen after your hit. Needs a
 *       confirmed hit (target hurt), then measures how far the target really travelled after the knockback
 *       settles. Barely moved = success, clearly moving = failed, in between or out of sight = UNKNOWN.</li>
 * </ul>
 * Everything is measured from what the client can see (entity state and positions); nothing is sent or faked.
 * Sensitivity scales the thresholds. Hits that are not a stun attempt (target not blocking in Shield mode, wrong
 * weapon, not a player) are ignored rather than counted as failures.
 */
public final class StunDetectorModule extends Module {
    private enum Result { SUCCESS, FAILED, UNKNOWN }

    private static final class Pending {
        final long hitTick;
        final long deadline;
        final Player target;
        final double startX, startZ;
        final boolean shieldMode;
        boolean eventSeen;      // shield mode: game confirmed the shield was disabled
        boolean heldAllWindow = true; // shield mode: target kept blocking every tick
        boolean hitConfirmed;   // movement mode: target was seen getting hurt
        double settleX, settleZ;
        boolean settled;
        int lowered;            // shield mode: ticks the target was not blocking

        Pending(long hitTick, long deadline, Player target, boolean shieldMode) {
            this.hitTick = hitTick;
            this.deadline = deadline;
            this.target = target;
            this.startX = target.getX();
            this.startZ = target.getZ();
            this.shieldMode = shieldMode;
        }
    }

    // ---- detection
    private final ChoiceSetting type = add(new ChoiceSetting("Stun type", 0, "Shield disable (axe)", "Movement (knockback)"), "Detection");
    private final ChoiceSetting sensitivity = add(new ChoiceSetting("Sensitivity", 1, "Strict", "Normal", "Lenient"), "Detection");
    private final SliderSetting window = add(new SliderSetting("Detection window", 3, 20, 1, 8, " ticks"), "Detection");
    private final BoolSetting axeOnly = add(new BoolSetting("Axe in hand required", true), "Detection",
            () -> type.index == 0);
    private final SliderSetting blockGrace = add(new SliderSetting("Ping grace (shield seen up to)", 0, 10, 1, 3, " ticks ago"), "Detection",
            () -> type.index == 0);
    private final SliderSetting settleTicks = add(new SliderSetting("Knockback settle time", 1, 10, 1, 4, " ticks"), "Detection",
            () -> type.index == 1);
    private final BoolSetting estimated = add(new BoolSetting("Accept estimated results", true), "Detection");
    private final SliderSetting maxRange = add(new SliderSetting("Ignore targets farther than", 3, 12, 1, 7, " blocks"), "Detection");

    // ---- display
    private final BoolSetting showCounts = add(new BoolSetting("Show failed / unknown counts", true), "Display");
    private final BoolSetting showLast = add(new BoolSetting("Show last result", true), "Display");
    private final BoolSetting showStreak = add(new BoolSetting("Show streak", true), "Display");
    private final BoolSetting showConfidence = add(new BoolSetting("Show confirmed / estimated", true), "Display");

    // ---- feedback
    private final BoolSetting flash = add(new BoolSetting("Flash HUD on result", true), "Feedback");
    private final BoolSetting popup = add(new BoolSetting("Result text near crosshair", true), "Feedback");
    private final SliderSetting feedbackTime = add(new SliderSetting("Feedback time", 0.3, 2.0, 0.1, 0.9, "s"), "Feedback");
    private final ColorSetting goodColor = add(new ColorSetting("Success color", Theme.GOOD, false, false), "Feedback");
    private final ColorSetting badColor = add(new ColorSetting("Fail color", Theme.BAD, false, false), "Feedback");
    private final ColorSetting unknownColor = add(new ColorSetting("Unknown color", Theme.WARN, false, false), "Feedback");

    // ---- statistics
    private final BoolSetting resetOnWorld = add(new BoolSetting("Reset stats on world change", false), "Statistics");
    private final KeybindSetting resetKey = add(new KeybindSetting("Reset stats key", -1), "Statistics");
    private final ActionSetting resetAction = add(new ActionSetting("Session statistics", "Reset", this::clearStats), "Statistics");

    // ---- tracking state
    private long tickNo;
    private Pending pending;
    private Object lastLevel;
    private boolean keyWasDown;
    /** Last tick each nearby player was seen blocking (covers ping lag between their shield going up and us seeing it). */
    private final Map<UUID, Long> blockSeen = new HashMap<>();

    // ---- session statistics
    private int success, failed, unknown, confirmedOk, estimatedOk, streak, bestStreak;
    private String lastText = "-";
    private Result lastResult;
    private boolean lastEstimated;
    private long resultTime;
    private StatCard live;

    public StunDetectorModule() {
        super("stundetector", "Stun Detector",
                "Tells you if your stun worked: SUCCESS, FAILED or UNKNOWN. Counts stuns and accuracy.",
                Category.GAMEPLAY, true, 0.008f, 0.52f, true);
    }

    // ------------------------------------------------------------------ hooks

    /** Called when the local player attacks an entity (ModuleManager.onAttack). */
    public void onAttack(Player me, Entity target) {
        if (!(target instanceof Player tp)) return; // only players can be stunned
        if (tp == me || me.distanceToSqr(tp) > maxRange.value * maxRange.value) return;
        boolean shield = type.index == 0;
        if (shield) {
            if (axeOnly.value && !me.getMainHandItem().is(ItemTags.AXES)) return;
            boolean up = tp.isBlocking();
            if (!up) {
                Long seen = blockSeen.get(tp.getUUID());
                up = seen != null && tickNo - seen <= blockGrace.intValue();
            }
            if (!up) return; // not blocking: nothing to stun, not an attempt
        }
        if (pending != null) conclude(pending, Result.UNKNOWN, "Replaced by a new hit", false);
        pending = new Pending(tickNo, tickNo + window.intValue(), tp, shield);
    }

    /** Called by LivingEntityMixin for entity event 30 (shield disabled) on any client-side living entity. */
    public static void onShieldDisabled(LivingEntity e) {
        if (ModuleManager.byId("stundetector") instanceof StunDetectorModule m && m.enabled
                && m.pending != null && m.pending.target == e) {
            m.pending.eventSeen = true;
        }
    }

    // ------------------------------------------------------------------ tracking

    @Override
    public void onDisable() {
        pending = null;
        blockSeen.clear();
    }

    @Override
    public void tick(Minecraft mc) {
        Player me = mc.player;
        if (me == null || mc.level == null) {
            lastLevel = null;
            pending = null;
            blockSeen.clear();
            return;
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            pending = null;
            blockSeen.clear();
            if (resetOnWorld.value) clearStats();
        }
        long n = tickNo;

        // Remember who has been blocking recently (only nearby players; the list is tiny).
        if (type.index == 0) {
            double r = maxRange.value + 2;
            for (Player o : mc.level.players()) {
                if (o != me && o.isBlocking() && o.distanceToSqr(me) <= r * r) blockSeen.put(o.getUUID(), n);
            }
            Iterator<Map.Entry<UUID, Long>> it = blockSeen.entrySet().iterator();
            while (it.hasNext()) if (n - it.next().getValue() > 40) it.remove();
        }

        if (pending != null) evaluate(pending, n);

        tickNo++;

        boolean down = mc.screen == null && resetKey.isDown(mc);
        if (down && !keyWasDown) clearStats();
        keyWasDown = down;

        live = buildCard();
    }

    private void evaluate(Pending q, long n) {
        Player t = q.target;
        long age = n - q.hitTick;
        if (t.isRemoved() || !t.isAlive()) {
            // Died or left: a kill is not a stun result we can judge.
            conclude(q, Result.UNKNOWN, "Target gone", false);
            return;
        }
        if (q.shieldMode) {
            if (q.eventSeen) {
                conclude(q, Result.SUCCESS, "Shield disabled", false);
                return;
            }
            if (age >= 1) {
                if (t.isBlocking()) { /* still up */ } else { q.heldAllWindow = false; q.lowered++; }
            }
            if (n >= q.deadline) {
                if (q.heldAllWindow) {
                    conclude(q, Result.FAILED, "Shield held", false);
                } else if (estimated.value && q.lowered > 0) {
                    conclude(q, Result.SUCCESS, "Shield lowered", true);
                } else {
                    conclude(q, Result.UNKNOWN, "No shield event", false);
                }
            }
            return;
        }

        // Movement mode.
        if (!q.hitConfirmed && t.hurtTime > 0) q.hitConfirmed = true;
        int settle = settleTicks.intValue();
        if (!q.settled && age >= settle) {
            q.settled = true;
            q.settleX = t.getX();
            q.settleZ = t.getZ();
        }
        if (n < q.deadline) return;
        if (!q.hitConfirmed) {
            conclude(q, Result.UNKNOWN, "Hit not confirmed", false);
            return;
        }
        long span = Math.max(1, q.deadline - q.hitTick - settle);
        double dx = t.getX() - q.settleX, dz = t.getZ() - q.settleZ;
        double perTick = Math.sqrt(dx * dx + dz * dz) / span;
        double stillMax, movingMin;
        switch (sensitivity.index) {
            case 0 -> { stillMax = 0.020; movingMin = 0.090; }  // strict: must be really frozen
            case 2 -> { stillMax = 0.060; movingMin = 0.160; }  // lenient
            default -> { stillMax = 0.035; movingMin = 0.120; }
        }
        if (perTick <= stillMax) conclude(q, Result.SUCCESS, "Target frozen", true);
        else if (perTick >= movingMin) conclude(q, Result.FAILED, "Target moving", false);
        else conclude(q, Result.UNKNOWN, "Movement unclear", false);
    }

    /** estimatedResult marks the result as inferred (shown with "~") instead of confirmed by the game. */
    private void conclude(Pending q, Result r, String why, boolean estimatedResult) {
        if (pending == q) pending = null;
        long now = System.currentTimeMillis();
        switch (r) {
            case SUCCESS -> {
                success++;
                if (estimatedResult) estimatedOk++; else confirmedOk++;
                streak++;
                bestStreak = Math.max(bestStreak, streak);
            }
            case FAILED -> {
                failed++;
                streak = 0;
            }
            case UNKNOWN -> unknown++;
        }
        lastResult = r;
        lastText = r.name() + " - " + why;
        lastEstimated = estimatedResult && r == Result.SUCCESS;
        resultTime = now;
    }

    public void clearStats() {
        success = failed = unknown = confirmedOk = estimatedOk = streak = bestStreak = 0;
        lastText = "-";
        lastResult = null;
        lastEstimated = false;
        resultTime = 0;
        pending = null;
    }

    // ------------------------------------------------------------------ public stats (for other modules / Lone AI)

    public int successes() { return success; }
    public int failures() { return failed; }
    public int unknowns() { return unknown; }

    /** Accuracy in percent over decided stuns (success + failed); -1 when nothing was decided yet. */
    public int accuracy() {
        int d = success + failed;
        return d <= 0 ? -1 : Math.round(100f * success / d);
    }

    // ------------------------------------------------------------------ HUD

    private int colorOf(Result r) {
        return r == Result.SUCCESS ? goodColor.color() : r == Result.FAILED ? badColor.color() : unknownColor.color();
    }

    private StatCard buildCard() {
        return card(success, failed, unknown, confirmedOk, estimatedOk, streak, bestStreak, lastText, lastResult, lastEstimated);
    }

    private StatCard card(int ok, int bad, int unk, int confirmed, int est, int streak, int best,
                          String last, Result lastRes, boolean lastEst) {
        List<Row> rows = new ArrayList<>();
        int decided = ok + bad;
        int pct = decided <= 0 ? -1 : Math.round(100f * ok / decided);
        String pctText = pct < 0 ? "-" : (est > 0 ? "~" : "") + pct + "%";
        int pctColor = pct < 0 ? dimColor() : pct >= 70 ? Theme.GOOD : pct >= 40 ? Theme.WARN : Theme.BAD;
        rows.add(StatCard.row("STUN ACCURACY", dimColor(), pctText, pctColor));
        rows.add(StatCard.row("Stuns", dimColor(), ok + " / " + decided + " decided", textColor()));
        if (showCounts.value) {
            rows.add(StatCard.row("Failed", dimColor(), String.valueOf(bad), bad > 0 ? badColor.color() : textColor()));
            rows.add(StatCard.row("Unknown", dimColor(), String.valueOf(unk), unk > 0 ? unknownColor.color() : textColor()));
        }
        if (showStreak.value) rows.add(StatCard.row("Streak", dimColor(), streak + " (best " + best + ")", textColor()));
        if (showLast.value) {
            rows.add(StatCard.row("Last", dimColor(), lastRes == null ? "-" : (lastEst ? "~" : "") + last,
                    lastRes == null ? dimColor() : colorOf(lastRes)));
        }
        if (showConfidence.value) rows.add(StatCard.row("Confirmed / est.", dimColor(), confirmed + " / " + est, textColor()));
        return new StatCard(rows, 140);
    }

    private StatCard sample() {
        return card(5, 2, 1, 4, 1, 2, 3, "SUCCESS - Shield disabled", Result.SUCCESS, false);
    }

    private StatCard active() {
        return HudRenderer.editing() ? sample() : live;
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

    private float feedbackProgress() {
        long ms = (long) (feedbackTime.value * 1000.0);
        if (resultTime == 0 || ms <= 0) return 1f;
        return Math.min(1f, (System.currentTimeMillis() - resultTime) / (float) ms);
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        StatCard c = active();
        if (c == null) return;
        if (flash.value && !HudRenderer.editing() && lastResult != null) {
            float t = feedbackProgress();
            if (t < 1f) {
                int r = backgroundOn() ? (int) radius.value : 0;
                Theme.roundRect(g, 0, 0, w, h, r, Theme.alpha(colorOf(lastResult) | 0xFF000000, 0.35f * (1f - t)));
            }
        }
        c.draw(this, g);
    }

    @Override
    public void drawOverlay(GuiGraphics g, Minecraft mc) {
        if (!popup.value || resultTime == 0 || lastResult == null) return;
        float t = feedbackProgress();
        if (t >= 1f) return;
        String text = (lastEstimated ? "~" : "") + lastResult.name();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        Theme.centered(g, text, cx, cy + 14 - Math.round(6 * Theme.ease(t)),
                Theme.alpha(colorOf(lastResult) | 0xFF000000, 1f - t), true);
    }
}
