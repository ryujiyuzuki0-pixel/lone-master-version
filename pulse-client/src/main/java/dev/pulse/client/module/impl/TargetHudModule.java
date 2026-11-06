package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/** Shows the health of whoever you last hit. */
public final class TargetHudModule extends Module {
    private final SliderSetting showFor = add(new SliderSetting("Display time", 1, 15, 1, 4, "s"));
    private final BoolSetting showName = add(new BoolSetting("Show name", true));
    private final BoolSetting showHealth = add(new BoolSetting("Show health number", true));
    private final BoolSetting showDistance = add(new BoolSetting("Show distance", false));
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 110, 240, 2, 150, "px"), "Layout");
    private final SliderSetting barHeight = add(new SliderSetting("Bar height", 3, 12, 1, 6, "px"), "Layout");
    private final ChoiceSetting barStyle = add(new ChoiceSetting("Bar style", 0, "Health gradient", "Accent", "Custom"), "Colors");
    private final ColorSetting barColor = add(new ColorSetting("Bar color", 0xFF4ADE80, false, true), "Colors", () -> this.barStyle.index == 2);
    private final ColorSetting trackColor = add(new ColorSetting("Bar track color", 0xFF2A2F4A, true, false), "Colors");
    private LivingEntity target;
    private long lastHit;

    public TargetHudModule() {
        super("target", "Target HUD", "Health bar of the last entity you hit.", Category.GAMEPLAY,
                true, 0.5f, 0.62f, true);
    }

    public void set(LivingEntity e) {
        target = e;
        lastHit = System.currentTimeMillis();
    }

    private boolean active() {
        return HudRenderer.editing()
                || (target != null && target.isAlive() && System.currentTimeMillis() - lastHit < showFor.value * 1000);
    }

    @Override public int width(Minecraft mc) { return active() ? boxWidth.intValue() : 0; }

    @Override public int height(Minecraft mc) { return active() ? 30 + barHeight.intValue() : 0; }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        boolean sample = target == null || !target.isAlive();
        String name = sample ? "Player" : target.getName().getString();
        float hp = sample ? 14f : target.getHealth();
        float max = sample ? 20f : Math.max(1f, target.getMaxHealth());
        float ratio = Math.max(0f, Math.min(1f, hp / max));

        if (showName.value) text(g, Theme.fit(name, w - 60), 8, 6, textColor());
        String num = String.format(Locale.ROOT, "%.1f", hp);
        if (showHealth.value) text(g, num, w - 8 - Theme.width(num), 6, dimColor());
        int bx = 8, bw = w - 16, by = 20, bh = barHeight.intValue();
        if (showDistance.value && mc.player != null && !sample) {
            String d = String.format(Locale.ROOT, "%.1fm", mc.player.distanceTo(target));
            text(g, d, 8, by + bh + 2, dimColor());
        }
        Theme.roundRect(g, bx, by, bw, bh, bh / 2, trackColor.color());
        int fill = Math.round(bw * ratio);
        if (fill > 0) {
            int c = switch (barStyle.index) {
                case 1 -> Theme.ACCENT;
                case 2 -> barColor.color();
                default -> Theme.lerp(Theme.BAD, Theme.GOOD, ratio);
            };
            Theme.roundRect(g, bx, by, Math.max(bh, fill), bh, bh / 2, c);
        }
    }
}
