package dev.pulse.client.module;

import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.Setting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public abstract class Module {
    public final String id, name, description;
    public final Category category;
    public final boolean hud;
    public volatile boolean enabled;
    public boolean defaultEnabled;
    public boolean favorite;
    /** Hidden modules only hold settings (like the client settings) and are not listed as mods. */
    public boolean hidden;
    /** Position as a fraction (0..1) of the free space on screen, so layouts survive any resolution. */
    public float fx, fy;
    public final float defaultFx, defaultFy;
    public final List<Setting> settings = new ArrayList<>();
    public float toggleAnim;

    // Standard settings every HUD element gets. null for modules that are not HUD elements.
    protected final SliderSetting scale;
    protected final BoolSetting background;
    protected final ColorSetting bgColor;
    protected final SliderSetting radius;
    protected final BoolSetting accentLine;
    protected final ColorSetting accentColor;
    protected final ColorSetting textColorSetting;
    protected final ColorSetting labelColorSetting;
    protected final BoolSetting shadow;
    private final SliderSetting posX, posY;

    protected Module(String id, String name, String description, Category category,
                     boolean hud, float fx, float fy, boolean backgroundDefault) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.hud = hud;
        this.fx = this.defaultFx = fx;
        this.fy = this.defaultFy = fy;
        if (hud) {
            SliderSetting sc = new SliderSetting("Scale", 0.5, 2.5, 0.05, 1.0, "x");
            BoolSetting bg = new BoolSetting("Background", backgroundDefault);
            ColorSetting bgc = new ColorSetting("Background color", Theme.HUD_BG, true, true);
            SliderSetting rad = new SliderSetting("Corner radius", 0, 10, 1, 4, "px");
            BoolSetting line = new BoolSetting("Accent line", true);
            ColorSetting acc = new ColorSetting("Accent color", 0xFFFFFFFF, true, true).withMode(1);
            ColorSetting txt = new ColorSetting("Text color", Theme.TEXT, false, true);
            ColorSetting lab = new ColorSetting("Label color", Theme.DIM, false, true);
            BoolSetting sh = new BoolSetting("Text shadow", true);
            scale = add(sc, "Appearance");
            background = add(bg, "Appearance");
            bgColor = add(bgc, "Appearance", () -> bg.value);
            radius = add(rad, "Appearance", () -> bg.value);
            accentLine = add(line, "Appearance", () -> bg.value);
            accentColor = add(acc, "Appearance", () -> bg.value && line.value);
            textColorSetting = add(txt, "Appearance");
            labelColorSetting = add(lab, "Appearance");
            shadow = add(sh, "Appearance");

            SliderSetting px = new SliderSetting("Position X", 0, 100, 0.5, fx * 100.0, "%");
            SliderSetting py = new SliderSetting("Position Y", 0, 100, 0.5, fy * 100.0, "%");
            px.persist = false;
            py.persist = false;
            px.onChange = () -> this.fx = (float) (px.value / 100.0);
            py.onChange = () -> this.fy = (float) (py.value / 100.0);
            posX = add(px, "Position");
            posY = add(py, "Position");
            add(new ActionSetting("Reset position", "Reset", () -> {
                resetPosition();
                syncPosition();
            }), "Position");
        } else {
            scale = null;
            background = null;
            bgColor = null;
            radius = null;
            accentLine = null;
            accentColor = null;
            textColorSetting = null;
            labelColorSetting = null;
            shadow = null;
            posX = null;
            posY = null;
        }
    }

    protected final <T extends Setting> T add(T s) {
        settings.add(s);
        return s;
    }

    protected final <T extends Setting> T add(T s, String group) {
        s.group = group;
        settings.add(s);
        return s;
    }

    protected final <T extends Setting> T add(T s, BooleanSupplier visible) {
        s.visible = visible;
        settings.add(s);
        return s;
    }

    protected final <T extends Setting> T add(T s, String group, BooleanSupplier visible) {
        s.group = group;
        s.visible = visible;
        settings.add(s);
        return s;
    }

    // ------------------------------------------------------------------ appearance helpers

    public float scaleValue() {
        return scale == null ? 1f : (float) scale.value;
    }

    public boolean backgroundOn() {
        return background != null && background.value;
    }

    /** Main text color (user configurable). */
    public int textColor() {
        return textColorSetting == null ? Theme.TEXT : textColorSetting.color();
    }

    /** Label / secondary text color (user configurable). */
    public int dimColor() {
        return labelColorSetting == null ? Theme.DIM : labelColorSetting.color();
    }

    public boolean shadowOn() {
        return shadow == null || shadow.value;
    }

    /** Draws text honoring the module's "Text shadow" setting. */
    public void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(Theme.font(), s, x, y, color, shadowOn());
    }

    /** Background card of a HUD element, drawn at (0,0) with the given size. */
    public void drawBackground(GuiGraphics g, int w, int h) {
        if (!backgroundOn()) return;
        int r = (int) radius.value;
        Theme.roundRect(g, 0, 0, w, h, r, bgColor.color());
        if (accentLine.value) {
            int c1 = accentColor.color();
            int c2 = accentColor.mode == 1 ? Theme.alpha(Theme.ACCENT2, accentColor.alpha / 255f) : c1;
            Theme.hGradient(g, r, 0, Math.max(0, w - 2 * r), 1, c1, c2);
        }
    }

    // ------------------------------------------------------------------ state

    public void setEnabled(boolean e) {
        if (e == enabled) return;
        enabled = e;
        if (e) onEnable(); else onDisable();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void resetPosition() {
        fx = defaultFx;
        fy = defaultFy;
    }

    /** Keeps the "Position X/Y" sliders in step with where the HUD editor put the element. */
    public void syncPosition() {
        if (posX == null) return;
        posX.set(fx * 100.0);
        posY.set(fy * 100.0);
    }

    /** Every setting and the position back to defaults (enabled state is left alone). */
    public void resetSettings() {
        for (Setting s : settings) s.reset();
        resetPosition();
        syncPosition();
    }

    /** Settings, position and enabled state back to factory defaults. */
    public void resetAll() {
        resetSettings();
        favorite = false;
        setEnabled(defaultEnabled);
    }

    public int visibleSettingCount() {
        int n = 0;
        for (Setting s : settings) if (!(s instanceof ActionSetting)) n++;
        return n;
    }

    public void onEnable() {}

    public void onDisable() {}

    public void tick(Minecraft mc) {}

    /** Full-screen drawing that is not a movable HUD box (vignettes, etc). */
    public void drawOverlay(GuiGraphics g, Minecraft mc) {}

    /** Size of the HUD element in unscaled GUI pixels. Return 0 to hide. */
    public int width(Minecraft mc) { return 0; }

    public int height(Minecraft mc) { return 0; }

    /** Draw at (0,0) with size (w,h). The pose is already translated and scaled. */
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {}
}
