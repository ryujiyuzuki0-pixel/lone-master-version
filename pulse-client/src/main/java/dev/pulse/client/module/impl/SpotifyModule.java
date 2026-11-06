package dev.pulse.client.module.impl;

import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.spotify.SpotifyClient;
import dev.pulse.client.ui.HudRenderer;
import dev.pulse.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class SpotifyModule extends Module {
    private final BoolSetting progressBar = add(new BoolSetting("Show progress bar", true));
    private final BoolSetting timestamps = add(new BoolSetting("Show timestamps", true));
    private final BoolSetting equalizer = add(new BoolSetting("Show equalizer", true));
    private final BoolSetting hideIdle = add(new BoolSetting("Hide when nothing plays", false));
    private final SliderSetting boxWidth = add(new SliderSetting("Width", 150, 320, 5, 200, "px"), "Layout");

    public SpotifyModule() {
        super("spotify", "Now Playing", "Shows your current Spotify track with a progress bar.",
                Category.UTILITY, true, 0.5f, 0.97f, true);
    }

    private boolean full() {
        return SpotifyClient.hasTrack() || HudRenderer.editing();
    }

    @Override
    public int width(Minecraft mc) {
        if (hideIdle.value && !full()) return 0;
        return boxWidth.intValue();
    }

    @Override
    public int height(Minecraft mc) {
        if (hideIdle.value && !full()) return 0;
        return full() ? 50 : 30;
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    @Override
    public void draw(GuiGraphics g, Minecraft mc, int w, int h) {
        if (!full()) {
            text(g, "Spotify", 8, 6, textColor());
            String line = SpotifyClient.connected() ? "Nothing playing" : "Not connected - /pulse spotify login";
            text(g, Theme.fit(line, w - 16), 8, 17, dimColor());
            return;
        }
        boolean sample = !SpotifyClient.hasTrack();
        String title = sample ? "Song Title" : SpotifyClient.title();
        String artist = sample ? "Artist Name" : SpotifyClient.artist();
        long dur = sample ? 200_000 : SpotifyClient.duration();
        long prog = sample ? 72_000 : SpotifyClient.progress();
        boolean playing = sample || SpotifyClient.playing();

        int textX = 8;
        if (equalizer.value) {
            textX = 52;
            Theme.roundRect(g, 6, 6, 38, 38, 5, 0xFF1A1F3A);
            double t = System.currentTimeMillis() / 180.0;
            for (int i = 0; i < 4; i++) {
                int bh = playing ? 8 + (int) Math.round((Math.sin(t + i * 1.3) * 0.5 + 0.5) * 20) : 6;
                int bx = 11 + i * 7;
                int top = 38 - bh + 6;
                Theme.roundRect(g, bx, top, 4, bh, 2, Theme.lerp(Theme.ACCENT, Theme.ACCENT2, i / 3f));
            }
        }

        text(g, Theme.fit(title, w - textX - 6), textX, 8, textColor());
        text(g, Theme.fit(artist, w - textX - 6), textX, 19, dimColor());

        int bx = textX, bw = w - textX - 8, by = 33;
        if (progressBar.value) {
            Theme.roundRect(g, bx, by, bw, 3, 1, 0xFF2A2F4A);
            int fill = dur <= 0 ? 0 : (int) Math.round(bw * (prog / (double) dur));
            if (fill > 0) Theme.hGradient(g, bx, by, fill, 3, Theme.ACCENT, Theme.ACCENT2);
        }
        if (timestamps.value) {
            Theme.scaledText(g, time(prog), bx, 39, 0.75f, dimColor(), false);
            String d = time(dur);
            Theme.scaledText(g, d, bx + bw - (int) (Theme.width(d) * 0.75f), 39, 0.75f, dimColor(), false);
        }
    }
}
