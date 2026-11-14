package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;

/** Recolors the whole client: menu, HUD cards, keystrokes and progress bars. */
public final class ThemeModule extends Module {
    public static final String[] NAMES = {"Lone", "Midnight", "Ocean", "Ember", "Rose", "Forest", "Mono", "Custom"};
    private static final int[][] PRESETS = {
            {0xFF3B82F6, 0xFF7DD3FC}, // Lone
            {0xFF7C5CFF, 0xFF22D3EE}, // Midnight
            {0xFF0EA5E9, 0xFF2DD4BF}, // Ocean
            {0xFFF97316, 0xFFFBBF24}, // Ember
            {0xFFEC4899, 0xFFF9A8D4}, // Rose
            {0xFF22C55E, 0xFFA3E635}, // Forest
            {0xFFE5E7EB, 0xFF9CA3AF}, // Mono
    };
    private final ChoiceSetting preset = add(new ChoiceSetting("Preset", 0, NAMES));
    private final ColorSetting accent = add(new ColorSetting("Accent", 0xFF3B82F6, false, false), "Custom colors", () -> this.preset.index == 7);
    private final ColorSetting accent2 = add(new ColorSetting("Accent 2", 0xFF7DD3FC, false, false), "Custom colors", () -> this.preset.index == 7);
    private final BoolSetting rainbow = add(new BoolSetting("Rainbow accent", false), "Animation");
    private final SliderSetting rainbowSpeed = add(new SliderSetting("Rainbow speed", 1, 10, 1, 4, "x"), "Animation", () -> this.rainbow.value);

    public ThemeModule() {
        super("theme", "Client Theme", "Pick the accent colors used across the client.",
                Category.VISUAL, false, 0f, 0f, false);
    }

    public int presetIndex() {
        return preset.index;
    }

    public void selectPreset(int i) {
        preset.index = Math.max(0, Math.min(NAMES.length - 1, i));
    }

    public static int[] colors(int i) {
        return i >= 0 && i < PRESETS.length ? PRESETS[i] : PRESETS[0];
    }

    @Override
    public void tick(Minecraft mc) {
        if (rainbow.value) {
            float hue = (System.currentTimeMillis() / (long) (60 / rainbowSpeed.value * 1.0 + 1)) % 360;
            Theme.setAccents(Theme.hsb(hue, 0.65f, 1f), Theme.hsb(hue + 50f, 0.65f, 1f));
        } else if (preset.index == 7) {
            Theme.setAccents(0xFF000000 | accent.rgb, 0xFF000000 | accent2.rgb);
        } else {
            int[] p = PRESETS[preset.index];
            Theme.setAccents(p[0], p[1]);
        }
    }

    @Override
    public void onDisable() {
        Theme.setAccents(PRESETS[0][0], PRESETS[0][1]);
    }
}
