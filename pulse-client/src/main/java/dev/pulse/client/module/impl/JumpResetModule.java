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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Session tracker for PvP jump resets, measured in client ticks around each hit you take.
 *
 * <p>A hit is a rise of your hurt timer. It only counts if you were on the ground the tick before (you cannot
 * jump-reset in the air) and, by default, if it came from another player. After the hit:
 * <ul>
 *   <li><b>Success</b>: you actually jumped within the success window (default: the hit tick or one tick later).</li>
 *   <li><b>Failed attempt</b>: you pressed jump in the attempt window but did not jump in time ("Late"), or the
 *       game did not register the jump (jump cooldown after landing).</li>
 *   <li><b>No attempt</b>: you hit the ground-hit without pressing jump. Counted as a hit taken, not as a fail.</li>
 * </ul>
 * Jumps that are not near a hit are never counted.
 *
 * <p>Confirmed vs estimated: a result is <b>confirmed</b> when the jump was observed through the game's own jump
 * routine (mixin) and the damage source named an attacker. If the mixin did not apply, the jump is inferred from
 * the jump key plus leaving the ground, and if the damage source is unknown the hit is accepted because another
 * player is nearby; those results are <b>estimated</b> and marked with "~".
 */
public final class JumpResetModule extends Module {
    private enum Outcome { SUCCESS, LATE, MISSED, NONE }

    private static final class Pending {
        final long hitTick;
        final long deadline;
        final boolean attackerKnown;
        boolean attempted;

        Pending(long hitTick, long deadline, boolean attackerKnown) {
            this.hitTick = hitTick;
            this.deadline = deadline;
            this.attackerKnown = attackerKnown;
        }
    }

    // ---- display
    private final ChoiceSetting rate = add(new ChoiceSetting("Success rate of", 0, "Attempts", "Hits taken"), "Display");
    private final BoolSetting showHits = add(new BoolSetting("Show hits taken", true), "Display");
    private final BoolSetting showStreak = add(new BoolSetting("Show streak", true), "Display");
    private final BoolSetting showLast = add(new BoolSetting("Show last result", true), "Display");
    private final BoolSetting showConfidence = add(new BoolSetting("Show confirmed / estimated", true), "Display");

    // ---- detection
    private final SliderSetting successWindow = add(new SliderSetting("Success window", 0, 3, 1, 1, " ticks"), "Detection");
    private final SliderSetting attemptWindow = add(new SliderSetting("Attempt window", 2, 10, 1, 4, " ticks"), "Detection");
    private final BoolSetting playersOnly = add(new BoolSetting("Only hits from players", true), "Detection");
    private final SliderSetting nearRange = add(new SliderSetting("Unknown source: player within", 3, 16, 1, 8, " blocks"), "Detection");

    // ---- feedback
    private final BoolSetting flash = add(new BoolSetting("Flash HUD on result", true), "Feedback");
    private final BoolSetting popup = add(new BoolSetting("Result text near crosshair", true), "Feedback");
    private final SliderSetting feedbackTime = add(new SliderSetting("Feedback time", 0.3, 2.0, 0.1, 0.8, "s"), "Feedback");
    private final ColorSetting goodColor = add(new ColorSetting("Success color", Theme.GOOD, false, false), "Feedback");
    private final ColorSetting badColor = add(new ColorSetting("Fail color", Theme.BAD, false, false), "Feedback");

    // ---- statistics
    private final BoolSetting resetOnWorld = add(new BoolSetting("Reset stats on world change", false), "Statistics");
    private final KeybindSetting resetKey = add(new KeybindSetting("Reset stats key", -1), "Statistics");
    private final ActionSetting resetAction = add(new ActionSetting("Session statistics", "Reset", this::clearStats), "Statistics");

    // ---- tracking state
    private long tickNo;
    private long hookJumpTick = Long.MIN_VALUE;
    private long lastJumpTick = Long.MIN_VALUE;
    private boolean lastJumpConfirmed;
    private boolean prevOnGround;
    private int prevHurt;
    private Object lastLevel;
    private Object lastPlayer;
    private boolean keyWasDown;
    private Pending pending;

    // ---- session statistics
    private int hits, attempts, successes, confirmedOk, estimatedOk, noAttempt, ignoredAirborne, streak, bestStreak;
    private String lastText = "-";
    private int lastColor;
    private boolean lastEstimated;
    private long lastTime;
    private long resultTime;
    private boolean resultGood;
    private StatCard live;

    public JumpResetModule() {
        super("jumpreset", "Jump Reset Counter",
                "Counts jump-reset attempts and successes each time you are hit on the ground. Session stats.",
                Category.GAMEPLAY, true, 0.008f, 0.34f, true);
    }

    // ------------------------------------------------------------------ jump hook

    /** Called by LivingEntityMixin whenever the local player's jump routine runs. */
    public static void onPlayerJump() {
        if (ModuleManager.byId("jumpreset") instanceof JumpResetModule m && m.enabled) m.hookJumpTick = m.tickNo;
    }

    // ------------------------------------------------------------------ tracking

    @Override
    public void onDisable() {
        pending = null;
    }

    @Override
    public void tick(Minecraft mc) {
        Player p = mc.player;
        if (p == null || mc.level == null) {
            lastLevel = null;
            lastPlayer = null;
            pending = null;
            return;
        }
        boolean fresh = false;
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            if (resetOnWorld.value) clearStats();
            fresh = true;
        }
        if (p != lastPlayer) {
            lastPlayer = p;
            fresh = true;
        }
        long n = tickNo;
        boolean onGround = p.onGround();
        boolean key = mc.options.keyJump.isDown();
        if (fresh) {
            // New world or respawn: do not compare against the previous player's state.
            pending = null;
            prevOnGround = onGround;
            prevHurt = p.hurtTime;
            hookJumpTick = Long.MIN_VALUE;
            lastJumpTick = Long.MIN_VALUE;
        }

        // Jump event: from the game's own jump routine when the hook works, otherwise inferred.
        if (hookJumpTick == n) {
            lastJumpTick = n;
            lastJumpConfirmed = true;
        } else if (prevOnGround && !onGround && key) {
            lastJumpTick = n;
            lastJumpConfirmed = false;
        }

        // A new hit is a rise of the hurt timer.
        int hurt = p.hurtTime;
        if (hurt > prevHurt) beginHit(mc, p, n, key);
        prevHurt = hurt;

        if (pending != null) evaluate(n, key);

        prevOnGround = onGround;
        tickNo++;

        boolean down = mc.screen == null && resetKey.isDown(mc);
        if (down && !keyWasDown) clearStats();
        keyWasDown = down;

        live = buildCard();
    }

    /** 0 = ignore, 1 = accepted but source unknown (estimated), 2 = attacker known. */
    private int classify(Minecraft mc, Player p) {
        DamageSource src = p.getLastDamageSource();
        if (src != null) {
            Entity attacker = src.getEntity();
            if (attacker instanceof Player) return 2;
            if (attacker instanceof LivingEntity) return playersOnly.value ? 0 : 2;
            if (src.getDirectEntity() == null) return 0; // fall, fire, void... nobody to jump-reset against
        }
        if (mc.level == null) return 0;
        double r = nearRange.value * nearRange.value;
        for (Player other : mc.level.players()) {
            if (other != p && other.distanceToSqr(p) <= r) return 1;
        }
        return 0;
    }

    private void beginHit(Minecraft mc, Player p, long n, boolean key) {
        if (pending != null) finish(pending, n);
        int cls = classify(mc, p);
        if (cls == 0) return;
        if (!prevOnGround) {
            ignoredAirborne++; // hit while airborne: a jump reset was not possible
            return;
        }
        hits++;
        pending = new Pending(n, n + Math.max(attemptWindow.intValue(), successWindow.intValue()), cls == 2);
        pending.attempted = key;
    }

    private void evaluate(long n, boolean key) {
        Pending q = pending;
        if (key) q.attempted = true;
        boolean jumped = lastJumpTick >= q.hitTick;
        if (jumped && lastJumpTick - q.hitTick <= successWindow.intValue()) {
            conclude(q, Outcome.SUCCESS, lastJumpTick - q.hitTick);
        } else if (n >= q.deadline) {
            finish(q, n);
        }
    }

    /** Window over without a success: decide between late, not registered, and no attempt. */
    private void finish(Pending q, long n) {
        boolean jumped = lastJumpTick >= q.hitTick && lastJumpTick <= q.deadline;
        if (jumped) conclude(q, Outcome.LATE, lastJumpTick - q.hitTick);
        else if (q.attempted) conclude(q, Outcome.MISSED, 0);
        else conclude(q, Outcome.NONE, 0);
    }

    private void conclude(Pending q, Outcome o, long ticks) {
        pending = null;
        long now = System.currentTimeMillis();
        boolean estimated = !(q.attackerKnown && lastJumpConfirmed);
        switch (o) {
            case SUCCESS -> {
                attempts++;
                successes++;
                if (estimated) estimatedOk++; else confirmedOk++;
                streak++;
                bestStreak = Math.max(bestStreak, streak);
                lastText = "Reset +" + ticks + "t";
                resultGood = true;
            }
            case LATE -> {
                attempts++;
                streak = 0;
                lastText = "Late +" + ticks + "t";
                resultGood = false;
            }
            case MISSED -> {
                attempts++;
                streak = 0;
                lastText = "Not registered";
                resultGood = false;
            }
            case NONE -> {
                noAttempt++;
                streak = 0;
                lastText = "No jump";
            }
        }
        lastEstimated = estimated && o != Outcome.NONE;
        lastTime = now;
        if (o != Outcome.NONE) resultTime = now;
        lastColor = o == Outcome.SUCCESS ? goodColor.color() : o == Outcome.NONE ? dimColor() : badColor.color();
    }

    public void clearStats() {
        hits = attempts = successes = confirmedOk = estimatedOk = noAttempt = ignoredAirborne = streak = bestStreak = 0;
        lastText = "-";
        lastEstimated = false;
        lastTime = 0;
        resultTime = 0;
        pending = null;
    }

    // ------------------------------------------------------------------ HUD

    private StatCard buildCard() {
        return card(hits, attempts, successes, confirmedOk, estimatedOk, streak, bestStreak, lastText, lastColor, lastEstimated, lastTime > 0);
    }

    private StatCard card(int hits, int attempts, int ok, int confirmed, int estimated, int streak, int best,
                          String last, int lastCol, boolean lastEst, boolean hasLast) {
        List<Row> rows = new ArrayList<>();
        int denom = rate.index == 0 ? attempts : hits;
        int pct = denom <= 0 ? -1 : Math.round(100f * ok / denom);
        String pctText = pct < 0 ? "-" : (estimated > 0 ? "~" : "") + pct + "%";
        int pctColor = pct < 0 ? dimColor() : pct >= 70 ? Theme.GOOD : pct >= 40 ? Theme.WARN : Theme.BAD;
        rows.add(StatCard.row("JUMP RESETS", dimColor(), pctText, pctColor));
        rows.add(StatCard.row("Resets", dimColor(), ok + " / " + attempts + " attempts", textColor()));
        rows.add(StatCard.row("Failed", dimColor(), String.valueOf(attempts - ok), attempts - ok > 0 ? badColor.color() : textColor()));
        if (showHits.value) rows.add(StatCard.row("Hits taken", dimColor(), String.valueOf(hits), textColor()));
        if (showStreak.value) rows.add(StatCard.row("Streak", dimColor(), streak + " (best " + best + ")", textColor()));
        if (showLast.value) rows.add(StatCard.row("Last", dimColor(), hasLast ? (lastEst ? "~" : "") + last : "-", hasLast ? lastCol : dimColor()));
        if (showConfidence.value) {
            rows.add(StatCard.row("Confirmed / est.", dimColor(), confirmed + " / " + estimated, textColor()));
        }
        return new StatCard(rows, 130);
    }

    private StatCard sample() {
        return card(8, 6, 4, 3, 1, 2, 3, "Reset +0t", goodColor.color(), false, true);
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
        if (flash.value && !HudRenderer.editing()) {
            float t = feedbackProgress();
            if (t < 1f) {
                int col = resultGood ? goodColor.color() : badColor.color();
                int r = backgroundOn() ? (int) radius.value : 0;
                Theme.roundRect(g, 0, 0, w, h, r, Theme.alpha(col | 0xFF000000, 0.35f * (1f - t)));
            }
        }
        c.draw(this, g);
    }

    @Override
    public void drawOverlay(GuiGraphics g, Minecraft mc) {
        if (!popup.value || resultTime == 0) return;
        float t = feedbackProgress();
        if (t >= 1f) return;
        int col = resultGood ? goodColor.color() : badColor.color();
        String text = (lastEstimated ? "~" : "") + lastText;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        Theme.centered(g, text, cx, cy + 14 - Math.round(6 * Theme.ease(t)), Theme.alpha(col | 0xFF000000, 1f - t), true);
    }
}
