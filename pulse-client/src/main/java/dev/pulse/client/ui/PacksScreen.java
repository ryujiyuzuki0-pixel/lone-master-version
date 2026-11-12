package dev.pulse.client.ui;

import dev.pulse.client.packs.PackImporter;
import dev.pulse.client.packs.PackInfo;
import dev.pulse.client.packs.PackThumbs;
import dev.pulse.client.packs.PacksMcApi;
import dev.pulse.client.packs.PacksSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Texture pack browser backed by the official PacksMC API. Lists and searches packs, shows previews and details,
 * and installs a pack with one click on "Download": the pack's PacksMC page opens (downloads must go through the
 * site), and as soon as the browser has saved the zip the client validates it, copies it into resourcepacks,
 * enables it and reloads. Dropping a .zip onto the window installs it the same way.
 * Same look as the other Lone screens (Theme palette, rounded cards, eased hover and scroll).
 */
public class PacksScreen extends Screen {
    private static final String[] SORTS = {"recent", "downloads", "likes"};
    private static final String[] SORT_LABELS = {"Newest", "Popular", "Most liked"};
    private static final String[] RES = {"", "16x", "32x", "64x", "128x"};
    private static final int GAP = 6;
    private static final int MIN_CARD_W = 112;

    private record Hit(int x, int y, int w, int h, String id) {}

    private final Screen parent;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private String hot = "";

    // data
    private final List<PackInfo> packs = new ArrayList<>();
    private final Map<String, PackInfo> details = new HashMap<>();
    private String cursor;
    private boolean loading, endReached;
    private int generation;
    private String error;
    private PackInfo selected;
    private int galleryIdx;

    // query
    private int sortIdx, resIdx;
    private String query = "", typed = "";
    private long typedAt;
    private boolean dirty;

    // widgets and status
    private EditBox search, keyBox;
    private String keyTyped = "";
    private boolean keyBusy;
    private String keyError = "";
    private String toast = "";
    private int toastColor = Theme.L_TEXT;
    private long toastAt;
    private boolean waiting;

    // animation
    private float scroll, targetScroll, descScroll;
    private float dt = 0.016f;
    private long lastNs = System.nanoTime();
    private final long openedAt = System.currentTimeMillis();

    // layout of the last frame
    private int px, py, pw, ph, listX, listY, listW, listH, detX, detY, detW, detH, cols, cardW, cardH, thumbH;
    private int searchX, searchY, searchW;

    public PacksScreen(Screen parent) {
        super(Component.literal("Texture Packs"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        search = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Search packs"));
        search.setBordered(false);
        search.setMaxLength(40);
        search.setTextColor(Theme.L_TEXT);
        search.setResponder(s -> {
            typed = s;
            typedAt = System.currentTimeMillis();
            dirty = true;
        });
        search.setValue(typed);
        addWidget(search);

        keyBox = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("API key"));
        keyBox.setBordered(false);
        keyBox.setMaxLength(120);
        keyBox.setTextColor(Theme.L_TEXT);
        keyBox.setResponder(s -> keyTyped = s);
        keyBox.setValue(keyTyped);
        addWidget(keyBox);

        if (PacksMcApi.hasKey() && packs.isEmpty() && !loading && error == null) reload();
    }

    @Override
    public void removed() {
        PackImporter.disarm();
        PackThumbs.clear();
        generation++; // late answers are dropped
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        generation++;
        packs.clear();
        cursor = null;
        endReached = false;
        loading = false;
        error = null;
        selected = null;
        scroll = targetScroll = 0;
        fetchPage();
    }

    private void fetchPage() {
        if (loading || endReached) return;
        loading = true;
        error = null;
        final int gen = generation;
        final boolean paged = SORTS[sortIdx].equals("recent");
        PacksMcApi.list(query, SORTS[sortIdx], RES[resIdx], cursor, paged ? 24 : 100).whenComplete((page, ex) ->
                minecraft.execute(() -> {
                    if (gen != generation) return;
                    loading = false;
                    if (ex != null) {
                        error = PacksMcApi.message(ex);
                        return;
                    }
                    packs.addAll(page.packs());
                    cursor = page.nextCursor();
                    endReached = !paged || cursor == null || page.packs().isEmpty();
                    if (selected == null && !packs.isEmpty()) select(packs.get(0));
                }));
    }

    private void select(PackInfo p) {
        selected = p;
        galleryIdx = 0;
        descScroll = 0;
        if (details.containsKey(p.id())) return;
        final int gen = generation;
        PacksMcApi.detail(p.slug().isEmpty() ? p.id() : p.slug()).whenComplete((d, ex) -> minecraft.execute(() -> {
            if (gen == generation && ex == null) details.put(p.id(), d); // a failed detail call just leaves the summary
        }));
    }

    private PackInfo shownDetail() {
        return selected == null ? null : details.getOrDefault(selected.id(), selected);
    }

    private void say(String s, int color) {
        toast = s;
        toastColor = color;
        toastAt = System.currentTimeMillis();
    }

    private void onInstalled(PackImporter.Result r) {
        waiting = false;
        say(r.message(), r.ok() ? Theme.GOOD : Theme.BAD);
    }

    private void download(PackInfo p) {
        if (p == null || !PacksMcApi.isPacksMcUrl(p.downloadUrl())) {
            say("That pack has no valid download page.", Theme.BAD);
            return;
        }
        String problem = PackImporter.arm(PacksSettings.autoActivate(), this::onInstalled);
        waiting = problem == null;
        openUri(p.downloadUrl());
        say(problem == null ? "Download it in your browser. It installs here the moment it finishes." : problem,
                problem == null ? Theme.L_TEXT : Theme.WARN);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Opens a link in the default browser without touching net.minecraft.Util, whose package differs between
     * Minecraft versions. Uses the OS's own opener (no shell, so the URL can't be interpreted as a command).
     */
    private static void openUri(String uri) {
        if (uri == null || !(uri.startsWith("https://") || uri.startsWith("http://"))) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder pb;
            if (os.contains("win")) pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri);
            else if (os.contains("mac")) pb = new ProcessBuilder("open", uri);
            else pb = new ProcessBuilder("xdg-open", uri);
            pb.start();
        } catch (Exception e) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI.create(uri));
            } catch (Throwable ignored) {
                // no way to open a browser here
            }
        }
    }

    private float hv(String id) {
        float cur = hover.getOrDefault(id, 0f);
        cur += ((id.equals(hot) ? 1f : 0f) - cur) * Math.min(1f, dt * 16f);
        hover.put(id, cur);
        return cur;
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return Long.toString(n);
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1_048_576) return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static int accentOf(PackInfo p) {
        return p.accent() != 0 ? p.accent() : Theme.hsb(Math.abs(p.id().hashCode()) % 360, 0.5f, 0.8f);
    }

    private List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        for (String para : s.replace("\r", "").split("\n")) {
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

    private void hit(int x, int y, int w, int h, String id) {
        hits.add(new Hit(x, y, w, h, id));
    }

    /** Draws an image cropped to fill the box (cover), or a coloured placeholder while it loads. */
    private void drawImage(GuiGraphics g, String url, int x, int y, int w, int h, PackInfo p) {
        PackThumbs.Thumb t = PackThumbs.get(url);
        if (t == null) {
            int c = accentOf(p);
            Theme.roundRect(g, x, y, w, h, 4, Theme.lerp(c, Theme.L_INSET, 0.55f));
            String ch = p.title().isEmpty() ? "?" : p.title().substring(0, 1).toUpperCase(Locale.ROOT);
            Theme.scaledText(g, ch, x + w / 2 - 4, y + h / 2 - 7, 2f, Theme.alpha(0xFFFFFFFF, 0.8f), false);
            return;
        }
        double da = w / (double) h;
        int srcW = Math.min(t.w(), (int) Math.round(t.h() * da));
        int srcH = Math.min(t.h(), (int) Math.round(t.w() / da));
        float u = (t.w() - srcW) / 2f, v = (t.h() - srcH) / 2f;
        g.blit(RenderPipelines.GUI_TEXTURED, t.tex().id, x, y, u, v, w, h, srcW, srcH, t.w(), t.h());
    }

    private void chip(GuiGraphics g, int x, int y, String label, boolean on, String id) {
        int w = Theme.width(label) + 16;
        float h = hv(id);
        int bg = on ? Theme.L_WHITE : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, h);
        Theme.roundRect(g, x, y, w, 16, 8, bg);
        Theme.centered(g, label, x + w / 2, y + 4, on ? Theme.L_INK : Theme.L_TEXT, false);
        hit(x, y, w, 16, id);
        chipX = x + w + 6;
    }

    private int chipX;

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;
        long now = System.currentTimeMillis();
        float intro = Theme.ease((now - openedAt) / 250f);
        scroll += (targetScroll - scroll) * Math.min(1f, dt * 16f);

        if (dirty && now - typedAt > 450) { // debounce: one API call per pause in typing, not per keystroke
            dirty = false;
            String t = typed.trim();
            if (!t.equals(query) && (t.isEmpty() || t.length() >= 2)) {
                query = t;
                reload();
            }
        }

        Backdrop.draw(g, width, height, (now - openedAt) / 1000.0, true);
        g.fill(0, 0, width, height, Theme.alpha(0xFF0A1E5C, 0.30f * intro));

        pw = Math.min(width - 24, 840);
        ph = Math.min(height - 24, 440);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1 - intro) * 10);
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 7, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 6, Theme.L_BG);

        hits.clear();
        hot = "";
        // header
        Component title = Component.literal("Texture Packs").withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(px + 14, py + 10);
        g.pose().scale(1.15f, 1.15f);
        g.drawString(Theme.font(), title, 0, 0, Theme.L_TEXT, false);
        g.pose().popMatrix();
        Theme.text(g, "from PacksMC", px + 14 + 78, py + 12, Theme.L_DIM);
        g.fill(px + 1, py + 30, px + pw - 1, py + 31, Theme.L_LINE);

        listX = px + 10;
        listY = py + 60;
        listH = ph - 60 - 38;
        listW = (int) (pw * 0.58);
        detX = listX + listW + 10;
        detY = listY;
        detW = px + pw - 10 - detX;
        detH = listH;

        if (!PacksMcApi.hasKey()) {
            drawKeySetup(g);
        } else {
            drawSearch(g, mx, my);
            int cx = px + 12;
            chipX = cx;
            for (int i = 0; i < SORTS.length; i++) chip(g, chipX, py + 38, SORT_LABELS[i], i == sortIdx, "sort:" + i);
            chipX += 8;
            for (int i = 0; i < RES.length; i++) chip(g, chipX, py + 38, RES[i].isEmpty() ? "Any size" : RES[i], i == resIdx, "res:" + i);
            drawGrid(g, mx, my);
            drawDetails(g);
        }
        drawFooter(g);

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(mx, my, h.x, h.y, h.w, h.h)) {
                hot = h.id;
                break;
            }
        }
    }

    private void drawSearch(GuiGraphics g, int mx, int my) {
        searchW = Math.max(110, Math.min(180, pw / 4));
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
        if (typed.isEmpty() && !focused) Theme.text(g, "Search packs", searchX + 18, searchY + 4, Theme.L_DIM2);
        hit(searchX, searchY, searchW, 16, "search");
    }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        Theme.roundRect(g, listX - 1, listY - 1, listW + 2, listH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, listX, listY, listW, listH, 5, 0xFF0C0F17);

        cols = Math.max(1, (listW - 10 + GAP) / (MIN_CARD_W + GAP));
        cardW = (listW - 10 - GAP * (cols - 1)) / cols;
        thumbH = cardW * 9 / 16;
        cardH = thumbH + 30;
        int rows = (packs.size() + cols - 1) / cols;
        int content = rows * (cardH + GAP) + GAP + (loading ? 18 : 0);
        float max = Math.max(0, content - listH);
        targetScroll = Math.max(0, Math.min(max, targetScroll));

        if (packs.isEmpty()) {
            int cy = listY + listH / 2 - 8;
            if (error != null) {
                Theme.centered(g, Theme.fit(error, listW - 24), listX + listW / 2, cy - 8, Theme.BAD, false);
                Theme.button(g, listX + listW / 2 - 32, cy + 6, 64, 18, "Try again", true, hv("retry") > 0.5f);
                hit(listX + listW / 2 - 32, cy + 6, 64, 18, "retry");
                hv("retry");
            } else {
                Theme.centered(g, loading ? "Loading packs..." : "No packs match.", listX + listW / 2, cy, Theme.L_DIM, false);
            }
            return;
        }

        g.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < packs.size(); i++) {
            int x = listX + 5 + (i % cols) * (cardW + GAP);
            int y = listY + 5 + (i / cols) * (cardH + GAP) - Math.round(scroll);
            if (y + cardH < listY || y > listY + listH) continue;
            PackInfo p = packs.get(i);
            String id = "card:" + i;
            float h = hv(id);
            boolean sel = selected != null && selected.id().equals(p.id());
            int lift = Math.round(h);
            Theme.roundRect(g, x - 1, y - lift - 1, cardW + 2, cardH + 2, 6, sel ? Theme.L_WHITE : Theme.lerp(Theme.L_LINE, Theme.L_BORDER_HI, h));
            Theme.roundRect(g, x, y - lift, cardW, cardH, 5, Theme.lerp(Theme.L_CARD, Theme.L_CARD_HI, h));
            drawImage(g, p.thumbnailUrl(), x + 3, y - lift + 3, cardW - 6, thumbH - 3, p);
            int ty = y - lift + thumbH + 3;
            Theme.text(g, Theme.fit(p.title(), cardW - 10), x + 5, ty, Theme.L_TEXT);
            String meta = (p.author().isEmpty() ? "" : "by " + p.author() + "  ") + compact(p.downloads()) + " dl";
            Theme.text(g, Theme.fit(meta, cardW - 10), x + 5, ty + 11, Theme.L_DIM);
            if (!p.resolution().isEmpty()) {
                int rw = Theme.width(p.resolution()) + 8;
                Theme.roundRect(g, x + cardW - rw - 6, y - lift + 6, rw, 12, 6, Theme.alpha(0xFF000000, 0.6f));
                Theme.text(g, p.resolution(), x + cardW - rw - 2, y - lift + 8, Theme.L_TEXT);
            }
            if (Theme.in(mx, my, listX, listY, listW, listH)) hit(x, y, cardW, cardH, id);
        }
        if (loading) Theme.centered(g, "Loading...", listX + listW / 2, listY + listH - 14, Theme.L_DIM, false);
        g.disableScissor();

        if (max > 0) {
            int knob = Math.max(16, (int) (listH * (listH / (float) content)));
            int ky = listY + (int) ((listH - knob) * (scroll / max));
            Theme.roundRect(g, listX + listW - 5, ky, 3, knob, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
        if (!loading && !endReached && error == null && max - scroll < 140) fetchPage(); // endless scrolling
    }

    private void drawDetails(GuiGraphics g) {
        Theme.roundRect(g, detX - 1, detY - 1, detW + 2, detH + 2, 6, Theme.L_LINE);
        Theme.roundRect(g, detX, detY, detW, detH, 5, 0xFF0C0F17);
        PackInfo p = shownDetail();
        if (p == null) {
            Theme.centered(g, "Select a pack to see it here.", detX + detW / 2, detY + detH / 2 - 4, Theme.L_DIM, false);
            return;
        }
        int x = detX + 10, w = detW - 20;
        int imgH = Math.min(w * 9 / 16, detH * 2 / 5);
        List<String> gal = p.gallery();
        String img = gal.isEmpty() ? p.thumbnailUrl() : gal.get(Math.floorMod(galleryIdx, gal.size()));
        drawImage(g, img, x, detY + 10, w, imgH, p);
        if (gal.size() > 1) {
            arrow(g, x + 4, detY + 10 + imgH / 2 - 8, "<", "gprev");
            arrow(g, x + w - 20, detY + 10 + imgH / 2 - 8, ">", "gnext");
            String n = (Math.floorMod(galleryIdx, gal.size()) + 1) + " / " + gal.size();
            Theme.roundRect(g, x + w / 2 - Theme.width(n) / 2 - 5, detY + 10 + imgH - 15, Theme.width(n) + 10, 12, 6, Theme.alpha(0xFF000000, 0.6f));
            Theme.centered(g, n, x + w / 2, detY + 10 + imgH - 13, Theme.L_TEXT, false);
        }

        int y = detY + 10 + imgH + 8;
        Component name = Component.literal(Theme.fit(p.title(), w)).withStyle(ChatFormatting.BOLD);
        Theme.scaledText(g, name.getString(), x, y, 1.1f, Theme.L_TEXT, false);
        y += 13;
        String by = p.author().isEmpty() ? "Unknown creator" : "by " + p.author();
        Theme.text(g, by, x, y, Theme.L_SOFT);
        if (p.authorVerified()) Icons.draw(g, Icons.CHECK, x + Theme.width(by) + 5, y, 1, Theme.ACCENT2);
        y += 13;
        y = info(g, "Size", String.join(" · ", nonEmpty(p.resolution(), size(p.sizeBytes()))), x, y, w);
        y = info(g, "For", String.join(", ", p.availableVersions().isEmpty() ? p.mcVersions() : p.availableVersions()), x, y, w);
        y = info(g, "Stats", compact(p.downloads()) + " downloads · " + compact(p.likes()) + " likes", x, y, w);
        y = info(g, "License", p.licenseLabel() + (p.requiresCredit() ? " · credit required" : ""), x, y, w);

        int btnY = detY + detH - 26 - (waiting ? 12 : 0);
        int descTop = y + 4;
        int descBottom = btnY - 6;
        if (descBottom > descTop + 10) {
            List<String> lines = wrap(p.description().isEmpty() ? "No description." : p.description(), w);
            g.enableScissor(detX, descTop, detX + detW, descBottom);
            for (int i = 0; i < lines.size(); i++) {
                int yy = descTop + i * 10 - Math.round(descScroll);
                if (yy > descBottom) break;
                Theme.text(g, lines.get(i), x, yy, 0xFFC2C7DC);
            }
            g.disableScissor();
            descScroll = Math.max(0, Math.min(Math.max(0, lines.size() * 10 - (descBottom - descTop)), descScroll));
        }

        int dlW = (int) (w * 0.62);
        float dh = hv("dl");
        Theme.roundRect(g, x, btnY, dlW, 20, 5, Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.2f * dh));
        Theme.centered(g, p.exclusive() ? "Get on PacksMC" : "Download", x + dlW / 2, btnY + 6, 0xFFFFFFFF, false);
        hit(x, btnY, dlW, 20, "dl");
        int opW = w - dlW - 6;
        Theme.button(g, x + dlW + 6, btnY, opW, 20, "Open page", false, hv("open") > 0.5f);
        hit(x + dlW + 6, btnY, opW, 20, "open");
        if (waiting) {
            Theme.text(g, Theme.fit("Waiting for the download in " + PacksSettings.downloadsDir().getFileName() + "...", w), x, btnY + 24, Theme.WARN);
        }
    }

    private void arrow(GuiGraphics g, int x, int y, String label, String id) {
        Theme.roundRect(g, x, y, 16, 16, 8, Theme.alpha(0xFF000000, 0.35f + 0.3f * hv(id)));
        Theme.centered(g, label, x + 8, y + 4, Theme.L_TEXT, false);
        hit(x, y, 16, 16, id);
    }

    private static List<String> nonEmpty(String... s) {
        List<String> out = new ArrayList<>();
        for (String v : s) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    private int info(GuiGraphics g, String label, String value, int x, int y, int w) {
        if (value == null || value.isBlank()) return y;
        Theme.text(g, label, x, y, Theme.L_DIM2);
        Theme.text(g, Theme.fit(value, w - 46), x + 44, y, Theme.L_TEXT);
        return y + 11;
    }

    private void drawFooter(GuiGraphics g) {
        int fy = py + ph - 28;
        g.fill(px + 1, fy - 1, px + pw - 1, fy, Theme.L_LINE);
        long age = System.currentTimeMillis() - toastAt;
        String msg;
        int color;
        if (!toast.isEmpty() && age < 9000) {
            msg = toast;
            color = toastColor;
        } else if (error != null && !packs.isEmpty()) {
            msg = error;
            color = Theme.BAD;
        } else {
            msg = "Drop a resource pack .zip onto this window to install it.";
            color = Theme.L_DIM2;
        }
        Theme.text(g, Theme.fit(msg, pw - 150), px + 14, fy + 10, color);
        int doneW = 64;
        int doneX = px + pw - doneW - 10;
        Theme.roundRect(g, doneX, fy + 5, doneW, 18, 5, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv("done")));
        Theme.centered(g, "Done", doneX + doneW / 2, fy + 10, Theme.L_TEXT, false);
        hit(doneX, fy + 5, doneW, 18, "done");
    }

    private void drawKeySetup(GuiGraphics g) {
        int cw = Math.min(pw - 40, 420);
        int cx = px + (pw - cw) / 2;
        int cy = py + 50;
        Theme.roundRect(g, cx, cy, cw, 190, 6, Theme.L_CARD);
        Theme.text(g, "Connect PacksMC", cx + 14, cy + 12, Theme.L_TEXT);
        List<String> lines = wrap("PacksMC's catalog needs a free API key. Any account works, no Packs+ needed. "
                + "Sign in at packsmc.com, open API Access, create a key and paste it below. "
                + "It is saved only on this PC (config/lone-packsmc.json).", cw - 28);
        for (int i = 0; i < lines.size(); i++) Theme.text(g, lines.get(i), cx + 14, cy + 30 + i * 10, Theme.L_SOFT);

        int by = cy + 30 + lines.size() * 10 + 10;
        boolean focused = keyBox.isFocused();
        Theme.roundRect(g, cx + 13, by - 1, cw - 26, 20, 5, focused ? Theme.alpha(Theme.ACCENT, 0.7f) : Theme.L_LINE);
        Theme.roundRect(g, cx + 14, by, cw - 28, 18, 4, 0xFF0C0F17);
        keyBox.setX(cx + 20);
        keyBox.setY(by + 5);
        keyBox.setWidth(cw - 40);
        keyBox.setHeight(8);
        keyBox.render(g, 0, 0, 0f);
        if (keyTyped.isEmpty() && !focused) Theme.text(g, "pmc_...", cx + 20, by + 5, Theme.L_DIM2);
        hit(cx + 14, by, cw - 28, 18, "keybox");

        int ry = by + 28;
        int sw = 96;
        Theme.button(g, cx + 14, ry, 110, 20, "Get a free key", false, hv("getkey") > 0.5f);
        hit(cx + 14, ry, 110, 20, "getkey");
        Theme.button(g, cx + cw - 14 - sw, ry, sw, 20, keyBusy ? "Checking..." : "Save key", true, hv("savekey") > 0.5f);
        hit(cx + cw - 14 - sw, ry, sw, 20, "savekey");
        if (!keyError.isEmpty()) Theme.text(g, Theme.fit(keyError, cw - 28), cx + 14, ry + 28, Theme.BAD);
    }

    // ------------------------------------------------------------------ input

    private void saveKey() {
        if (keyBusy) return;
        keyBusy = true;
        keyError = "";
        PacksMcApi.verifyAndStoreKey(keyTyped).whenComplete((v, ex) -> minecraft.execute(() -> {
            keyBusy = false;
            if (ex != null) {
                keyError = PacksMcApi.message(ex);
            } else {
                keyTyped = "";
                keyBox.setValue("");
                keyBox.setFocused(false);
                reload();
            }
        }));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return super.mouseClicked(e, dbl);
        double mx = e.x(), my = e.y();
        String id = null;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (Theme.in(mx, my, h.x, h.y, h.w, h.h)) {
                id = h.id;
                break;
            }
        }
        boolean onSearch = "search".equals(id), onKey = "keybox".equals(id);
        search.setFocused(onSearch);
        keyBox.setFocused(onKey);
        if (onSearch || onKey) {
            EditBox box = onSearch ? search : keyBox;
            setFocused(box);
            box.mouseClicked(e, dbl);
            return true;
        }
        if (getFocused() == search || getFocused() == keyBox) setFocused(null);
        if (id == null) return super.mouseClicked(e, dbl);

        switch (id) {
            case "done" -> onClose();
            case "retry" -> reload();
            case "getkey" -> openUri(PacksMcApi.KEY_PAGE);
            case "savekey" -> saveKey();
            case "dl" -> download(selected);
            case "open" -> {
                PackInfo p = selected;
                if (p != null && PacksMcApi.isPacksMcUrl(p.downloadUrl())) openUri(p.downloadUrl());
            }
            case "gprev" -> galleryIdx--;
            case "gnext" -> galleryIdx++;
            default -> {
                if (id.startsWith("sort:")) {
                    int i = Integer.parseInt(id.substring(5));
                    if (i != sortIdx) {
                        sortIdx = i;
                        reload();
                    }
                } else if (id.startsWith("res:")) {
                    int i = Integer.parseInt(id.substring(4));
                    if (i != resIdx) {
                        resIdx = i;
                        reload();
                    }
                } else if (id.startsWith("card:")) {
                    int i = Integer.parseInt(id.substring(5));
                    if (i >= 0 && i < packs.size()) select(packs.get(i));
                }
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (Theme.in(mx, my, detX, detY, detW, detH)) {
            descScroll = Math.max(0, descScroll - (float) sy * 20f);
            return true;
        }
        if (Theme.in(mx, my, listX, listY, listW, listH)) {
            targetScroll = Math.max(0, targetScroll - (float) sy * 34f);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
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
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && keyBox.isFocused()) {
            saveKey();
            return true;
        }
        if (k == GLFW.GLFW_KEY_F && (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && PacksMcApi.hasKey()) {
            search.setFocused(true);
            setFocused(search);
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        for (Path p : paths) {
            if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                say(p.getFileName() + " isn't a .zip.", Theme.BAD);
                continue;
            }
            say("Installing " + p.getFileName() + "...", Theme.L_TEXT);
            PackImporter.install(p, PacksSettings.autoActivate()).thenAccept(r -> minecraft.execute(() -> onInstalled(r)));
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
