package dev.pulse.client.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The player's installed mods (what Fabric has loaded), with search and a details pane. */
public class InstalledModsScreen extends Screen {
    private record Entry(String id, String name, String version, String description, String authors,
                         String environment, String license, String homepage, boolean library) {}

    private static final int ROW = 28;

    private final Screen parent;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private Entry selected;
    private EditBox search;
    private String query = "";
    private boolean showLibraries;
    private float scroll, targetScroll, descScroll;
    private float dt = 0.016f;
    private long lastNs = System.nanoTime();
    private final long openedAt = System.currentTimeMillis();
    private final java.util.Map<String, Float> hover = new java.util.HashMap<>();
    private String hot = "";

    private int px, py, pw, ph, listX, listY, listW, listH, detX, detY, detW, detH;
    private int doneX, doneY, doneW = 64, doneH = 18, libX, libY, libW, libH, searchX, searchY, searchW;

    public InstalledModsScreen(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
        load();
    }

    private void load() {
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            if (c.getContainingMod().isPresent()) continue; // bundled sub-modules (Fabric API parts etc.)
            ModMetadata m = c.getMetadata();
            boolean builtin = "builtin".equals(m.getType());
            String name = m.getName() == null || m.getName().isBlank() ? m.getId() : m.getName();
            String authors = m.getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
            String license = String.join(", ", m.getLicense());
            String home = m.getContact().get("homepage").orElse("");
            all.add(new Entry(m.getId(), name, m.getVersion().getFriendlyString(), m.getDescription() == null ? "" : m.getDescription(),
                    authors, m.getEnvironment().name().toLowerCase(Locale.ROOT), license, home, builtin));
        }
        all.sort(Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT)));
        refilter();
    }

    private void refilter() {
        String q = query.toLowerCase(Locale.ROOT);
        shown = new ArrayList<>();
        for (Entry e : all) {
            if (e.library() && !showLibraries) continue;
            if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q) && !e.id().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        if (selected == null || !shown.contains(selected)) selected = shown.isEmpty() ? null : shown.get(0);
        scroll = targetScroll = 0;
        descScroll = 0;
    }

    @Override
    protected void init() {
        search = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(32);
        search.setTextColor(Theme.L_TEXT);
        search.setResponder(s -> {
            query = s;
            refilter();
        });
        search.setValue(query);
        addWidget(search);
    }

    private float hv(String id) {
        float cur = hover.getOrDefault(id, 0f);
        cur += ((id.equals(hot) ? 1f : 0f) - cur) * Math.min(1f, dt * 16f);
        hover.put(id, cur);
        return cur;
    }

    private static int colorFor(String id) {
        return Theme.hsb(Math.abs(id.hashCode()) % 360, 0.5f, 0.85f);
    }

    private List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String test = line.length() == 0 ? word : line + " " + word;
                if (Theme.width(test) > width && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(test);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        float intro = Theme.ease((System.currentTimeMillis() - openedAt) / 250f);
        scroll += (targetScroll - scroll) * Math.min(1f, dt * 16f);

        double t = (System.currentTimeMillis() - openedAt) / 1000.0;
        Backdrop.draw(g, width, height, t, true);
        g.fill(0, 0, width, height, Theme.alpha(0xFF0A1E5C, 0.30f * intro));

        pw = Math.min(width - 24, 720);
        ph = Math.min(height - 24, 400);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1 - intro) * 10);
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 7, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 6, Theme.L_BG);

        // hover target
        hot = "";
        for (int i = 0; i < shown.size(); i++) {
            int ry = listY + i * ROW - Math.round(scroll);
            if (Theme.in(mx, my, listX, listY, listW, listH) && Theme.in(mx, my, listX, ry, listW, ROW - 2)) hot = "row:" + i;
        }
        if (Theme.in(mx, my, doneX, doneY, doneW, doneH)) hot = "done";
        if (Theme.in(mx, my, libX, libY, libW, libH)) hot = "lib";

        // header
        Component title = Component.literal("Mods").withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(px + 14, py + 10);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), title, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        Theme.text(g, shown.size() + " of " + all.size() + " installed", px + 14 + 48, py + 12, Theme.L_DIM);
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        searchW = Math.max(100, Math.min(170, pw / 3));
        searchX = px + pw - searchW - 14;
        searchY = py + 7;
        boolean focused = search.isFocused();
        Theme.roundRect(g, searchX - 1, searchY - 1, searchW + 2, 18, 5, focused ? Theme.alpha(Theme.ACCENT, 0.7f) : Theme.L_LINE);
        Theme.roundRect(g, searchX, searchY, searchW, 16, 4, 0xFF0C0F17);
        Icons.draw(g, Icons.SEARCH, searchX + 5, searchY + 4, 1, focused ? Theme.L_TEXT : Theme.L_DIM);
        search.setX(searchX + 18);
        search.setY(searchY + 4);
        search.setWidth(searchW - 24);
        search.setHeight(8);
        search.render(g, mx, my, 0f);
        if (query.isEmpty() && !focused) Theme.text(g, "Search mods", searchX + 18, searchY + 4, Theme.L_DIM2);

        // panes
        listX = px + 10;
        listY = py + 38;
        listW = (int) (pw * 0.42);
        listH = ph - 38 - 36;
        detX = listX + listW + 10;
        detY = listY;
        detW = px + pw - 10 - detX;
        detH = listH;
        drawList(g);
        drawDetails(g);

        // footer
        doneW = 64;
        doneX = px + pw - doneW - 10;
        doneY = py + ph - doneH - 9;
        float dh = hv("done");
        Theme.roundRect(g, doneX, doneY, doneW, doneH, 5, Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.25f * dh));
        Theme.centered(g, "Done", doneX + doneW / 2, doneY + 5, 0xFFFFFFFF, false);

        String libLabel = showLibraries ? "Hide libraries" : "Show libraries";
        libW = Theme.width(libLabel) + 20;
        libH = 18;
        libX = px + 10;
        libY = doneY;
        float lh = hv("lib");
        Theme.roundRect(g, libX, libY, libW, libH, 5, Theme.lerp(Theme.L_CARD, 0xFF232940, lh));
        Theme.centered(g, libLabel, libX + libW / 2, libY + 5, Theme.L_TEXT, false);
    }

    private void drawList(GuiGraphics g) {
        Theme.roundRect(g, listX - 1, listY - 1, listW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, listX, listY, listW, listH, 5, 0xFF0C0F17);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        if (shown.isEmpty()) {
            Theme.centered(g, "No mods match.", listX + listW / 2, listY + listH / 2 - 4, Theme.L_DIM, false);
        }
        for (int i = 0; i < shown.size(); i++) {
            Entry e = shown.get(i);
            int ry = listY + 4 + i * ROW - Math.round(scroll);
            if (ry + ROW < listY || ry > listY + listH) continue;
            boolean sel = e == selected;
            float h = hv("row:" + i);
            if (sel) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.L_WHITE);
            else if (h > 0.01f) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.alpha(0xFFFFFFFF, 0.08f * h));
            Theme.roundRect(g, listX + 9, ry + 4, 18, 18, 4, colorFor(e.id()));
            Theme.centered(g, e.name().isEmpty() ? "?" : e.name().substring(0, 1).toUpperCase(Locale.ROOT),
                    listX + 18, ry + 9, 0xFFFFFFFF, false);
            int ink = sel ? Theme.L_INK : Theme.lerp(0xFFC2C7DC, Theme.L_TEXT, h);
            String v = Theme.fit(e.version(), 50);
            Theme.text(g, Theme.fit(e.name(), listW - 40 - Theme.width(v) - 24), listX + 34, ry + 9, ink);
            Theme.text(g, v, listX + listW - 14 - Theme.width(v), ry + 9, sel ? 0xFF6B7186 : Theme.L_DIM2);
        }
        g.disableScissor();
        float max = Math.max(0, shown.size() * ROW + 8 - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll));
        if (max > 0) {
            int knob = Math.max(16, (int) (listH * (listH / (shown.size() * ROW + 8f))));
            int ky = listY + (int) ((listH - knob) * (scroll / max));
            Theme.roundRect(g, listX + listW - 5, ky, 3, knob, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
    }

    private void drawDetails(GuiGraphics g) {
        Theme.roundRect(g, detX - 1, detY - 1, detW + 2, detH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, detX, detY, detW, detH, 5, 0xFF0C0F17);
        if (selected == null) {
            Theme.centered(g, "Select a mod to see its details.", detX + detW / 2, detY + detH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        Entry e = selected;
        int x = detX + 14, y = detY + 14;
        Theme.roundRect(g, x, y, 32, 32, 6, colorFor(e.id()));
        Theme.centered(g, e.name().isEmpty() ? "?" : e.name().substring(0, 1).toUpperCase(Locale.ROOT), x + 16, y + 12, 0xFFFFFFFF, false);
        Component name = Component.literal(Theme.fit(e.name(), detW - 70)).withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(x + 42, y + 3);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), name, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        Theme.text(g, Theme.fit("v" + e.version() + "  -  " + e.id(), detW - 70), x + 42, y + 20, Theme.L_DIM);

        int ly = y + 44;
        g.fill(detX + 10, ly - 5, detX + detW - 10, ly - 4, Theme.L_LINE);
        ly = infoLine(g, "Author", e.authors(), x, ly);
        ly = infoLine(g, "Runs on", e.environment().equals("*") ? "client and server" : e.environment(), x, ly);
        ly = infoLine(g, "License", e.license(), x, ly);
        ly = infoLine(g, "Website", e.homepage(), x, ly);

        ly += 4;
        small(g, "DESCRIPTION", x, ly);
        ly += 12;
        List<String> lines = wrap(e.description().isEmpty() ? "No description provided." : e.description(), detW - 28);
        g.enableScissor(detX, ly, detX + detW, detY + detH - 4);
        for (int i = 0; i < lines.size(); i++) {
            int yy = ly + i * 10 - Math.round(descScroll);
            if (yy > detY + detH) break;
            Theme.text(g, lines.get(i), x, yy, 0xFFC2C7DC);
        }
        g.disableScissor();
        descScroll = Math.max(0, Math.min(Math.max(0, lines.size() * 10 - (detY + detH - 6 - ly)), descScroll));
    }

    private void small(GuiGraphics g, String s, int x, int y) {
        Theme.scaledText(g, s, x, y, 0.8f, Theme.L_DIM2, false);
    }

    private int infoLine(GuiGraphics g, String label, String value, int x, int y) {
        if (value == null || value.isBlank()) return y;
        Theme.text(g, label, x, y, Theme.L_DIM2);
        Theme.text(g, Theme.fit(value, detW - 100), x + 52, y, Theme.L_TEXT);
        return y + 12;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        boolean onSearch = Theme.in(mx, my, searchX, searchY, searchW, 16);
        search.setFocused(onSearch);
        if (onSearch) {
            setFocused(search);
            search.mouseClicked(e, dbl);
            return true;
        }
        if (getFocused() == search) setFocused(null);
        if (Theme.in(mx, my, doneX, doneY, doneW, doneH)) {
            onClose();
            return true;
        }
        if (Theme.in(mx, my, libX, libY, libW, libH)) {
            showLibraries = !showLibraries;
            refilter();
            return true;
        }
        if (Theme.in(mx, my, listX, listY, listW, listH)) {
            int i = (int) ((my - listY - 4 + scroll) / ROW);
            if (i >= 0 && i < shown.size()) {
                selected = shown.get(i);
                descScroll = 0;
            }
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Theme.in(mx, my, detX, detY, detW, detH)) {
            descScroll = Math.max(0, descScroll - (float) sy * 20f);
            return true;
        }
        float max = Math.max(0, shown.size() * ROW + 8 - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll - (float) sy * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        boolean typing = search.isFocused();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) {
                search.setFocused(false);
                setFocused(null);
            } else {
                onClose();
            }
            return true;
        }
        if (k == GLFW.GLFW_KEY_F && (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            search.setFocused(true);
            setFocused(search);
            return true;
        }
        if (k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_UP) {
            int i = shown.indexOf(selected) + (k == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            if (i >= 0 && i < shown.size()) {
                selected = shown.get(i);
                descScroll = 0;
                float top = i * ROW, bottom = top + ROW;
                if (top < targetScroll) targetScroll = top;
                if (bottom > targetScroll + listH - 8) targetScroll = bottom - listH + 8;
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
