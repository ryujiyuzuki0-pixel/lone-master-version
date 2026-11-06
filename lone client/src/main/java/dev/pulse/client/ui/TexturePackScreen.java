package dev.pulse.client.ui;

import dev.pulse.client.packs.LocalPacks;
import dev.pulse.client.packs.Opener;
import dev.pulse.client.packs.PacksMcApi;
import dev.pulse.client.packs.PacksMcApi.PackInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texture pack manager. "My packs" turns your installed packs on and off and sets their order.
 * "PacksMC" browses the packsmc.com catalog through its official API; getting a pack opens its page on the
 * site (downloads must go through packsmc.com), and "Import downloads" then copies the zip into your game.
 */
public class TexturePackScreen extends Screen {
    private enum Tab { LOCAL, PACKSMC }

    private record Btn(String id, int x, int y, int w, int h) {}

    private static final int ROW = 28;
    private static final String[] SORTS = {"recent", "downloads", "likes"};
    private static final String[] SORT_NAMES = {"Newest", "Most downloaded", "Most liked"};
    private static final String MC_VERSION = FabricLoader.getInstance().getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");

    private final Screen parent;
    private Tab tab = Tab.LOCAL;

    // local state
    private final Map<String, LocalPacks.Entry> known = new HashMap<>();
    private List<String> order = new ArrayList<>();      // enabled ids, lowest priority first
    private List<LocalPacks.Entry> shownLocal = new ArrayList<>();
    private String selLocal;
    private boolean dirty;

    // PacksMC state
    private final List<PackInfo> remote = new ArrayList<>();
    private PackInfo selRemote;
    private String nextCursor;
    private int sortIdx;
    private boolean loading, fetchedOnce;
    private String error = "";

    private EditBox search, keyBox;
    private String query = "";
    private String status = "";
    private long statusUntil;
    private float scroll, targetScroll, descScroll;
    private float dt = 0.016f;
    private long lastNs = System.nanoTime();
    private final long openedAt = System.currentTimeMillis();
    private final List<Btn> btns = new ArrayList<>();
    private int mouseX, mouseY;

    private int px, py, pw, ph, listX, listY, listW, listH, detX, detY, detW, detH;
    private int searchX, searchY, searchW, tabX, tabY;

    public TexturePackScreen(Screen parent) {
        super(Component.literal("Texture Packs"));
        this.parent = parent;
        PacksMcApi.init();
        reloadLocal();
    }

    // ------------------------------------------------------------------ local packs

    private void reloadLocal() {
        known.clear();
        for (LocalPacks.Entry e : LocalPacks.scan()) known.put(e.id(), e);
        if (order.isEmpty() && !dirty) order = LocalPacks.selected();
        order.removeIf(id -> !known.containsKey(id));
        rebuildLocal();
    }

    private void rebuildLocal() {
        shownLocal = new ArrayList<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            LocalPacks.Entry e = known.get(order.get(i));
            if (e != null && !e.locked()) shownLocal.add(e);
        }
        for (LocalPacks.Entry e : known.values().stream()
                .sorted((a, b) -> a.title().compareToIgnoreCase(b.title())).toList()) {
            if (!e.locked() && !order.contains(e.id())) shownLocal.add(e);
        }
        if (selLocal == null || shownLocal.stream().noneMatch(e -> e.id().equals(selLocal))) {
            selLocal = shownLocal.isEmpty() ? null : shownLocal.get(0).id();
        }
    }

    private LocalPacks.Entry selectedLocal() {
        return selLocal == null ? null : known.get(selLocal);
    }

    private void toggleSelected() {
        LocalPacks.Entry e = selectedLocal();
        if (e == null) return;
        if (order.contains(e.id())) order.remove(e.id());
        else order.add(e.id());
        dirty = true;
        rebuildLocal();
    }

    private void moveSelected(int delta) {
        // delta +1 = higher priority = earlier in the list on screen
        int i = selLocal == null ? -1 : order.indexOf(selLocal);
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= order.size()) return;
        LocalPacks.Entry other = known.get(order.get(j));
        if (other == null || other.locked()) return;
        String t = order.get(i);
        order.set(i, order.get(j));
        order.set(j, t);
        dirty = true;
        rebuildLocal();
    }

    private void importDownloads() {
        LocalPacks.ImportResult r = LocalPacks.importFromDownloads();
        reloadLocal();
        if (!r.imported().isEmpty()) {
            say("Imported " + String.join(", ", r.imported()) + ". Find it under My packs.");
        } else if (r.alreadyThere() > 0) {
            say("Those downloads are already in your resourcepacks folder.");
        } else if (r.notPacks() > 0) {
            say("Found zips in Downloads but none look like a pack (no pack.mcmeta at the top).");
        } else {
            say("No new pack zips in your Downloads folder from the last 2 hours.");
        }
    }

    private void say(String s) {
        status = s;
        statusUntil = System.currentTimeMillis() + 7000;
    }

    private void open(String url) {
        if (!Opener.openSite(url)) say("Could not open the browser. Go to " + url);
    }

    // ------------------------------------------------------------------ PacksMC

    private void fetch(boolean append) {
        if (loading || !PacksMcApi.hasKey()) return;
        loading = true;
        error = "";
        fetchedOnce = true;
        String cursor = append ? nextCursor : null;
        PacksMcApi.list(query, SORTS[sortIdx], cursor).whenComplete((page, ex) -> minecraft.execute(() -> {
            loading = false;
            if (ex != null) {
                error = PacksMcApi.message(ex);
                return;
            }
            if (!append) {
                remote.clear();
                scroll = targetScroll = 0;
            }
            remote.addAll(page.packs());
            nextCursor = page.nextCursor();
            if (selRemote == null || !remote.contains(selRemote)) selRemote = remote.isEmpty() ? null : remote.get(0);
            descScroll = 0;
        }));
    }

    private boolean matchesVersion(PackInfo p) {
        return !MC_VERSION.isEmpty() && p.versions().contains(MC_VERSION);
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        search = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(40);
        search.setTextColor(Theme.L_TEXT);
        search.setValue(query);
        addWidget(search);

        keyBox = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("API key"));
        keyBox.setBordered(false);
        keyBox.setMaxLength(120);
        keyBox.setTextColor(Theme.L_TEXT);
        addWidget(keyBox);
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1e3);
        return Long.toString(n);
    }

    private static String size(long b) {
        if (b <= 0) return "";
        if (b >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return Math.max(1, b / 1024) + " KB";
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
        mouseX = mx;
        mouseY = my;
        btns.clear();
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        float intro = Theme.ease((System.currentTimeMillis() - openedAt) / 250f);
        scroll += (targetScroll - scroll) * Math.min(1f, dt * 16f);

        double t = (System.currentTimeMillis() - openedAt) / 1000.0;
        Backdrop.draw(g, width, height, t, true);
        g.fill(0, 0, width, height, Theme.alpha(0xFF0A1E5C, 0.30f * intro));

        pw = Math.min(width - 24, 760);
        ph = Math.min(height - 24, 420);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1 - intro) * 10);
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 7, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 6, Theme.L_BG);

        // header
        Component title = Component.literal("Texture Packs").withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(px + 14, py + 10);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), title, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        tabX = px + 14 + 96;
        tabY = py + 7;
        int x = tabX;
        x += tabButton(g, "tab:local", "My packs", x, tabY, tab == Tab.LOCAL) + 4;
        x += tabButton(g, "tab:packsmc", "PacksMC", x, tabY, tab == Tab.PACKSMC) + 8;
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        boolean needKey = tab == Tab.PACKSMC && !PacksMcApi.hasKey();
        searchW = Math.min(160, px + pw - 14 - x);
        boolean showSearch = tab == Tab.PACKSMC && !needKey && searchW >= 70;
        if (showSearch) {
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
            search.setVisible(true);
            search.render(g, mx, my, 0f);
            if (search.getValue().isEmpty() && !focused) Theme.text(g, "Search, then Enter", searchX + 18, searchY + 4, Theme.L_DIM2);
        } else {
            search.setVisible(false);
            search.setFocused(false);
        }
        keyBox.setVisible(needKey);
        if (!needKey) keyBox.setFocused(false);

        listX = px + 10;
        listY = py + 38;
        listW = (int) (pw * 0.42);
        listH = ph - 38 - 52;
        detX = listX + listW + 10;
        detY = listY;
        detW = px + pw - 10 - detX;
        detH = listH;

        if (needKey) {
            drawKeyGate(g);
        } else if (tab == Tab.LOCAL) {
            drawLocalList(g);
            drawLocalDetails(g);
        } else {
            if (!fetchedOnce) fetch(false);
            drawRemoteList(g);
            drawRemoteDetails(g);
        }
        drawFooter(g, needKey);
    }

    private int tabButton(GuiGraphics g, String id, String label, int x, int y, boolean active) {
        int w = Theme.width(label) + 18;
        boolean hov = Theme.in(mouseX, mouseY, x, y, w, 16);
        int bg = active ? Theme.L_WHITE : (hov ? 0xFF232940 : Theme.L_CARD);
        Theme.roundRect(g, x, y, w, 16, 5, bg);
        Theme.centered(g, label, x + w / 2, y + 4, active ? Theme.L_INK : Theme.L_TEXT, false);
        btns.add(new Btn(id, x, y, w, 16));
        return w;
    }

    private int button(GuiGraphics g, String id, String label, int x, int y, boolean primary, boolean enabled) {
        int w = Theme.width(label) + 20, h = 18;
        boolean hov = enabled && Theme.in(mouseX, mouseY, x, y, w, h);
        int bg;
        if (!enabled) bg = Theme.alpha(Theme.L_CARD, 0.7f);
        else if (primary) bg = Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, hov ? 0.25f : 0f);
        else bg = hov ? 0xFF232940 : Theme.L_CARD;
        Theme.roundRect(g, x, y, w, h, 5, bg);
        Theme.centered(g, label, x + w / 2, y + 5, !enabled ? Theme.L_DIM2 : (primary ? 0xFFFFFFFF : Theme.L_TEXT), false);
        if (enabled) btns.add(new Btn(id, x, y, w, h));
        return w;
    }

    private void drawFooter(GuiGraphics g, boolean needKey) {
        int fy = py + ph - 18 - 9;
        int sy = fy - 14;
        // status line
        String line = System.currentTimeMillis() < statusUntil ? status : "";
        if (line.isEmpty() && tab == Tab.LOCAL && dirty) line = "Changes apply when you press Done.";
        if (!line.isEmpty()) Theme.text(g, Theme.fit(line, pw - 28), px + 14, sy, Theme.L_DIM);

        int x = px + 10;
        if (!needKey) {
            if (tab == Tab.LOCAL) {
                LocalPacks.Entry e = selectedLocal();
                boolean on = e != null && order.contains(e.id());
                x += button(g, "toggle", on ? "Disable" : "Enable", x, fy, true, e != null) + 5;
                x += button(g, "up", "Higher", x, fy, false, on) + 5;
                x += button(g, "down", "Lower", x, fy, false, on) + 5;
                x += button(g, "import", "Import downloads", x, fy, false, true) + 5;
                button(g, "folder", "Open folder", x, fy, false, true);
            } else {
                x += button(g, "get", "Get on PacksMC", x, fy, true, selRemote != null) + 5;
                x += button(g, "import", "Import downloads", x, fy, false, true) + 5;
                x += button(g, "sort", SORT_NAMES[sortIdx], x, fy, false, true) + 5;
                if (nextCursor != null && SORTS[sortIdx].equals("recent")) {
                    x += button(g, "more", loading ? "Loading..." : "Load more", x, fy, false, !loading) + 5;
                }
                button(g, "removekey", "Remove key", x, fy, false, true);
            }
        }
        int dw = 64;
        int dx = px + pw - dw - 10;
        boolean hov = Theme.in(mouseX, mouseY, dx, fy, dw, 18);
        Theme.roundRect(g, dx, fy, dw, 18, 5, Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, hov ? 0.25f : 0f));
        Theme.centered(g, "Done", dx + dw / 2, fy + 5, 0xFFFFFFFF, false);
        btns.add(new Btn("done", dx, fy, dw, 18));
    }

    private void pane(GuiGraphics g, int x, int y, int w, int h) {
        Theme.roundRect(g, x - 1, y - 1, w + 2, h + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, x, y, w, h, 5, 0xFF0C0F17);
    }

    private void scrollbar(GuiGraphics g, int count) {
        float max = Math.max(0, count * ROW + 8 - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll));
        if (max > 0) {
            int knob = Math.max(16, (int) (listH * (listH / (count * ROW + 8f))));
            int ky = listY + (int) ((listH - knob) * (scroll / max));
            Theme.roundRect(g, listX + listW - 5, ky, 3, knob, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
    }

    // ---- local

    private void drawLocalList(GuiGraphics g) {
        pane(g, listX, listY, listW, listH);
        Theme.text(g, shownLocal.size() + " packs, " + order.stream().filter(id -> known.containsKey(id) && !known.get(id).locked()).count() + " on",
                listX + 8, listY - 11 + 0, Theme.L_DIM2);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        if (shownLocal.isEmpty()) {
            Theme.centered(g, "No packs installed yet.", listX + listW / 2, listY + listH / 2 - 10, Theme.L_DIM, false);
            Theme.centered(g, "Use the PacksMC tab to find some.", listX + listW / 2, listY + listH / 2 + 2, Theme.L_DIM2, false);
        }
        for (int i = 0; i < shownLocal.size(); i++) {
            LocalPacks.Entry e = shownLocal.get(i);
            int ry = listY + 4 + i * ROW - Math.round(scroll);
            if (ry + ROW < listY || ry > listY + listH) continue;
            boolean sel = e.id().equals(selLocal);
            boolean hov = Theme.in(mouseX, mouseY, listX, ry, listW, ROW - 2) && Theme.in(mouseX, mouseY, listX, listY, listW, listH);
            boolean on = order.contains(e.id());
            if (sel) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.L_WHITE);
            else if (hov) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.alpha(0xFFFFFFFF, 0.08f));
            Theme.roundRect(g, listX + 9, ry + 4, 18, 18, 4, colorFor(e.id()));
            Theme.centered(g, e.title().isEmpty() ? "?" : e.title().substring(0, 1).toUpperCase(Locale.ROOT), listX + 18, ry + 9, 0xFFFFFFFF, false);
            int ink = sel ? Theme.L_INK : Theme.L_TEXT;
            String tag = on ? "ON" : "";
            Theme.text(g, Theme.fit(e.title(), listW - 40 - 40), listX + 34, ry + 9, ink);
            if (on) Theme.text(g, tag, listX + listW - 16 - Theme.width(tag), ry + 9, sel ? 0xFF16A34A : Theme.GOOD);
        }
        g.disableScissor();
        scrollbar(g, shownLocal.size());
    }

    private void drawLocalDetails(GuiGraphics g) {
        pane(g, detX, detY, detW, detH);
        LocalPacks.Entry e = selectedLocal();
        if (e == null) {
            Theme.centered(g, "Select a pack to see its details.", detX + detW / 2, detY + detH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        int x = detX + 14, y = detY + 14;
        Theme.roundRect(g, x, y, 32, 32, 6, colorFor(e.id()));
        Theme.centered(g, e.title().isEmpty() ? "?" : e.title().substring(0, 1).toUpperCase(Locale.ROOT), x + 16, y + 12, 0xFFFFFFFF, false);
        Component name = Component.literal(Theme.fit(e.title(), detW - 70)).withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(x + 42, y + 3);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), name, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        int pos = order.indexOf(e.id());
        String st = pos < 0 ? "Off" : "On, priority " + (order.size() - pos);
        Theme.text(g, st, x + 42, y + 20, pos < 0 ? Theme.L_DIM : Theme.GOOD);

        int ly = y + 44;
        g.fill(detX + 10, ly - 5, detX + detW - 10, ly - 4, Theme.L_LINE);
        if (!e.compatible()) {
            Theme.text(g, Theme.fit("Made for a different Minecraft version", detW - 28), x, ly, Theme.WARN);
            ly += 12;
        }
        Theme.text(g, "File", x, ly, Theme.L_DIM2);
        Theme.text(g, Theme.fit(e.id().replaceFirst("^file/", ""), detW - 80), x + 40, ly, Theme.L_TEXT);
        ly += 16;
        small(g, "DESCRIPTION", x, ly);
        ly += 12;
        drawWrapped(g, e.description().isEmpty() ? "No description provided." : e.description(), x, ly);
    }

    // ---- PacksMC

    private void drawKeyGate(GuiGraphics g) {
        pane(g, listX, listY, pw - 20, listH);
        int cx = px + pw / 2, y = listY + 18;
        Theme.centered(g, "Connect to PacksMC", cx, y, Theme.L_TEXT, false);
        y += 16;
        String[] lines = {
                "PacksMC gives every account a free API key for browsing its catalog.",
                "1. Sign in on packsmc.com and open API Access, then create a key.",
                "2. Paste it below (Ctrl+V) and press Enter.",
                "Your key is stored only in config/lone-packsmc.txt and sent only to packsmc.com."};
        for (String s : lines) {
            for (String w : wrap(s, pw - 60)) {
                Theme.centered(g, w, cx, y, Theme.L_DIM, false);
                y += 11;
            }
            y += 3;
        }
        y += 6;
        int bw = Math.min(300, pw - 60), bx = cx - bw / 2;
        boolean f = keyBox.isFocused();
        Theme.roundRect(g, bx - 1, y - 1, bw + 2, 20, 5, f ? Theme.alpha(Theme.ACCENT, 0.7f) : Theme.L_LINE);
        Theme.roundRect(g, bx, y, bw, 18, 4, 0xFF0C0F17);
        keyBox.setX(bx + 6);
        keyBox.setY(y + 5);
        keyBox.setWidth(bw - 12);
        keyBox.setHeight(8);
        keyBox.render(g, mouseX, mouseY, 0f);
        if (keyBox.getValue().isEmpty() && !f) Theme.text(g, "pmc_...", bx + 6, y + 5, Theme.L_DIM2);
        keyBoxRect = new int[]{bx, y, bw, 18};
        y += 28;
        int w1 = button(g, "getkey", "Get a free key", cx - 90, y, false, true);
        button(g, "savekey", "Save key", cx - 90 + w1 + 6, y, true, true);
    }

    private int[] keyBoxRect = new int[]{0, 0, 0, 0};

    private void drawRemoteList(GuiGraphics g) {
        pane(g, listX, listY, listW, listH);
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        if (!error.isEmpty()) {
            int y = listY + 20;
            for (String w : wrap(error, listW - 24)) {
                Theme.centered(g, w, listX + listW / 2, y, Theme.BAD, false);
                y += 11;
            }
        } else if (remote.isEmpty()) {
            Theme.centered(g, loading ? "Loading packs..." : "No packs found.", listX + listW / 2, listY + listH / 2 - 4, Theme.L_DIM, false);
        }
        for (int i = 0; i < remote.size(); i++) {
            PackInfo p = remote.get(i);
            int ry = listY + 4 + i * ROW - Math.round(scroll);
            if (ry + ROW < listY || ry > listY + listH) continue;
            boolean sel = p == selRemote;
            boolean hov = Theme.in(mouseX, mouseY, listX, ry, listW, ROW - 2) && Theme.in(mouseX, mouseY, listX, listY, listW, listH);
            if (sel) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.L_WHITE);
            else if (hov) Theme.roundRect(g, listX + 4, ry, listW - 12, ROW - 3, 5, Theme.alpha(0xFFFFFFFF, 0.08f));
            Theme.roundRect(g, listX + 9, ry + 4, 18, 18, 4, colorFor(p.id()));
            Theme.centered(g, p.name().isEmpty() ? "?" : p.name().substring(0, 1).toUpperCase(Locale.ROOT), listX + 18, ry + 9, 0xFFFFFFFF, false);
            int ink = sel ? Theme.L_INK : Theme.L_TEXT;
            String res = p.resolution();
            Theme.text(g, Theme.fit(p.name(), listW - 40 - Theme.width(res) - 26), listX + 34, ry + 4, ink);
            Theme.text(g, Theme.fit("by " + p.author() + "  " + compact(p.downloads()) + " dl", listW - 52), listX + 34, ry + 15, sel ? 0xFF6B7186 : Theme.L_DIM2);
            Theme.text(g, res, listX + listW - 14 - Theme.width(res), ry + 4, sel ? 0xFF6B7186 : Theme.L_DIM2);
        }
        g.disableScissor();
        scrollbar(g, remote.size());
    }

    private void drawRemoteDetails(GuiGraphics g) {
        pane(g, detX, detY, detW, detH);
        PackInfo p = selRemote;
        if (p == null) {
            Theme.centered(g, "Select a pack to see its details.", detX + detW / 2, detY + detH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        int x = detX + 14, y = detY + 14;
        Theme.roundRect(g, x, y, 32, 32, 6, colorFor(p.id()));
        Theme.centered(g, p.name().isEmpty() ? "?" : p.name().substring(0, 1).toUpperCase(Locale.ROOT), x + 16, y + 12, 0xFFFFFFFF, false);
        Component name = Component.literal(Theme.fit(p.name(), detW - 70)).withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(x + 42, y + 3);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), name, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        Theme.text(g, Theme.fit("by " + p.author() + (p.verified() ? " (verified)" : ""), detW - 70), x + 42, y + 20, Theme.L_DIM);

        int ly = y + 44;
        g.fill(detX + 10, ly - 5, detX + detW - 10, ly - 4, Theme.L_LINE);
        boolean match = matchesVersion(p);
        String vers = String.join(", ", p.versions());
        ly = info(g, "Versions", vers.isEmpty() ? "not listed" : vers, x, ly, match ? Theme.GOOD : Theme.L_TEXT);
        ly = info(g, "Resolution", p.resolution(), x, ly, Theme.L_TEXT);
        ly = info(g, "Downloads", compact(p.downloads()) + "   Likes " + compact(p.likes()), x, ly, Theme.L_TEXT);
        ly = info(g, "Size", size(p.sizeBytes()), x, ly, Theme.L_TEXT);
        ly = info(g, "License", p.license() + (p.requiresCredit() ? " (credit the creator)" : ""), x, ly, Theme.L_TEXT);
        if (p.exclusive()) ly = info(g, "Access", "Packs+ / early access: download on the site", x, ly, Theme.WARN);
        ly += 6;
        String how = "Press Get on PacksMC to open this pack's page, download it there, then come back and press Import downloads.";
        drawWrapped(g, how, x, ly);
    }

    private int info(GuiGraphics g, String label, String value, int x, int y, int color) {
        if (value == null || value.isBlank()) return y;
        Theme.text(g, label, x, y, Theme.L_DIM2);
        Theme.text(g, Theme.fit(value, detW - 100), x + 56, y, color);
        return y + 12;
    }

    private void drawWrapped(GuiGraphics g, String text, int x, int ly) {
        List<String> lines = wrap(text, detW - 28);
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

    // ------------------------------------------------------------------ input

    private void click(String id) {
        switch (id) {
            case "tab:local" -> {
                tab = Tab.LOCAL;
                scroll = targetScroll = 0;
                descScroll = 0;
            }
            case "tab:packsmc" -> {
                tab = Tab.PACKSMC;
                scroll = targetScroll = 0;
                descScroll = 0;
            }
            case "toggle" -> toggleSelected();
            case "up" -> moveSelected(1);
            case "down" -> moveSelected(-1);
            case "import" -> importDownloads();
            case "folder" -> {
                if (!Opener.openFolder(LocalPacks.folder())) say("Could not open the folder: " + LocalPacks.folder());
            }
            case "get" -> {
                if (selRemote != null) {
                    open(selRemote.url());
                    say("Download it on the site, then press Import downloads.");
                }
            }
            case "sort" -> {
                sortIdx = (sortIdx + 1) % SORTS.length;
                nextCursor = null;
                fetch(false);
            }
            case "more" -> fetch(true);
            case "removekey" -> {
                PacksMcApi.setKey("");
                remote.clear();
                selRemote = null;
                fetchedOnce = false;
                say("PacksMC key removed.");
            }
            case "getkey" -> open(PacksMcApi.SITE + "/api-access");
            case "savekey" -> saveKey();
            case "done" -> onClose();
            default -> {
            }
        }
    }

    private void saveKey() {
        String k = keyBox.getValue().trim();
        if (!PacksMcApi.setKey(k) || k.isEmpty()) {
            say("That does not look like a PacksMC key (it starts with pmc_).");
            return;
        }
        keyBox.setValue("");
        keyBox.setFocused(false);
        fetchedOnce = false;
        error = "";
        say("Key saved.");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        boolean onSearch = search.visible && Theme.in(mx, my, searchX, searchY, searchW, 16);
        boolean onKey = keyBox.visible && Theme.in(mx, my, keyBoxRect[0], keyBoxRect[1], keyBoxRect[2], keyBoxRect[3]);
        search.setFocused(onSearch);
        keyBox.setFocused(onKey);
        if (onSearch) {
            setFocused(search);
            search.mouseClicked(e, dbl);
            return true;
        }
        if (onKey) {
            setFocused(keyBox);
            keyBox.mouseClicked(e, dbl);
            return true;
        }
        if (getFocused() == search || getFocused() == keyBox) setFocused(null);
        if (e.button() == 0) {
            for (Btn b : btns) {
                if (Theme.in(mx, my, b.x(), b.y(), b.w(), b.h())) {
                    click(b.id());
                    return true;
                }
            }
            if (Theme.in(mx, my, listX, listY, listW, listH) && !(tab == Tab.PACKSMC && !PacksMcApi.hasKey())) {
                int i = (int) ((my - listY - 4 + scroll) / ROW);
                if (tab == Tab.LOCAL && i >= 0 && i < shownLocal.size()) {
                    selLocal = shownLocal.get(i).id();
                    descScroll = 0;
                } else if (tab == Tab.PACKSMC && i >= 0 && i < remote.size()) {
                    selRemote = remote.get(i);
                    descScroll = 0;
                }
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Theme.in(mx, my, detX, detY, detW, detH)) {
            descScroll = Math.max(0, descScroll - (float) sy * 20f);
            return true;
        }
        int count = tab == Tab.LOCAL ? shownLocal.size() : remote.size();
        float max = Math.max(0, count * ROW + 8 - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll - (float) sy * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        boolean typing = search.isFocused() || keyBox.isFocused();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) {
                search.setFocused(false);
                keyBox.setFocused(false);
                setFocused(null);
            } else {
                onClose();
            }
            return true;
        }
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) {
            if (search.isFocused()) {
                query = search.getValue();
                nextCursor = null;
                fetch(false);
                return true;
            }
            if (keyBox.isFocused()) {
                saveKey();
                return true;
            }
        }
        if (!typing && (k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_UP)) {
            int step = k == GLFW.GLFW_KEY_DOWN ? 1 : -1;
            if (tab == Tab.LOCAL) {
                int i = -1;
                for (int j = 0; j < shownLocal.size(); j++) if (shownLocal.get(j).id().equals(selLocal)) i = j;
                i += step;
                if (i >= 0 && i < shownLocal.size()) {
                    selLocal = shownLocal.get(i).id();
                    descScroll = 0;
                    keepVisible(i);
                }
            } else {
                int i = remote.indexOf(selRemote) + step;
                if (i >= 0 && i < remote.size()) {
                    selRemote = remote.get(i);
                    descScroll = 0;
                    keepVisible(i);
                }
            }
            return true;
        }
        return super.keyPressed(e);
    }

    private void keepVisible(int i) {
        float top = i * ROW, bottom = top + ROW;
        if (top < targetScroll) targetScroll = top;
        if (bottom > targetScroll + listH - 8) targetScroll = bottom - listH + 8;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        if (dirty) {
            try {
                LocalPacks.apply(order);
            } catch (Exception ex) {
                dev.pulse.client.PulseClient.LOG.warn("Could not apply the resource pack selection", ex);
            }
            dirty = false;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
