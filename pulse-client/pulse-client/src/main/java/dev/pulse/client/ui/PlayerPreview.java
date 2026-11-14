package dev.pulse.client.ui;

import com.mojang.authlib.GameProfile;
import dev.pulse.client.module.ModuleManager;
import dev.pulse.client.module.impl.CapeModule;
import dev.pulse.client.skin.SkinLibrary;
import dev.pulse.client.skin.SkinTex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.entity.player.PlayerSkin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Draws the player's own skin as a standing figure (front view) on the title screen. The skin is read from the
 * game's skin manager, so it follows whatever account is logged in. If it can't be read, a blue figure is drawn.
 */
public final class PlayerPreview {
    private static Method lookup;
    private static boolean searched;
    /** Bare id + name profile. It has no texture data, so it only gets a default skin. Used as a last resort. */
    private static GameProfile bareProfile;
    /** The signed-in account's full profile (carries the signed texture property). Filled in off-thread. */
    private static volatile GameProfile fullProfile;
    private static boolean profileAsked;
    private static long profileAskedAt;
    private static PlayerSkin skin;
    private static volatile long polled;
    private static int failures;

    private PlayerPreview() {}

    // ------------------------------------------------------------------ skin lookup

    private static PlayerSkin skin(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - polled < (skin == null ? 400 : 1500)) return skin;
        polled = now;
        try {
            if (!searched) {
                searched = true;
                find(mc);
            }
            if (lookup == null) return skin;
            requestFullProfile(mc);
            GameProfile p = fullProfile;
            if (p == null) {
                // Give the account profile a few seconds to arrive; offline accounts never get one, so fall back to the bare profile.
                if (now - profileAskedAt < 4000) return skin;
                p = bareProfile;
            }
            PlayerSkin s = unwrap(lookup.invoke(mc.getSkinManager(), p));
            if (s != null) skin = s;
            failures = 0;
        } catch (Exception | LinkageError e) {
            // A failed lookup (e.g. the skin download errored) is retried; only give up after several in a row.
            if (++failures >= 8) lookup = null;
        }
        return skin;
    }

    /**
     * Asks for the signed-in account's full GameProfile once, on a worker thread. The game keeps that profile (with the
     * account's skin texture property) in Minecraft itself; a profile built from just the UUID and name has no
     * textures, which is why the avatar used to come out as a default skin. Found by return type because method
     * names differ between mappings.
     */
    private static void requestFullProfile(Minecraft mc) {
        if (profileAsked) return;
        profileAsked = true;
        profileAskedAt = System.currentTimeMillis();
        Method getter = null;
        for (Method m : Minecraft.class.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == GameProfile.class) {
                getter = m;
                break;
            }
        }
        if (getter == null) return;
        final Method call = getter;
        CompletableFuture.runAsync(() -> {
            try {
                Object o = call.invoke(mc);
                if (o instanceof GameProfile gp) {
                    fullProfile = gp;
                    polled = 0; // look the skin up right away
                }
            } catch (Exception | LinkageError ignored) {
                // stay on the bare profile
            }
        });
    }

    /** Finds the skin manager method that takes a GameProfile (its name differs between versions). */
    private static void find(Minecraft mc) throws Exception {
        var user = mc.getUser();
        UUID id = null;
        for (Method m : user.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == UUID.class) {
                id = (UUID) m.invoke(user);
                break;
            }
        }
        if (id == null) return;
        bareProfile = new GameProfile(id, user.getName());
        Method best = null;
        int bestScore = -1;
        for (Method m : mc.getSkinManager().getClass().getMethods()) {
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != GameProfile.class) continue;
            Class<?> rt = m.getReturnType();
            int score = -1;
            if (Supplier.class.isAssignableFrom(rt)) score = 3;
            else if (rt == PlayerSkin.class) score = 2;
            else if (CompletableFuture.class.isAssignableFrom(rt)) score = 1;
            if (score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        lookup = best;
    }

    private static PlayerSkin unwrap(Object r) {
        Object o = r;
        for (int i = 0; i < 4 && o != null && !(o instanceof PlayerSkin); i++) {
            if (o instanceof CompletableFuture<?> f) o = f.getNow(null);
            else if (o instanceof Optional<?> opt) o = opt.orElse(null);
            else if (o instanceof Supplier<?> sup) o = sup.get();
            else return null;
        }
        return o instanceof PlayerSkin ps ? ps : null;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * @param cx    horizontal center on screen
     * @param feetY screen y of the soles
     * @param scale screen pixels per skin pixel (the figure is 32 skin pixels tall)
     */
    public static void draw(GuiGraphics g, Minecraft mc, int cx, int feetY, float scale, double time, float alpha) {
        // soft glow under the feet
        for (int i = 0; i < 6; i++) {
            int w = (int) (scale * (30 - i * 4)), h = Math.max(3, (int) (scale * (5 - i * 0.6f)));
            Theme.roundRect(g, cx - w / 2, feetY - h / 2 + 2, w, h, h / 2, Theme.alpha(0xFF4C7EE8, 0.10f * alpha));
        }

        PlayerSkin s = skin(mc);
        float bob = (float) Math.sin(time * 1.6) * 0.5f;
        g.pose().pushMatrix();
        g.pose().translate(cx + (float) Math.sin(time * 0.8) * scale * 0.4f, feetY + bob * scale * 0.4f);
        g.pose().scale(scale, scale);

        CapeModule cape = ModuleManager.cape();
        if (cape != null && cape.enabled && cape.face() != null) drawCape(g, cape.face(), time, alpha);

        SkinLibrary.Entry worn = SkinLibrary.worn();
        SkinTex wornTex = worn == null ? null : SkinLibrary.tex(worn);
        if (wornTex != null) {
            var id = wornTex.id;
            drawParts((x, y, u, v, w, h) -> g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, w, h, 64, 64), false);
        } else if (s != null) {
            drawSkin(g, s, alpha);
        } else {
            drawFallback(g, alpha);
        }
        g.pose().popMatrix();
    }

    private static void drawCape(GuiGraphics g, int[] face, double time, float alpha) {
        g.pose().pushMatrix();
        g.pose().translate(0f, -24f);
        g.pose().scale(1.25f, 1.18f);
        for (int y = 0; y < 16; y++) {
            float sway = (float) Math.sin(time * 1.6 + y * 0.4) * 0.5f * (y / 15f);
            for (int x = 0; x < 10; x++) {
                int c = face[y * 10 + x];
                g.fill(Math.round(x - 5 + sway), y, Math.round(x - 4 + sway), y + 1, Theme.alpha(c, alpha));
            }
        }
        g.pose().popMatrix();
    }

    /** Draws the 12 body parts of a skin through a callback that blits one region of the right texture. */
    private interface PartBlit {
        void part(int x, int y, float u, float v, int w, int h);
    }

    private static void drawParts(PartBlit b, boolean slim) {
        int aw = slim ? 3 : 4;
        // base layers
        b.part(-4, -32, 8f, 8f, 8, 8);            // head
        b.part(-4, -24, 20f, 20f, 8, 12);         // body
        b.part(-4 - aw, -24, 44f, 20f, aw, 12);   // arm (viewer left)
        b.part(4, -24, 36f, 52f, aw, 12);         // arm (viewer right)
        b.part(-4, -12, 4f, 20f, 4, 12);          // leg (viewer left)
        b.part(0, -12, 20f, 52f, 4, 12);          // leg (viewer right)
        // clothing / hat layers
        b.part(-4, -32, 40f, 8f, 8, 8);
        b.part(-4, -24, 20f, 36f, 8, 12);
        b.part(-4 - aw, -24, 44f, 36f, aw, 12);
        b.part(4, -24, 52f, 52f, aw, 12);
        b.part(-4, -12, 4f, 36f, 4, 12);
        b.part(0, -12, 4f, 52f, 4, 12);
    }

    private static void drawSkin(GuiGraphics g, PlayerSkin s, float alpha) {
        var tex = s.body().texturePath();
        boolean slim = String.valueOf(s.model()).toLowerCase(Locale.ROOT).contains("slim");
        drawParts((x, y, u, v, w, h) -> g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, w, h, 64, 64), slim);
    }

    /**
     * Draws any skin texture (used by the Wardrobe tiles and preview).
     *
     * @param cx    horizontal center, @param feetY y of the soles, @param scale screen pixels per skin pixel
     */
    public static void drawTexture(GuiGraphics g, SkinTex t, boolean slim, int cx, int feetY, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(cx, feetY);
        g.pose().scale(scale, scale);
        var id = t.id;
        drawParts((x, y, u, v, w, h) -> g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, w, h, 64, 64), slim);
        g.pose().popMatrix();
    }

    private static void drawFallback(GuiGraphics g, float alpha) {
        int hair = Theme.alpha(0xFF2B3FB0, alpha), face = Theme.alpha(0xFF5B78E8, alpha);
        int body = Theme.alpha(0xFF3FA2F5, alpha), arm = Theme.alpha(0xFF7C8CF0, alpha), leg = Theme.alpha(0xFF2F58D8, alpha);
        g.fill(-4, -32, 4, -24, face);
        g.fill(-4, -32, 4, -29, hair);
        g.fill(-4, -24, 4, -12, body);
        g.fill(-8, -24, -4, -12, arm);
        g.fill(4, -24, 8, -12, arm);
        g.fill(-4, -12, 0, 0, leg);
        g.fill(0, -12, 4, 0, leg);
    }
}
