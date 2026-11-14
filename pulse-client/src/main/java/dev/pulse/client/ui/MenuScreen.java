package dev.pulse.client.ui;

import dev.pulse.client.Friends;
import dev.pulse.client.PulseConfig;
import dev.pulse.client.cosmetics.Cosmetic;
import dev.pulse.client.cosmetics.CosmeticCategory;
import dev.pulse.client.cosmetics.CosmeticRegistry;
import dev.pulse.client.cosmetics.CosmeticsManager;
import dev.pulse.client.module.Category;
import dev.pulse.client.module.Module;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.CapeModule;
import dev.pulse.client.module.impl.ClientModule;
import dev.pulse.client.module.impl.SpotifyModule;
import dev.pulse.client.module.impl.ThemeModule;
import dev.pulse.client.module.impl.WaypointsModule;
import dev.pulse.client.module.setting.ActionSetting;
import dev.pulse.client.module.setting.BoolSetting;
import dev.pulse.client.module.setting.ChoiceSetting;
import dev.pulse.client.module.setting.ColorSetting;
import dev.pulse.client.module.setting.KeybindSetting;
import dev.pulse.client.module.setting.Setting;
import dev.pulse.client.module.setting.SliderSetting;
import dev.pulse.client.skin.SkinLibrary;
import dev.pulse.client.skin.SkinTex;
import dev.pulse.client.skin.SkinUploader;
import dev.pulse.client.spotify.SpotifyClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The Lone Client menu: a sidebar (modules by category, client pages), a search bar and module cards.
 * Click a card to open its settings; every module has its own fully editable settings page.
 */
public class MenuScreen extends Screen {
    public enum Page {
        FAVORITES("Favorites"), ALL("All"), HUD("HUD"), VISUAL("Visual"), GAMEPLAY("Gameplay"), UTILITY("Utility"),
        SETTINGS("Settings"), FRIENDS("Friends"), COSMETICS("Cosmetics"), SKINS("Skins"), PROFILES("Profiles");

        public final String title;

        Page(String title) {
            this.title = title;
        }

        Category category() {
            return switch (this) {
                case HUD -> Category.HUD;
                case VISUAL -> Category.VISUAL;
                case GAMEPLAY -> Category.GAMEPLAY;
                case UTILITY -> Category.UTILITY;
                default -> null;
            };
        }

        boolean isModuleList() {
            return this == FAVORITES || this == ALL || category() != null;
        }
    }

    private interface Drag {
        void apply(double mx, double my);
    }

    private static final class Zone {
        final int x, y, w, h;
        final String id;
        final boolean clipped;
        String group;
        boolean silent;
        IntConsumer click;
        Drag drag;
        IntConsumer wheel;

        Zone(int x, int y, int w, int h, String id, boolean clipped) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.id = id;
            this.clipped = clipped;
        }
    }

    private static Page lastPage = Page.HUD;

    private static final int HEAD = 26, ROWH = 26, COLOR_H = 88;

    private final Screen parent;
    private final List<Zone> zones = new ArrayList<>();
    private final Map<String, Float> hvMap = new HashMap<>();
    private Page page;
    private Module detail;
    private String hot = "", hotGroup = "";
    private Zone activeDrag;
    private ColorSetting expandedColor;
    private KeybindSetting listening;
    private String query = "";
    private EditBox search, input;
    private float scroll, targetScroll, contentH;
    private float dt = 0.016f;
    private long lastNs = System.nanoTime();
    private final long openedAt = System.currentTimeMillis();
    private boolean openSoundDone;
    private String toast = "";
    private long toastAt;

    // frame layout
    private int px, py, pw, ph, sb, cx, cy, cw, ch;
    private int viewX, viewY, viewW, viewH;
    private int searchX, searchY, searchW, searchH;
    private int inputX, inputY, inputW, inputH;
    private boolean inputShown;

    public MenuScreen(Screen parent) {
        this(parent, lastPage);
    }

    public MenuScreen(Screen parent, Page page) {
        super(Component.literal("Lone Client"));
        this.parent = parent;
        this.page = page;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        search = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Search modules"));
        search.setBordered(false);
        search.setMaxLength(32);
        search.setTextColor(Theme.L_TEXT);
        search.setResponder(s -> {
            query = s;
            scroll = targetScroll = 0;
            if (!s.isEmpty()) detail = null;
        });
        search.setValue(query);
        addWidget(search);

        input = new EditBox(Theme.font(), 0, 0, 100, 8, Component.literal("Name"));
        input.setBordered(false);
        input.setMaxLength(16);
        input.setTextColor(Theme.L_TEXT);
        addWidget(input);
    }

    private ClientModule client() {
        return ModuleManager.client();
    }

    private float animSpeed() {
        ClientModule c = client();
        return c == null ? 1f : c.animSpeed();
    }

    // ------------------------------------------------------------------ zones / hover

    private Zone zone(int x, int y, int w, int h, String id, boolean clipped, IntConsumer click) {
        Zone z = new Zone(x, y, w, h, id, clipped);
        z.click = click;
        zones.add(z);
        return z;
    }

    private Zone pick(double mx, double my) {
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (!Theme.in(mx, my, z.x, z.y, z.w, z.h)) continue;
            if (z.clipped && !Theme.in(mx, my, viewX, viewY, viewW + 4, viewH)) continue;
            return z;
        }
        return null;
    }

    private float hv(String id) {
        float cur = hvMap.getOrDefault(id, 0f);
        float target = id.equals(hot) || id.equals(hotGroup) ? 1f : 0f;
        cur += (target - cur) * Math.min(1f, dt * 16f * animSpeed());
        hvMap.put(id, cur);
        return cur;
    }

    // ------------------------------------------------------------------ text helpers

    private void bold(GuiGraphics g, String s, int x, int y, float scale, int color) {
        Component c = Component.literal(s).withStyle(ChatFormatting.BOLD);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(Theme.font(), c, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private int boldWidth(String s, float scale) {
        return (int) (Theme.font().width(Component.literal(s).withStyle(ChatFormatting.BOLD)) * scale);
    }

    private void small(GuiGraphics g, String s, int x, int y, int color) {
        Theme.scaledText(g, s, x, y, 0.8f, color, false);
    }

    private void notify(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long nowNs = System.nanoTime();
        dt = Math.min(0.1f, (nowNs - lastNs) / 1e9f);
        lastNs = nowNs;

        // hover from last frame's zones
        Zone hz = activeDrag != null ? activeDrag : pick(mx, my);
        String newHot = hz == null ? "" : hz.id;
        hot = newHot;
        hotGroup = hz == null || hz.group == null ? "" : hz.group;
        zones.clear();
        inputShown = false;

        scroll += (targetScroll - scroll) * Math.min(1f, dt * 16f);

        float intro = Theme.ease((System.currentTimeMillis() - openedAt) / 260f);
        if (!openSoundDone) {
            openSoundDone = true;
        }

        // backdrop
        ClientModule cm = client();
        float dim = cm == null ? 0.55f : cm.dim();
        if (minecraft.level == null) {
            double t = (System.currentTimeMillis() - openedAt) / 1000.0;
            Backdrop.draw(g, width, height, t, false);
            g.fill(0, 0, width, height, Theme.alpha(Theme.L_SCRIM, 0.45f * intro));
        } else {
            g.fill(0, 0, width, height, Theme.alpha(Theme.L_SCRIM, dim * intro));
        }

        pw = Math.min(width - 12, 800);
        ph = Math.min(height - 12, 460);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1 - intro) * 10);
        sb = Math.max(112, Math.min(148, pw / 4));

        for (int i = 4; i >= 1; i--) { // soft, neutral drop shadow
            Theme.roundRect(g, px - 1 - i, py - 1 - i + 3, pw + 2 + 2 * i, ph + 2 + 2 * i, 8 + i, Theme.alpha(0xFF000000, 0.06f * intro));
        }
        Theme.roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 9, Theme.L_LINE);
        Theme.roundRect(g, px, py, pw, ph, 8, Theme.L_BG);
        Theme.roundRect(g, px, py, sb, ph, 8, Theme.L_SIDE);
        g.fill(px + 8, py, px + sb, py + ph, Theme.L_SIDE);
        g.fill(px + sb, py + 1, px + sb + 1, py + ph - 1, Theme.L_LINE);
        g.fill(px + 1, py + HEAD, px + pw - 1, py + HEAD + 1, Theme.L_LINE);

        drawSidebar(g, mx, my);
        drawTopBar(g, mx, my);
        drawContent(g, mx, my);
        drawToast(g);

        if (search != null && !inputShown) input.setFocused(false);
    }

    private void drawToast(GuiGraphics g) {
        long age = System.currentTimeMillis() - toastAt;
        if (toast.isEmpty() || age > 2200) return;
        float a = age < 2000 ? 1f : 1f - (age - 2000) / 200f;
        int tw = Theme.width(toast) + 20;
        int x = px + pw / 2 - tw / 2, y = py + ph - 30;
        Theme.roundRect(g, x, y, tw, 18, 5, Theme.alpha(Theme.L_CARD_HI, a));
        Theme.roundRect(g, x, y, 2, 18, 1, Theme.alpha(Theme.ACCENT, a));
        Theme.text(g, toast, x + 10, y + 5, Theme.alpha(Theme.L_TEXT, a));
    }

    // ------------------------------------------------------------------ sidebar

    private void setPage(Page p) {
        page = p;
        lastPage = p;
        detail = null;
        expandedColor = null;
        listening = null;
        scroll = targetScroll = 0;
        if (!query.isEmpty()) search.setValue("");
        input.setValue("");
    }

    private void openDetail(Module m) {
        detail = m;
        expandedColor = null;
        listening = null;
        scroll = targetScroll = 0;
        m.syncPosition();
    }

    private int countFor(Page p) {
        return switch (p) {
            case FAVORITES -> (int) ModuleManager.listed().stream().filter(m -> m.favorite).count();
            case ALL -> ModuleManager.listed().size();
            case FRIENDS -> Friends.all().size();
            default -> p.category() == null ? -1 : ModuleManager.byCategory(p.category()).size();
        };
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        bold(g, "Lone Client", px + 12, py + 9, 1.1f, Theme.L_TEXT);

        int profileH = 38;
        int avail = ph - HEAD - profileH - 4;
        int step = Math.max(15, Math.min(21, (avail - 2 * 18) / 11));
        int rh = step - 2;
        int x = px + 8, w = sb - 16;
        int y = py + HEAD + 8;

        sectionLabel(g, "MODULES", y);
        y += 16;
        Page[] mods = {Page.FAVORITES, Page.ALL, Page.HUD, Page.VISUAL, Page.GAMEPLAY, Page.UTILITY};
        String[][] icons = {Icons.STAR, Icons.ALL, Icons.HUD, Icons.EYE, Icons.TARGET, Icons.TOOL};
        for (int i = 0; i < mods.length; i++) {
            Page p = mods[i];
            navItem(g, "nav:" + p.name(), x, y, w, rh, icons[i], p.title, countFor(p) >= 0 ? String.valueOf(countFor(p)) : "",
                    page == p && query.isEmpty(), () -> setPage(p));
            y += step;
        }
        y += 6;
        sectionLabel(g, "CLIENT", y);
        y += 16;
        Page[] cl = {Page.SETTINGS, Page.FRIENDS, Page.COSMETICS, Page.SKINS};
        String[][] cicons = {Icons.GEAR, Icons.PERSON, Icons.SHIRT, Icons.FACE};
        for (int i = 0; i < cl.length; i++) {
            Page p = cl[i];
            navItem(g, "nav:" + p.name(), x, y, w, rh, cicons[i], p.title, "", page == p && query.isEmpty(), () -> setPage(p));
            y += step;
        }
        navItem(g, "nav:EDITOR", x, y, w, rh, Icons.LAYOUT, "HUD Editor", "", false, () -> {
            PulseConfig.save();
            minecraft.setScreen(new HudEditorScreen(this));
        });

        // profile card
        int pyy = py + ph - profileH;
        g.fill(px + 8, pyy, px + sb - 8, pyy + 1, Theme.L_LINE);
        String pid = "nav:PROFILES";
        float hv = hv(pid);
        Theme.roundRect(g, px + 8, pyy + 5, sb - 16, profileH - 11, 5,
                page == Page.PROFILES ? Theme.alpha(0xFFFFFFFF, 0.10f) : Theme.alpha(0xFFFFFFFF, 0.06f * hv));
        String name = minecraft.getUser() == null ? "Player" : minecraft.getUser().getName();
        Theme.roundRect(g, px + 13, pyy + 9, 20, 20, 4, Theme.lerp(Theme.L_TILE_HI, Theme.ACCENT, 0.5f * hv));
        Theme.centered(g, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), px + 23, pyy + 15, Theme.L_TEXT, false);
        Theme.text(g, Theme.fit(name, sb - 56), px + 39, pyy + 10, Theme.L_TEXT);
        g.fill(px + 39, pyy + 23, px + 42, pyy + 26, Theme.GOOD);
        Theme.text(g, Theme.fit(PulseConfig.profile(), sb - 66), px + 45, pyy + 21, Theme.L_DIM);
        zone(px + 8, pyy + 5, sb - 16, profileH - 11, pid, false, b -> setPage(Page.PROFILES)).silent = true;
    }

    private void sectionLabel(GuiGraphics g, String s, int y) {
        small(g, s, px + 12, y, Theme.L_DIM2);
        int tw = (int) (Theme.width(s) * 0.8f);
        g.fill(px + 18 + tw, y + 3, px + sb - 12, y + 4, Theme.L_LINE);
    }

    private void navItem(GuiGraphics g, String id, int x, int y, int w, int h, String[] icon, String label,
                         String count, boolean active, Runnable action) {
        float hv = hv(id);
        int ox = Math.round(hv * 2);
        if (active) {
            Theme.roundRect(g, x, y, w, h, 6, Theme.L_CARD_HI);
            Theme.roundRect(g, x + 2, y + 4, 2, h - 8, 1, Theme.ACCENT);
        } else if (hv > 0.01f) {
            Theme.roundRect(g, x, y, w, h, 6, Theme.alpha(Theme.L_CARD_HI, hv));
        }
        int ink = active ? Theme.L_TEXT : Theme.lerp(Theme.L_DIM, Theme.L_TEXT, hv);
        Icons.draw(g, icon, x + 7 + ox, y + (h - 9) / 2, 1, ink);
        Theme.text(g, label, x + 22 + ox, y + (h - 8) / 2, ink);
        if (!count.isEmpty()) {
            int cc = active ? Theme.L_DIM : Theme.L_DIM2;
            Theme.text(g, count, x + w - 8 - Theme.width(count), y + (h - 8) / 2, cc);
        }
        zone(x, y, w, h, id, false, b -> action.run()).silent = true;
    }

    // ------------------------------------------------------------------ top bar

    private String[] iconForPage() {
        return switch (page) {
            case FAVORITES -> Icons.STAR;
            case ALL -> Icons.ALL;
            case HUD -> Icons.HUD;
            case VISUAL -> Icons.EYE;
            case GAMEPLAY -> Icons.TARGET;
            case UTILITY -> Icons.TOOL;
            case SETTINGS -> Icons.GEAR;
            case FRIENDS -> Icons.PERSON;
            case COSMETICS -> Icons.SHIRT;
            case SKINS -> Icons.FACE;
            case PROFILES -> Icons.PERSON;
        };
    }

    private List<Module> modulesForPage() {
        List<Module> out = new ArrayList<>();
        if (!query.isEmpty()) {
            String q = query.toLowerCase(Locale.ROOT);
            for (Module m : ModuleManager.listed()) {
                boolean hit = m.name.toLowerCase(Locale.ROOT).contains(q)
                        || m.description.toLowerCase(Locale.ROOT).contains(q)
                        || m.category.label.toLowerCase(Locale.ROOT).contains(q);
                if (!hit) for (Setting s : m.settings) if (s.name.toLowerCase(Locale.ROOT).contains(q)) { hit = true; break; }
                if (hit) out.add(m);
            }
            return out;
        }
        if (page == Page.FAVORITES) {
            for (Module m : ModuleManager.listed()) if (m.favorite) out.add(m);
        } else if (page == Page.ALL) {
            out.addAll(ModuleManager.listed());
        } else if (page.category() != null) {
            out.addAll(ModuleManager.byCategory(page.category()));
        }
        return out;
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        int x = px + sb + 14;
        String title;
        String sub = "";
        String[] icon = iconForPage();
        if (!query.isEmpty()) {
            title = "Search";
            sub = modulesForPage().size() + " result" + (modulesForPage().size() == 1 ? "" : "s");
            icon = Icons.SEARCH;
        } else if (page.isModuleList()) {
            List<Module> l = modulesForPage();
            int on = 0;
            for (Module m : l) if (m.enabled) on++;
            title = page.title;
            sub = on + " of " + l.size() + " on";
        } else if (page == Page.FRIENDS) {
            title = "Friends";
            sub = Friends.all().size() + " saved";
        } else if (page == Page.PROFILES) {
            title = "Profiles";
            sub = "active: " + PulseConfig.profile();
        } else {
            title = page.title;
        }
        Icons.draw(g, icon, x, py + 9, 1, Theme.L_TEXT);
        bold(g, title, x + 14, py + 9, 1.0f, Theme.L_TEXT);
        if (!sub.isEmpty()) Theme.text(g, sub, x + 14 + boldWidth(title, 1.0f) + 8, py + 9, Theme.L_DIM);

        // close button
        int cxx = px + pw - 24, cyy = py + 5;
        float hv = hv("close");
        Theme.roundRect(g, cxx, cyy, 16, 16, 4, Theme.lerp(0x00000000, Theme.alpha(0xFFF87171, 0.25f), hv));
        Icons.draw(g, Icons.CLOSE, cxx + 4, cyy + 4, 1, Theme.lerp(Theme.L_TEXT, 0xFFF87171, hv));
        zone(cxx, cyy, 16, 16, "close", false, b -> onClose()).silent = true;

        // search field
        searchW = Math.max(90, Math.min(170, pw - sb - 160));
        if (pw - sb < 260) searchW = 0;
        if (searchW > 0) {
            searchH = 16;
            searchX = cxx - 8 - searchW;
            searchY = py + 5;
            boolean focused = search.isFocused();
            float sh = hv("search");
            Theme.roundRect(g, searchX - 1, searchY - 1, searchW + 2, searchH + 2, 5,
                    focused ? Theme.L_BORDER_HI : Theme.lerp(Theme.L_LINE, Theme.L_BORDER_HI, sh * 0.6f));
            Theme.roundRect(g, searchX, searchY, searchW, searchH, 4, Theme.L_INSET);
            Icons.draw(g, Icons.SEARCH, searchX + 5, searchY + 4, 1, focused ? Theme.L_TEXT : Theme.L_DIM);
            search.setX(searchX + 18);
            search.setY(searchY + 4);
            search.setWidth(searchW - 24 - (searchW > 120 ? 30 : 0));
            search.setHeight(8);
            search.render(g, mx, my, 0f);
            if (query.isEmpty()) {
                if (!focused) Theme.text(g, "Search modules", searchX + 18, searchY + 4, Theme.L_DIM2);
                if (searchW > 120) Theme.text(g, "Ctrl F", searchX + searchW - 6 - Theme.width("Ctrl F"), searchY + 4, Theme.L_DIM2);
            }
            zone(searchX, searchY, searchW, searchH, "search", false, b -> {
            }).silent = true;
        }
    }

    // ------------------------------------------------------------------ content shell

    private void drawContent(GuiGraphics g, int mx, int my) {
        cx = px + sb + 10;
        cy = py + HEAD + 8;
        cw = pw - sb - 20;
        ch = ph - HEAD - 16;
        Theme.roundRect(g, cx - 1, cy - 1, cw + 2, ch + 2, 8, Theme.L_LINE);
        Theme.roundRect(g, cx, cy, cw, ch, 7, Theme.L_INSET);

        boolean searching = !query.isEmpty() && detail == null;
        boolean settingsView = detail != null || (page == Page.SETTINGS && !searching);
        int headH = settingsView ? 38 : 0;
        viewX = cx + 8;
        viewY = cy + 8 + headH;
        viewW = cw - 16 - 4;
        viewH = ch - 16 - headH;

        if (settingsView) {
            Module m = detail != null ? detail : client();
            drawDetailHeader(g, m, mx, my);
        }

        g.enableScissor(viewX - 2, viewY, viewX + viewW + 6, viewY + viewH);
        int endY;
        if (settingsView) {
            Module m = detail != null ? detail : client();
            endY = drawSettingsList(g, m, mx, my);
        } else if (searching || page.isModuleList()) {
            endY = drawModuleGrid(g, modulesForPage(), mx, my);
        } else if (page == Page.FRIENDS) {
            endY = drawFriends(g, mx, my);
        } else if (page == Page.PROFILES) {
            endY = drawProfiles(g, mx, my);
        } else if (page == Page.SKINS) {
            endY = drawSkins(g, mx, my);
        } else {
            endY = drawCosmetics(g, mx, my);
        }
        g.disableScissor();

        contentH = endY - (viewY - Math.round(scroll));
        float max = Math.max(0, contentH - viewH);
        targetScroll = Math.max(0, Math.min(max, targetScroll));
        scroll = Math.max(0, Math.min(max, scroll));
        if (max > 0) {
            int trackH = viewH;
            int knobH = Math.max(16, (int) (trackH * (viewH / contentH)));
            int knobY = viewY + (int) ((trackH - knobH) * (scroll / max));
            Theme.roundRect(g, cx + cw - 6, knobY, 3, knobH, 1, Theme.alpha(0xFFFFFFFF, 0.18f));
        }
    }

    // ------------------------------------------------------------------ module grid

    private int drawModuleGrid(GuiGraphics g, List<Module> mods, int mx, int my) {
        int y0 = viewY - Math.round(scroll);
        if (mods.isEmpty()) {
            String msg = !query.isEmpty() ? "No modules match \"" + query + "\"."
                    : page == Page.FAVORITES ? "No favorites yet. Click the star on a module." : "Nothing here.";
            Theme.centered(g, msg, viewX + viewW / 2, viewY + viewH / 2 - 4, Theme.L_DIM, false);
            return y0;
        }
        int cols = viewW >= 330 ? 2 : 1;
        int gx = 8, gy = 8, ch = 44;
        int w = (viewW - gx * (cols - 1)) / cols;
        int rows = (mods.size() + cols - 1) / cols;
        for (int i = 0; i < mods.size(); i++) {
            int x = viewX + (i % cols) * (w + gx);
            int y = y0 + (i / cols) * (ch + gy);
            if (y + ch < viewY - 2 || y > viewY + viewH + 2) continue;
            drawCard(g, mods.get(i), x, y, w, ch);
        }
        return y0 + rows * (ch + gy);
    }

    private void drawCard(GuiGraphics g, Module m, int x, int y, int w, int h) {
        String id = "card:" + m.id;
        float hv = hv(id);
        m.toggleAnim += ((m.enabled ? 1f : 0f) - m.toggleAnim) * Math.min(1f, dt * 14f * animSpeed());
        int yy = y;
        float lit = Math.max(hv, m.toggleAnim * 0.55f);
        Theme.roundRect(g, x, yy, w, h, 8, Theme.lerp(Theme.L_LINE, Theme.L_BORDER_HI, hv));
        Theme.roundRect(g, x + 1, yy + 1, w - 2, h - 2, 7, Theme.lerp(Theme.L_CARD, Theme.L_CARD_HI, lit));

        int nameColor = Theme.lerp(Theme.L_SOFT, Theme.L_TEXT, Math.max(m.toggleAnim, hv));
        Theme.text(g, m.name, x + 12, yy + 9, nameColor);
        Theme.text(g, Theme.fit(m.description, w - 92), x + 12, yy + 24, Theme.L_DIM);
        Theme.toggle(g, x + w - 38, yy + (h - 14) / 2, m.toggleAnim);

        // star
        int sx = x + w - 38 - 17;
        float sh = hv("star:" + m.id);
        int starColor = m.favorite ? 0xFFFBBF24 : Theme.lerp(Theme.L_DIM2, Theme.L_TEXT, sh);
        Icons.draw(g, Icons.STAR, sx, yy + (h - 9) / 2, 1, starColor);

        Zone body = zone(x, yy, w, h, id, true, b -> {
            if (b == 1) {
                m.toggle();
                PulseConfig.save();
            } else {
                openDetail(m);
            }
        });
        body.silent = true;
        Zone star = zone(sx - 3, yy + 6, 15, h - 12, "star:" + m.id, true, b -> {
            m.favorite = !m.favorite;
            PulseConfig.save();
        });
        star.silent = true;
        star.group = id;
        Zone tog = zone(x + w - 44, yy, 44, h, "tog:" + m.id, true, b -> {
            m.toggle();
            PulseConfig.save();
        });
        tog.silent = true;
        tog.group = id;
    }

    // ------------------------------------------------------------------ detail header

    private void drawDetailHeader(GuiGraphics g, Module m, int mx, int my) {
        int x = cx + 10, y = cy + 8;
        boolean isClient = detail == null;
        int tx = x;
        if (!isClient) {
            float bh = hv("back");
            Theme.roundRect(g, x, y + 2, 20, 20, 5, Theme.lerp(Theme.L_CARD, Theme.L_CARD_HI, bh));
            Icons.draw(g, Icons.BACK, x + 6, y + 7, 1, Theme.lerp(Theme.L_DIM, Theme.L_TEXT, bh));
            zone(x, y + 2, 20, 20, "back", false, b -> {
                detail = null;
                scroll = targetScroll = 0;
                expandedColor = null;
                PulseConfig.save();
            }).silent = true;
            tx = x + 28;
        }
        bold(g, isClient ? "Client settings" : m.name, tx, y + 4, 1.05f, Theme.L_TEXT);
        String sub = isClient ? "Menu key, animations and the title screen." : m.description;
        Theme.text(g, Theme.fit(sub, cw - 200 - (tx - x)), tx, y + 16, Theme.L_DIM);

        int rx = cx + cw - 10;
        if (!isClient) {
            m.toggleAnim += ((m.enabled ? 1f : 0f) - m.toggleAnim) * Math.min(1f, dt * 14f * animSpeed());
            Theme.toggle(g, rx - 26, y + 5, m.toggleAnim);
            zone(rx - 30, y + 1, 34, 22, "head:toggle", false, b -> {
                m.toggle();
                PulseConfig.save();
            }).silent = true;
            rx -= 38;
            String fav = m.favorite ? "Favorited" : "Favorite";
            int fw = Theme.width(fav) + 26;
            smallButton(g, "head:fav", rx - fw, y + 3, fw, 18, fav, m.favorite ? 0xFFFBBF24 : Theme.L_DIM, Icons.STAR, () -> {
                m.favorite = !m.favorite;
                PulseConfig.save();
            });
            rx -= fw + 6;
        } else {
            smallButton(g, "head:mc", rx - 112, y + 3, 112, 18, "Minecraft options", Theme.L_TEXT, null, () -> {
                PulseConfig.save();
                minecraft.setScreen(new OptionsScreen(this, minecraft.options));
            });
            rx -= 118;
        }
        smallButton(g, "head:reset", rx - 46, y + 3, 46, 18, "Reset", Theme.L_TEXT, null, () -> {
            m.resetSettings();
            expandedColor = null;
            notify(isClient ? "Client settings reset." : m.name + " reset to defaults.");
            PulseConfig.save();
        });
        g.fill(cx + 8, cy + 36, cx + cw - 8, cy + 37, Theme.L_LINE);
    }

    private void smallButton(GuiGraphics g, String id, int x, int y, int w, int h, String label, int color,
                             String[] icon, Runnable action) {
        float hv = hv(id);
        Theme.roundRect(g, x, y, w, h, 5, Theme.lerp(Theme.L_CARD, Theme.L_CONTROL_HI, hv));
        int tx = x + (w - Theme.width(label)) / 2;
        if (icon != null) {
            tx += 6;
            Icons.draw(g, icon, tx - 14, y + (h - 9) / 2, 1, color);
        }
        Theme.text(g, label, tx, y + (h - 8) / 2, Theme.lerp(color, Theme.L_TEXT, hv * 0.5f));
        zone(x, y, w, h, id, false, b -> action.run());
    }

    // ------------------------------------------------------------------ settings list

    private Map<String, List<Setting>> grouped(Module m) {
        Map<String, List<Setting>> map = new LinkedHashMap<>();
        String[] late = {"Appearance", "Position"};
        for (Setting s : m.settings) {
            if (!s.isVisible()) continue;
            String gname = s.group == null ? "General" : s.group;
            if (gname.equals("Appearance") || gname.equals("Position")) continue;
            map.computeIfAbsent(gname, k -> new ArrayList<>()).add(s);
        }
        for (String lg : late) {
            for (Setting s : m.settings) {
                if (!s.isVisible() || s.group == null || !s.group.equals(lg)) continue;
                map.computeIfAbsent(lg, k -> new ArrayList<>()).add(s);
            }
        }
        return map;
    }

    private int drawSettingsList(GuiGraphics g, Module m, int mx, int my) {
        if (detail != null && activeDrag == null) m.syncPosition();
        int y = viewY - Math.round(scroll);

        if (m instanceof CapeModule cape) y = drawCapePreview(g, cape, y);
        if (m instanceof SpotifyModule) y = drawSpotifyBlock(g, y);
        if (m instanceof WaypointsModule wp) y = drawWaypointList(g, wp, y);

        Map<String, List<Setting>> groups = grouped(m);
        if (groups.isEmpty()) {
            Theme.centered(g, "This module has no settings.", viewX + viewW / 2, y + 20, Theme.L_DIM, false);
            return y + 40;
        }
        for (Map.Entry<String, List<Setting>> e : groups.entrySet()) {
            small(g, e.getKey().toUpperCase(Locale.ROOT), viewX + 4, y + 2, Theme.L_DIM2);
            y += 14;
            int total = 0;
            for (Setting s : e.getValue()) total += rowHeight(s);
            if (y + total > viewY - 4 && y < viewY + viewH + 4) {
                Theme.roundRect(g, viewX, y, viewW, total, 8, Theme.L_CARD);
            }
            int ry = y;
            List<Setting> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                Setting s = list.get(i);
                int rh = rowHeight(s);
                if (ry + rh > viewY - 2 && ry < viewY + viewH + 2) drawSetting(g, m, s, viewX, ry, viewW, i == list.size() - 1);
                ry += rh;
            }
            y += total + 10;
        }
        return y;
    }

    private int rowHeight(Setting s) {
        return s == expandedColor ? ROWH + COLOR_H : ROWH;
    }

    private void drawSetting(GuiGraphics g, Module m, Setting s, int x, int y, int w, boolean last) {
        String id = "set:" + m.id + ":" + s.name;
        float rowHv = hv(id);
        if (rowHv > 0.01f) Theme.roundRect(g, x + 2, y + 1, w - 4, ROWH - 2, 5, Theme.alpha(0xFFFFFFFF, 0.035f * rowHv));
        Theme.text(g, Theme.fit(s.name, w / 2 - 6), x + 12, y + (ROWH - 8) / 2, Theme.lerp(Theme.L_SOFT, Theme.L_TEXT, rowHv));
        if (!last && s != expandedColor) g.fill(x + 10, y + ROWH - 1, x + w - 10, y + ROWH, Theme.alpha(Theme.L_LINE, 0.7f));
        // whole-row hover zone goes first so the controls added after it stay on top
        if (!(s instanceof BoolSetting) && !(s instanceof ColorSetting)) zone(x, y, w, ROWH, id, true, null).silent = true;

        if (s instanceof BoolSetting b) {
            b.anim += ((b.value ? 1f : 0f) - b.anim) * Math.min(1f, dt * 18f * animSpeed());
            Theme.toggle(g, x + w - 12 - 26, y + 6, b.anim);
            Zone z = zone(x, y, w, ROWH, id, true, bt -> {
                b.value = !b.value;
                PulseConfig.save();
            });
            z.silent = true;
        } else if (s instanceof SliderSetting sl) {
            drawSlider(g, sl, id, x, y, w);
        } else if (s instanceof ChoiceSetting c) {
            drawChoice(g, c, id, x, y, w);
        } else if (s instanceof ColorSetting cs) {
            drawColor(g, cs, id, x, y, w);
        } else if (s instanceof KeybindSetting kb) {
            int bw = 96, bx = x + w - 12 - bw;
            boolean on = listening == kb;
            float hv = hv(id + ":btn");
            Theme.roundRect(g, bx - 1, y + 3, bw + 2, 20, 5, on ? Theme.alpha(Theme.L_TEXT, 0.45f) : 0x00000000);
            Theme.roundRect(g, bx, y + 4, bw, 18, 4, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv));
            Theme.centered(g, on ? "Press a key..." : kb.display(), bx + bw / 2, y + 9, on ? Theme.L_TEXT : Theme.L_TEXT, false);
            Zone z = zone(bx, y + 4, bw, 18, id + ":btn", true, bt -> {
                if (bt == 1) {
                    kb.key = -1;
                    listening = null;
                } else {
                    listening = on ? null : kb;
                }
                PulseConfig.save();
            });
            z.group = id;
        } else if (s instanceof ActionSetting a) {
            int bw = Math.max(60, Theme.width(a.label) + 20), bx = x + w - 12 - bw;
            float hv = hv(id + ":btn");
            Theme.roundRect(g, bx, y + 4, bw, 18, 4, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv));
            Theme.centered(g, a.label, bx + bw / 2, y + 9, Theme.L_TEXT, false);
            Zone z = zone(bx, y + 4, bw, 18, id + ":btn", true, bt -> {
                a.run();
                PulseConfig.save();
            });
            z.group = id;
        }
    }

    private void drawSlider(GuiGraphics g, SliderSetting sl, String id, int x, int y, int w) {
        int vw = 46;
        int tw = Math.max(60, Math.min(150, w * 36 / 100));
        int tx = x + w - 12 - vw - 8 - tw, ty = y + 12;
        float hv = hv(id + ":track");
        Theme.roundRect(g, tx, ty, tw, 3, 1, Theme.L_CONTROL_HI);
        int fill = (int) Math.round(tw * sl.normalized());
        if (fill > 0) Theme.roundRect(g, tx, ty, Math.max(2, fill), 3, 1, Theme.ACCENT);
        int kw = 6 + Math.round(hv * 2);
        Theme.roundRect(g, tx + fill - kw / 2, ty - 3, kw, 9, 3, 0xFFFFFFFF);
        String val = sl.format();
        Theme.text(g, val, x + w - 12 - Theme.width(val), y + (ROWH - 8) / 2, Theme.L_SOFT);
        final int ftx = tx, ftw = tw;
        Zone z = zone(tx - 5, y, tw + 10, ROWH, id + ":track", true, b -> {
            if (b == 1) {
                sl.reset();
                PulseConfig.save();
            }
        });
        z.group = id;
        z.silent = true;
        z.drag = (dx, dy) -> {
            double before = sl.value;
            sl.setNormalized((dx - ftx) / ftw);
        };
        z.wheel = d -> {
            sl.nudge(d);
            PulseConfig.save();
        };
    }

    private void drawChoice(GuiGraphics g, ChoiceSetting c, String id, int x, int y, int w) {
        int bw = 124, bx = x + w - 12 - bw;
        float hv = hv(id + ":btn");
        Theme.roundRect(g, bx, y + 4, bw, 18, 4, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv));
        Theme.centered(g, Theme.fit(c.get(), bw - 28), bx + bw / 2, y + 9, Theme.L_TEXT, false);
        int ac = Theme.lerp(Theme.L_DIM2, Theme.L_TEXT, hv);
        Theme.text(g, "<", bx + 6, y + 9, ac);
        Theme.text(g, ">", bx + bw - 6 - Theme.width(">"), y + 9, ac);
        final int fbx = bx;
        Zone z = zone(bx, y + 4, bw, 18, id + ":btn", true, b -> {
            if (b == 1 || (b == 0 && lastMouseX < fbx + bw / 3.0)) c.cycleBack(); else c.cycle();
            PulseConfig.save();
        });
        z.group = id;
        z.wheel = d -> {
            if (d > 0) c.cycleBack(); else c.cycle();
            PulseConfig.save();
        };
    }

    private double lastMouseX;

    private void drawColor(GuiGraphics g, ColorSetting cs, String id, int x, int y, int w) {
        boolean open = cs == expandedColor;
        int sw = 28, sx = x + w - 12 - sw;
        float hv = hv(id + ":sw");
        Theme.roundRect(g, sx - 1, y + 5, sw + 2, 16, 4, open ? Theme.alpha(Theme.L_TEXT, 0.6f) : Theme.lerp(Theme.L_BORDER_HI, 0xFFFFFFFF, hv * 0.6f));
        Theme.roundRect(g, sx, y + 6, sw, 14, 3, Theme.L_LINE);
        int shown = cs.color();
        Theme.roundRect(g, sx, y + 6, sw, 14, 3, 0xFF000000 | (shown & 0xFFFFFF));
        if (cs.hasAlpha && cs.alpha < 255) {
            Theme.roundRect(g, sx, y + 6, sw / 2, 14, 3, Theme.L_LINE);
            Theme.roundRect(g, sx + sw / 2, y + 6, sw / 2, 14, 3, shown);
        }
        String lab = cs.label();
        Theme.text(g, lab, sx - 8 - Theme.width(lab), y + (ROWH - 8) / 2, Theme.L_DIM);
        Zone z = zone(x, y, w, ROWH, id + ":sw", true, b -> expandedColor = open ? null : cs);
        z.group = id;
        z.silent = false;

        if (!open) return;
        int ay = y + ROWH + 2, ax = x + 14;
        int svW = 104, svH = 60;
        // saturation / brightness square
        for (int i = 0; i < svW; i += 2) {
            float s = i / (float) (svW - 1);
            g.fillGradient(ax + i, ay, ax + i + 2, ay + svH, Theme.hsb(cs.hue, s, 1f), 0xFF000000);
        }
        int mxp = ax + Math.round(cs.sat * (svW - 1)), myp = ay + Math.round((1f - cs.val) * (svH - 1));
        g.fill(mxp - 3, myp - 3, mxp + 4, myp + 4, 0xFF000000);
        g.fill(mxp - 2, myp - 2, mxp + 3, myp + 3, 0xFFFFFFFF);
        g.fill(mxp - 1, myp - 1, mxp + 2, myp + 2, 0xFF000000 | cs.rgb);
        final int fax = ax, fay = ay;
        Zone sv = zone(ax, ay, svW, svH, id + ":sv", true, null);
        sv.silent = true;
        sv.drag = (dx, dy) -> {
            cs.setHsv(cs.hue, (float) ((dx - fax) / (svW - 1)), (float) (1.0 - (dy - fay) / (svH - 1)));
            if (cs.hasModes) cs.mode = 0;
        };
        // hue bar
        int hx = ax + svW + 8;
        for (int j = 0; j < svH; j += 2) g.fill(hx, ay + j, hx + 10, ay + j + 2, Theme.hsb(j / (float) (svH - 1) * 360f, 1f, 1f));
        int hy = ay + Math.round(cs.hue / 360f * (svH - 1));
        g.fill(hx - 2, hy - 1, hx + 12, hy + 2, 0xFF000000);
        g.fill(hx - 1, hy, hx + 11, hy + 1, 0xFFFFFFFF);
        final int fhx = hx;
        Zone hue = zone(hx, ay, 10, svH, id + ":hue", true, null);
        hue.silent = true;
        hue.drag = (dx, dy) -> {
            cs.setHsv((float) ((dy - fay) / (svH - 1) * 360.0), cs.sat == 0 && cs.val == 0 ? 1f : cs.sat, cs.val == 0 ? 1f : cs.val);
            if (cs.hasModes) cs.mode = 0;
        };
        int nextX = hx + 14;
        if (cs.hasAlpha) {
            int ax2 = hx + 16;
            g.fill(ax2, ay, ax2 + 10, ay + svH, Theme.L_LINE);
            g.fillGradient(ax2, ay, ax2 + 10, ay + svH, 0xFF000000 | cs.rgb, cs.rgb);
            int aly = ay + Math.round((1f - cs.alpha / 255f) * (svH - 1));
            g.fill(ax2 - 2, aly - 1, ax2 + 12, aly + 2, 0xFF000000);
            g.fill(ax2 - 1, aly, ax2 + 11, aly + 1, 0xFFFFFFFF);
            Zone al = zone(ax2, ay, 10, svH, id + ":alpha", true, null);
            al.silent = true;
            al.drag = (dx, dy) -> cs.alpha = Math.max(0, Math.min(255, (int) Math.round((1.0 - (dy - fay) / (svH - 1)) * 255)));
            nextX = ax2 + 14;
        }
        // presets
        int[] presets = {0xFFFFFF, 0xF87171, 0xFB923C, 0xFBBF24, 0x4ADE80, 0x22D3EE, 0x3B82F6, 0xA78BFA, 0xF472B6, 0x000000};
        int py2 = ay + svH + 6;
        for (int i = 0; i < presets.length; i++) {
            int sx2 = ax + i * 14;
            Theme.roundRect(g, sx2 - 1, py2 - 1, 13, 13, 3, Theme.L_BORDER_HI);
            Theme.roundRect(g, sx2, py2, 11, 11, 2, 0xFF000000 | presets[i]);
            final int pc = presets[i];
            zone(sx2, py2, 11, 11, id + ":p" + i, true, b -> {
                cs.setRgb(pc);
                if (cs.hasModes) cs.mode = 0;
            });
        }
        // mode chips
        if (cs.hasModes) {
            int chipW = Math.max(40, Math.min(58, (x + w - 12 - nextX) / 2 - 2));
            for (int i = 0; i < ColorSetting.MODES.length; i++) {
                int cxp = nextX + (i % 2) * (chipW + 4), cyp = ay + (i / 2) * 19;
                boolean sel = cs.mode == i;
                float ch = hv(id + ":m" + i);
                Theme.roundRect(g, cxp, cyp, chipW, 16, 4, sel ? Theme.alpha(Theme.ACCENT, 0.85f) : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, ch));
                Theme.centered(g, ColorSetting.MODES[i], cxp + chipW / 2, cyp + 4, Theme.L_TEXT, false);
                final int mi = i;
                zone(cxp, cyp, chipW, 16, id + ":m" + i, true, b -> cs.mode = mi);
            }
            String hex = String.format(Locale.ROOT, "#%06X", cs.rgb);
            Theme.text(g, hex, nextX, ay + 44, Theme.L_DIM);
            if (cs.hasAlpha) Theme.text(g, Math.round(cs.alpha / 2.55f) + "%", nextX + Theme.width(hex) + 8, ay + 44, Theme.L_DIM);
        }
    }

    // ------------------------------------------------------------------ special blocks (cape, spotify, waypoints)

    private void drawCapeWave(GuiGraphics g, int[] face, int cxp, int top, int s, double speed) {
        int ox = cxp - 5 * s;
        Theme.roundRect(g, ox - 8, top - 8, 10 * s + 16, 7, 3, Theme.L_BORDER_HI);
        double t = System.currentTimeMillis() / 600.0 * speed;
        for (int yy = 0; yy < 16; yy++) {
            float f = yy / 15f;
            for (int xx = 0; xx < 10; xx++) {
                float sway = (float) (Math.sin(t * 1.6 + yy * 0.45) * 7 * f * f + Math.sin(t * 0.9 + xx * 0.3) * 2 * f);
                float lift = (float) (Math.cos(t * 1.3 + xx * 0.5) * 1.5 * f);
                int x0 = ox + xx * s + Math.round(sway), y0 = top + yy * s + Math.round(lift);
                g.fill(x0, y0, x0 + s + 1, y0 + s + 1, face[yy * 10 + xx]);
            }
        }
    }

    private int drawCapePreview(GuiGraphics g, CapeModule cape, int y) {
        int h = 112;
        Theme.roundRect(g, viewX, y, viewW, h, 6, Theme.L_CARD);
        if (cape.face() != null) drawCapeWave(g, cape.face(), viewX + viewW / 2, y + 18, 5, cape.previewSpeed());
        Theme.centered(g, cape.equippedName(), viewX + viewW / 2, y + h - 12, Theme.L_DIM, false);
        return y + h + 10;
    }

    private int drawSpotifyBlock(GuiGraphics g, int y) {
        int h = 62;
        Theme.roundRect(g, viewX, y, viewW, h, 6, Theme.L_CARD);
        Theme.text(g, Theme.fit(SpotifyClient.statusLine(), viewW - 28), viewX + 12, y + 9, Theme.L_DIM);
        int by = y + 28, bx = viewX + 12;
        boolean conn = SpotifyClient.connected();
        bx = actionBtn(g, "sp:conn", bx, by, conn ? 78 : 62, conn ? "Disconnect" : "Connect", !conn, conn ? SpotifyClient::logout : SpotifyClient::login);
        bx = actionBtn(g, "sp:prev", bx, by, 40, "Prev", false, () -> SpotifyClient.control("previous"));
        bx = actionBtn(g, "sp:pause", bx, by, 44, "Pause", false, () -> SpotifyClient.control("pause"));
        bx = actionBtn(g, "sp:play", bx, by, 40, "Play", false, () -> SpotifyClient.control("play"));
        actionBtn(g, "sp:next", bx, by, 40, "Next", false, () -> SpotifyClient.control("next"));
        return y + h + 10;
    }

    private int actionBtn(GuiGraphics g, String id, int x, int y, int w, String label, boolean primary, Runnable r) {
        float hv = hv(id);
        int bg = primary ? Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.2f * hv) : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv);
        Theme.roundRect(g, x, y, w, 20, 4, bg);
        Theme.centered(g, label, x + w / 2, y + 6, Theme.L_TEXT, false);
        zone(x, y, w, 20, id, true, b -> r.run());
        return x + w + 6;
    }

    private int drawWaypointList(GuiGraphics g, WaypointsModule wp, int y) {
        var all = wp.all();
        int rows = Math.max(1, Math.min(8, all.size()));
        int h = 24 + rows * 20;
        Theme.roundRect(g, viewX, y, viewW, h, 6, Theme.L_CARD);
        small(g, "SAVED WAYPOINTS", viewX + 12, y + 8, Theme.L_DIM2);
        if (all.isEmpty()) {
            Theme.text(g, "None yet. Use \"Add here\" below or /pulse waypoint add <name>.", viewX + 12, y + 28, Theme.L_DIM);
        }
        for (int i = 0; i < Math.min(8, all.size()); i++) {
            var w = all.get(i);
            int ry = y + 22 + i * 20;
            Theme.text(g, Theme.fit(w.name, viewW / 3), viewX + 12, ry + 6, Theme.L_TEXT);
            String c = (int) w.x + " " + (int) w.y + " " + (int) w.z;
            Theme.text(g, c, viewX + viewW / 2 - 20, ry + 6, Theme.L_DIM);
            float hv = hv("wp:" + w.name);
            Icons.draw(g, Icons.CLOSE, viewX + viewW - 22, ry + 5, 1, Theme.lerp(Theme.L_DIM2, 0xFFF87171, hv));
            final String wn = w.name;
            zone(viewX + viewW - 26, ry, 18, 18, "wp:" + w.name, true, b -> {
                wp.remove(wn);
                notify("Removed " + wn + ".");
            });
        }
        return y + h + 10;
    }

    // ------------------------------------------------------------------ cosmetics page

    private CosmeticCategory cosCat = CosmeticCategory.CAPE;

    /** Category tabs, a live preview (hover an item to try it before wearing it) and the item grid. */
    private int drawCosmeticItems(GuiGraphics g, int y) {
        CapeModule master = ModuleManager.cape();
        small(g, "COSMETICS  " + CosmeticRegistry.count() + " ITEMS", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        int tabX = viewX;
        for (CosmeticCategory c : CosmeticCategory.values()) {
            String label = c.title + " " + CosmeticRegistry.list(c).size();
            int w = Theme.width(label) + 18;
            String id = "cos:tab:" + c.key;
            float h = hv(id);
            boolean on = c == cosCat;
            Theme.roundRect(g, tabX, y, w, 18, 9, on ? Theme.L_WHITE : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, h));
            Theme.centered(g, label, tabX + w / 2, y + 5, on ? Theme.L_INK : Theme.L_TEXT, false);
            zone(tabX, y, w, 18, id, true, b -> cosCat = c);
            tabX += w + 6;
        }
        y += 26;

        List<Cosmetic> items = CosmeticRegistry.list(cosCat);
        Cosmetic worn = CosmeticsManager.equipped(cosCat);
        Cosmetic hovered = null;
        for (Cosmetic it : items) if (("cos:" + it.id()).equals(hot)) hovered = it;
        Cosmetic shown = hovered != null ? hovered : worn;

        int tileW = 54, tileH = 66, gap = 6, prevW = 108;
        int cols = Math.max(2, (viewW - prevW - 24) / (tileW + gap));
        int rows = (items.size() + cols - 1) / cols;
        int h = Math.max(170, rows * (tileH + gap) + 12);
        Theme.roundRect(g, viewX, y, viewW, h, 6, Theme.L_CARD);
        int pcx = viewX + prevW / 2 + 6;

        // preview
        if (shown == null) {
            Theme.centered(g, "Nothing worn", pcx, y + 50, Theme.L_DIM, false);
        } else if (shown.category() == CosmeticCategory.CAPE) {
            drawCapeWave(g, shown.face(), pcx, y + 22, 5, master.previewSpeed());
        } else if (shown.category() == CosmeticCategory.WINGS) {
            Theme.roundRect(g, pcx - 24, y + 16, 48, 76, 4, Theme.L_BORDER_HI);
            for (int yy = 0; yy < 16; yy++) for (int xx = 0; xx < 10; xx++) {
                g.fill(pcx - 20 + xx * 4, y + 20 + yy * 4, pcx - 16 + xx * 4, y + 24 + yy * 4, shown.face()[yy * 10 + xx]);
            }
        } else {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 16; i++) {
                double t = (now / 1100.0 + i * 0.31) % 1.0;
                int dx = (int) (Math.sin(i * 2.1 + now / 1400.0) * 28);
                int dyy = (int) (84 - t * 84);
                g.fill(pcx + dx, y + 18 + dyy, pcx + dx + 3, y + 21 + dyy, Theme.alpha(shown.color(), (float) Math.sin(Math.PI * t)));
            }
        }
        if (shown != null) {
            Theme.centered(g, Theme.fit(shown.name(), prevW), pcx, y + 22 + 16 * 5 + 10, Theme.L_TEXT, false);
            Theme.centered(g, shown.rarity().label, pcx, y + 22 + 16 * 5 + 22, shown.rarity().color, false);
            Theme.centered(g, hovered != null && !CosmeticsManager.isEquipped(hovered) ? "Click to wear" : "Worn", pcx,
                    y + 22 + 16 * 5 + 34, Theme.L_DIM, false);
        }
        if (worn != null) actionBtn(g, "cos:off", pcx - 34, y + h - 30, 68, "Take off", false, () -> CosmeticsManager.unequip(cosCat));
        if (!master.enabled) {
            Theme.centered(g, "Cosmetics are off", pcx, y + h - 46, Theme.WARN, false);
        }
        g.fill(viewX + prevW + 12, y + 10, viewX + prevW + 13, y + h - 10, Theme.L_LINE);

        for (int i = 0; i < items.size(); i++) {
            Cosmetic it = items.get(i);
            int tx = viewX + prevW + 22 + (i % cols) * (tileW + gap);
            int ty = y + 8 + (i / cols) * (tileH + gap);
            boolean sel = CosmeticsManager.isEquipped(it);
            String id = "cos:" + it.id();
            float hv = hv(id);
            if (sel) Theme.roundRect(g, tx - 1, ty - 1, tileW + 2, tileH + 2, 6, Theme.alpha(Theme.L_TEXT, 0.75f));
            Theme.roundRect(g, tx, ty, tileW, tileH, 5, Theme.lerp(Theme.L_TILE, Theme.L_TILE_HI, hv));
            if (it.face() != null) {
                int[] face = it.face();
                for (int py2 = 0; py2 < 16; py2++) for (int px2 = 0; px2 < 10; px2++) {
                    g.fill(tx + (tileW - 20) / 2 + px2 * 2, ty + 6 + py2 * 2, tx + (tileW - 20) / 2 + px2 * 2 + 2, ty + 8 + py2 * 2, face[py2 * 10 + px2]);
                }
            } else {
                Theme.roundRect(g, tx + (tileW - 26) / 2, ty + 9, 26, 26, 13, Theme.lerp(it.color(), Theme.L_TILE, 0.25f));
                Theme.roundRect(g, tx + (tileW - 10) / 2, ty + 15, 10, 10, 5, Theme.alpha(0xFFFFFFFF, 0.35f));
            }
            Theme.centered(g, Theme.fit(it.name(), tileW - 6), tx + tileW / 2, ty + tileH - 17, sel ? Theme.L_TEXT : Theme.L_DIM, false);
            g.fill(tx + 8, ty + tileH - 6, tx + tileW - 8, ty + tileH - 4, it.rarity().color);
            zone(tx, ty, tileW, tileH, id, true, b -> {
                boolean on = CosmeticsManager.toggle(it);
                if (on && !master.enabled) master.setEnabled(true);
                PulseConfig.save();
                notify((on ? "Wearing " : "Took off ") + it.name() + ".");
            });
        }
        return y + h + 14;
    }

    private int drawCosmetics(GuiGraphics g, int mx, int my) {
        int y = viewY - Math.round(scroll);
        ThemeModule theme = ModuleManager.theme();

        y = drawCosmeticItems(g, y);

        // --- theme
        small(g, "CLIENT THEME", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        int chipW = 62, chipH = 40;
        int tcols = Math.max(2, (viewW - 16) / (chipW + 6));
        int trows = (ThemeModule.NAMES.length + tcols - 1) / tcols;
        int th = trows * (chipH + 6) + 10;
        Theme.roundRect(g, viewX, y, viewW, th, 6, Theme.L_CARD);
        for (int i = 0; i < ThemeModule.NAMES.length; i++) {
            int tx = viewX + 8 + (i % tcols) * (chipW + 6);
            int ty = y + 8 + (i / tcols) * (chipH + 6);
            boolean sel = theme.presetIndex() == i;
            String id = "theme:" + i;
            float hv = hv(id);
            if (sel) Theme.roundRect(g, tx - 1, ty - 1, chipW + 2, chipH + 2, 6, Theme.alpha(Theme.L_TEXT, 0.75f));
            Theme.roundRect(g, tx, ty, chipW, chipH, 5, Theme.lerp(Theme.L_TILE, Theme.L_TILE_HI, hv));
            int[] cols2 = i < 7 ? ThemeModule.colors(i) : new int[]{0xFFFFFFFF, 0xFF888888};
            if (i < 7) {
                Theme.roundRect(g, tx + 6, ty + 6, (chipW - 12) / 2, 14, 3, cols2[0]);
                Theme.roundRect(g, tx + 6 + (chipW - 12) / 2, ty + 6, (chipW - 12) - (chipW - 12) / 2, 14, 3, cols2[1]);
            } else {
                Icons.draw(g, Icons.PLUS, tx + chipW / 2 - 4, ty + 9, 1, Theme.L_DIM);
            }
            Theme.centered(g, ThemeModule.NAMES[i], tx + chipW / 2, ty + chipH - 14, sel ? Theme.L_TEXT : Theme.L_DIM, false);
            final int idx = i;
            zone(tx, ty, chipW, chipH, id, true, b -> {
                theme.selectPreset(idx);
                if (!theme.enabled) theme.setEnabled(true);
                PulseConfig.save();
            });
        }
        y += th + 14;

        // --- quick links
        small(g, "MORE COSMETIC SETTINGS", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        String[][] links = {{"crosshair", "Custom Crosshair"}, {"lowhealth", "Low Health Vignette"}, {"cape", "Cape options"}, {"theme", "Theme colors"}};
        int lw = (viewW - 8) / 2;
        for (int i = 0; i < links.length; i++) {
            int lx = viewX + (i % 2) * (lw + 8), ly = y + (i / 2) * 26;
            Module mod = ModuleManager.byId(links[i][0]);
            if (mod == null) continue;
            String id = "link:" + links[i][0];
            float hv = hv(id);
            Theme.roundRect(g, lx, ly, lw, 22, 5, Theme.lerp(Theme.L_CARD, Theme.L_CARD_HI, hv));
            Theme.text(g, links[i][1], lx + 12, ly + 7, Theme.lerp(Theme.L_DIM, Theme.L_TEXT, hv));
            Icons.draw(g, Icons.BACK, lx + lw - 16, ly + 6, 1, Theme.L_DIM2);
            zone(lx, ly, lw, 22, id, true, b -> {
                page = Page.VISUAL;
                lastPage = page;
                openDetail(mod);
            });
        }
        y += ((links.length + 1) / 2) * 26 + 6;
        return y;
    }

    // ------------------------------------------------------------------ friends / profiles

    private int drawInputRow(GuiGraphics g, int y, String placeholder, String buttonLabel, Runnable submit) {
        inputShown = true;
        input.setMaxLength(page == Page.SKINS ? 260 : 16);
        int bw = Math.max(54, Theme.width(buttonLabel) + 22);
        int fw = viewW - bw - 8;
        inputX = viewX;
        inputY = y;
        inputW = fw;
        inputH = 22;
        boolean focused = input.isFocused();
        Theme.roundRect(g, inputX - 1, inputY - 1, fw + 2, 24, 6, focused ? Theme.L_BORDER_HI : Theme.L_LINE);
        Theme.roundRect(g, inputX, inputY, fw, 22, 5, Theme.L_INSET);
        input.setX(inputX + 10);
        input.setY(inputY + 7);
        input.setWidth(fw - 20);
        input.setHeight(8);
        input.render(g, 0, 0, 0f);
        if (input.getValue().isEmpty() && !focused) Theme.text(g, placeholder, inputX + 10, inputY + 7, Theme.L_DIM2);
        zone(inputX, inputY, fw, 22, "input", true, b -> {
        }).silent = true;
        actionBtnWide(g, "input:go", viewX + fw + 8, y, bw, buttonLabel, submit);
        return y + 30;
    }

    private void actionBtnWide(GuiGraphics g, String id, int x, int y, int w, String label, Runnable r) {
        float hv = hv(id);
        Theme.roundRect(g, x, y, w, 22, 5, Theme.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.2f * hv));
        Theme.centered(g, label, x + w / 2, y + 7, 0xFFFFFFFF, false);
        zone(x, y, w, 22, id, true, b -> r.run());
    }

    private void submitFriend() {
        String n = input.getValue().trim();
        if (n.isEmpty()) return;
        if (Friends.add(n)) {
            notify("Added " + n + ".");
            input.setValue("");
        } else {
            notify("Use a valid, new Minecraft name.");
        }
    }

    private int drawFriends(GuiGraphics g, int mx, int my) {
        int y = viewY - Math.round(scroll);
        y = drawInputRow(g, y, "Add a friend by Minecraft name", "Add", this::submitFriend);
        var list = new ArrayList<>(Friends.all());
        if (list.isEmpty()) {
            Theme.centered(g, "No friends yet. Friends are stored on this computer.", viewX + viewW / 2, y + 30, Theme.L_DIM, false);
            return y + 60;
        }
        for (String name : list) {
            Theme.roundRect(g, viewX, y, viewW, 30, 6, Theme.L_CARD);
            Theme.roundRect(g, viewX + 8, y + 5, 20, 20, 4, Theme.L_TILE_HI);
            Theme.centered(g, name.substring(0, 1).toUpperCase(Locale.ROOT), viewX + 18, y + 11, Theme.L_TEXT, false);
            Theme.text(g, name, viewX + 36, y + 11, Theme.L_TEXT);
            float hv = hv("fr:" + name);
            Icons.draw(g, Icons.CLOSE, viewX + viewW - 22, y + 10, 1, Theme.lerp(Theme.L_DIM2, 0xFFF87171, hv));
            zone(viewX + viewW - 28, y + 4, 22, 22, "fr:" + name, true, b -> Friends.remove(name));
            y += 36;
        }
        return y;
    }

    private void submitProfile() {
        String n = input.getValue().trim();
        if (PulseConfig.createProfile(n)) {
            notify("Created profile " + n + " (copy of your current settings).");
            input.setValue("");
        } else {
            notify("Use 1-16 letters, numbers, - or _ (and a new name).");
        }
    }

    private int drawProfiles(GuiGraphics g, int mx, int my) {
        int y = viewY - Math.round(scroll);
        y = drawInputRow(g, y, "New profile name (copies your current setup)", "Create", this::submitProfile);
        for (String name : PulseConfig.profiles()) {
            boolean active = name.equals(PulseConfig.profile());
            Theme.roundRect(g, viewX, y, viewW, 32, 6, Theme.L_CARD);
            if (active) g.fill(viewX + 3, y + 8, viewX + 5, y + 24, Theme.ACCENT);
            Theme.text(g, name, viewX + 14, y + 12, Theme.L_TEXT);
            int rx = viewX + viewW - 10;
            if (!name.equals("default")) {
                float hv = hv("pdel:" + name);
                Icons.draw(g, Icons.CLOSE, rx - 9, y + 11, 1, Theme.lerp(Theme.L_DIM2, 0xFFF87171, hv));
                zone(rx - 14, y + 5, 22, 22, "pdel:" + name, true, b -> {
                    PulseConfig.deleteProfile(name);
                    notify("Deleted " + name + ".");
                });
                rx -= 28;
            }
            if (active) {
                Theme.text(g, "Active", rx - Theme.width("Active"), y + 12, Theme.GOOD);
            } else {
                int bw = 54;
                float hv = hv("pload:" + name);
                Theme.roundRect(g, rx - bw, y + 6, bw, 20, 5, Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv));
                Theme.centered(g, "Load", rx - bw / 2, y + 12, Theme.L_TEXT, false);
                zone(rx - bw, y + 6, bw, 20, "pload:" + name, true, b -> {
                    PulseConfig.switchProfile(name);
                    expandedColor = null;
                    notify("Loaded profile " + name + ".");
                });
            }
            y += 38;
        }
        return y;
    }

    // ------------------------------------------------------------------ skins page

    private long confirmUntil;

    private List<String> wrapText(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (Theme.width(test) > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private void submitSkin() {
        String err = SkinLibrary.addFromPath(input.getValue());
        if (err == null) {
            notify("Skin added.");
            input.setValue("");
        } else {
            notify(err);
        }
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (page != Page.SKINS) setPage(Page.SKINS);
        int added = 0;
        String lastError = null;
        for (Path p : paths) {
            String err = SkinLibrary.addFromPath(p.toString());
            if (err == null) added++; else lastError = err;
        }
        notify(added > 0 ? "Added " + added + " skin" + (added == 1 ? "" : "s") + "." : lastError == null ? "Nothing to add." : lastError);
    }

    private int drawSkins(GuiGraphics g, int mx, int my) {
        int y = viewY - Math.round(scroll);
        SkinLibrary.Entry sel = SkinLibrary.selected();
        boolean slim = SkinLibrary.slim();

        // --- selected skin: preview + actions
        small(g, "SELECTED SKIN", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        int ch = 156;
        Theme.roundRect(g, viewX, y, viewW, ch, 6, Theme.L_CARD);
        int figCx = viewX + 60;
        Theme.roundRect(g, figCx - 28, y + ch - 12, 56, 4, 2, Theme.alpha(0xFF000000, 0.35f)); // flat ground shadow
        SkinTex tex = SkinLibrary.tex(sel);
        if (tex != null) PlayerPreview.drawTexture(g, tex, slim, figCx, y + ch - 16, 3.5f);
        else Theme.centered(g, "Can't load this skin", figCx, y + ch / 2, Theme.L_DIM, false);

        int rx = viewX + 128, rw = Math.max(80, viewW - 128 - 12);
        bold(g, Theme.fit(sel.name, rw), rx, y + 10, 1.1f, Theme.L_TEXT);
        Theme.text(g, Theme.fit(sel.builtin ? "Built-in skin" : "Your skin (" + sel.file.getFileName() + ")", rw), rx, y + 25, Theme.L_DIM);

        Theme.text(g, "Arm style", rx, y + 45, Theme.L_DIM);
        String[] styles = {"Classic", "Slim"};
        for (int i = 0; i < 2; i++) {
            int cxp = rx + 56 + i * 56;
            boolean on = slim == (i == 1);
            float hv = hv("skin:arm" + i);
            Theme.roundRect(g, cxp, y + 41, 52, 16, 4, on ? Theme.alpha(Theme.ACCENT, 0.85f) : Theme.lerp(Theme.L_CONTROL, Theme.L_CONTROL_HI, hv));
            Theme.centered(g, styles[i], cxp + 26, y + 45, Theme.L_TEXT, false);
            final boolean toSlim = i == 1;
            zone(cxp, y + 41, 52, 16, "skin:arm" + i, true, b -> SkinLibrary.setSlim(toSlim));
        }

        int by = y + 66;
        boolean confirming = System.currentTimeMillis() < confirmUntil;
        String applyLabel = SkinUploader.busy() ? "Uploading..." : confirming ? "Click again to confirm" : "Apply to my account";
        int bwid = Math.min(rw, 156);
        actionBtn(g, "skin:apply", rx, by, bwid, applyLabel, true, () -> {
            if (SkinUploader.busy()) return;
            if (!confirming) {
                confirmUntil = System.currentTimeMillis() + 4000;
                SkinLibrary.setStatus("This changes the skin on your Minecraft account for everyone. Click again to confirm.");
            } else {
                confirmUntil = 0;
                SkinLibrary.applyToAccount(sel);
            }
        });
        int bx2 = rx;
        bx2 = actionBtn(g, "skin:wear", bx2, by + 26, 78, "Wear locally", false, () -> {
            if (SkinLibrary.slim()) {
                SkinLibrary.setStatus("Slim skins only look right after you apply them to your account and rejoin.");
            } else {
                SkinLibrary.wear(sel);
                SkinLibrary.setStatus("Wearing " + sel.name + " in game. Only you see this until you apply it to your account.");
            }
        });
        actionBtn(g, "skin:reset", bx2, by + 26, Math.min(rw - 84, 104), "Use my account skin", false, () -> {
            SkinLibrary.wear(null);
            SkinLibrary.setStatus("Back to your account's skin.");
        });

        String st = SkinLibrary.status();
        if (!st.isEmpty()) {
            List<String> lines = wrapText(st, rw);
            for (int i = 0; i < Math.min(4, lines.size()); i++) Theme.text(g, lines.get(i), rx, by + 56 + i * 10, Theme.L_DIM);
        } else {
            Theme.text(g, "Apply uploads it to Mojang so other players see it, even without this client.",
                    rx, by + 56, Theme.L_DIM2);
        }
        y += ch + 14;

        // --- all skins
        small(g, "ALL SKINS", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        List<SkinLibrary.Entry> list = SkinLibrary.all();
        int tileW = 58, tileH = 92, gap = 6;
        int cols = Math.max(2, (viewW - 16) / (tileW + gap));
        int rows = (list.size() + cols - 1) / cols;
        int gh = rows * (tileH + gap) + 10;
        Theme.roundRect(g, viewX, y, viewW, gh, 6, Theme.L_CARD);
        SkinLibrary.Entry worn = SkinLibrary.worn();
        for (int i = 0; i < list.size(); i++) {
            SkinLibrary.Entry e = list.get(i);
            int tx = viewX + 8 + (i % cols) * (tileW + gap);
            int ty = y + 8 + (i / cols) * (tileH + gap);
            String id = "skin:" + e.id;
            float hv = hv(id);
            if (e == sel) Theme.roundRect(g, tx - 1, ty - 1, tileW + 2, tileH + 2, 6, Theme.alpha(Theme.L_TEXT, 0.75f));
            Theme.roundRect(g, tx, ty, tileW, tileH, 5, Theme.lerp(Theme.L_TILE, Theme.L_TILE_HI, hv));
            SkinTex t = SkinLibrary.tex(e);
            if (t != null) PlayerPreview.drawTexture(g, t, false, tx + tileW / 2, ty + 70, 2.0f);
            Theme.centered(g, Theme.fit(e.name, tileW - 6), tx + tileW / 2, ty + tileH - 13, e == sel ? Theme.L_TEXT : Theme.L_DIM, false);
            if (e == worn) small(g, "WORN", tx + 4, ty + 4, Theme.GOOD);
            zone(tx, ty, tileW, tileH, id, true, b -> {
                SkinLibrary.select(e);
                SkinLibrary.setStatus("");
                confirmUntil = 0;
            });
            if (!e.builtin) {
                float dh = hv(id + ":del");
                Icons.draw(g, Icons.CLOSE, tx + tileW - 12, ty + 4, 1, Theme.lerp(Theme.L_DIM2, 0xFFF87171, dh));
                Zone z = zone(tx + tileW - 15, ty + 1, 14, 14, id + ":del", true, b -> {
                    SkinLibrary.delete(e);
                    notify("Removed " + e.name + ".");
                });
                z.group = id;
            }
        }
        y += gh + 14;

        // --- add your own
        small(g, "ADD YOUR OWN SKIN", viewX + 4, y + 2, Theme.L_DIM2);
        y += 14;
        y = drawInputRow(g, y, "Paste the path to a skin .png (64x64)", "Add", this::submitSkin);
        Theme.text(g, Theme.fit("Or drag a .png file onto this window. Your skins are kept in the lone-skins folder.", viewW - 8), viewX + 4, y, Theme.L_DIM);
        y += 16;
        int bx = viewX;
        bx = actionBtn(g, "skin:folder", bx, y, 110, "Open skins folder", false, SkinLibrary::openFolder);
        actionBtn(g, "skin:refresh", bx, y, 60, "Refresh", false, () -> {
            SkinLibrary.rescan();
            notify("Skin list refreshed.");
        });
        y += 34;
        return y;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        lastMouseX = mx;
        int b = e.button();

        boolean onSearch = searchW > 0 && Theme.in(mx, my, searchX, searchY, searchW, searchH);
        boolean onInput = inputShown && Theme.in(mx, my, inputX, inputY, inputW, inputH);
        search.setFocused(onSearch);
        input.setFocused(onInput);
        if (onSearch) {
            setFocused(search);
            search.mouseClicked(e, dbl);
            return true;
        }
        if (onInput) {
            setFocused(input);
            input.mouseClicked(e, dbl);
            return true;
        }
        if (getFocused() == search || getFocused() == input) setFocused(null);
        if (listening != null) listening = null;

        Zone z = pick(mx, my);
        if (z != null) {
            if (z.click != null) z.click.accept(b);
            if (z.drag != null && b == 0) {
                activeDrag = z;
                z.drag.apply(mx, my);
            }
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (activeDrag != null && activeDrag.drag != null) {
            activeDrag.drag.apply(e.x(), e.y());
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (activeDrag != null) {
            activeDrag = null;
            PulseConfig.save();
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Zone z = pick(mx, my);
        if (z != null && z.wheel != null && sy != 0) {
            z.wheel.accept(sy > 0 ? 1 : -1);
            return true;
        }
        targetScroll = Math.max(0, Math.min(Math.max(0, contentH - viewH), targetScroll - (float) sy * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (listening != null) {
            if (k != GLFW.GLFW_KEY_ESCAPE) {
                listening.key = (k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) ? -1 : k;
                PulseConfig.save();
            }
            listening = null;
            return true;
        }
        boolean typing = search.isFocused() || input.isFocused();
        if (k == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) {
                search.setFocused(false);
                input.setFocused(false);
                setFocused(null);
            } else if (!query.isEmpty()) {
                search.setValue("");
            } else if (detail != null) {
                detail = null;
                scroll = targetScroll = 0;
                expandedColor = null;
            } else {
                onClose();
            }
            return true;
        }
        ClientModule cm = client();
        if (!typing && cm != null && k == cm.menuKey()) {
            onClose();
            return true;
        }
        if (k == GLFW.GLFW_KEY_F && (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && searchW > 0) {
            search.setFocused(true);
            setFocused(search);
            return true;
        }
        if (input.isFocused() && (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER)) {
            if (page == Page.FRIENDS) submitFriend();
            else if (page == Page.PROFILES) submitProfile();
            else if (page == Page.SKINS) submitSkin();
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
        PulseConfig.save();
        lastPage = page;
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
