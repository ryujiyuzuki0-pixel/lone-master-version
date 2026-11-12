package dev.pulse.client.skin;

import dev.pulse.client.PulseClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes the skin on the signed-in Minecraft account through Mojang's official profile API
 * (POST https://api.minecraftservices.com/minecraft/profile/skins). That is what makes the skin visible to
 * every other player, including people who do not have this client. The session token only ever goes to that URL.
 */
public final class SkinUploader {
    private static final String URL = "https://api.minecraftservices.com/minecraft/profile/skins";
    private static volatile boolean busy;

    private SkinUploader() {}

    public static boolean busy() {
        return busy;
    }

    /** Reads the PNG header: returns "" when it looks like a valid skin, otherwise the reason it is not. */
    public static String validate(byte[] png) {
        if (png == null || png.length < 33) return "That file is not a PNG image.";
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) if (png[i] != sig[i]) return "That file is not a PNG image.";
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        if (w != 64 || (h != 64 && h != 32)) return "A skin must be 64x64 (or 64x32) pixels. This one is " + w + "x" + h + ".";
        return "";
    }

    /** Looks like a real Microsoft session token (offline / cracked launchers use a short placeholder). */
    public static boolean canUpload(String token) {
        return token != null && token.length() > 40;
    }

    public static void upload(byte[] png, boolean slim, String token, Consumer<String> status) {
        if (busy) {
            status.accept("Already uploading, please wait...");
            return;
        }
        String problem = validate(png);
        if (!problem.isEmpty()) {
            status.accept(problem);
            return;
        }
        if (!canUpload(token)) {
            status.accept("This account is not signed in with Microsoft, so Mojang can't change its skin. "
                    + "The skin is only changed for you.");
            return;
        }
        busy = true;
        status.accept("Uploading skin to your Minecraft account...");
        Thread t = new Thread(() -> {
            try {
                status.accept(send(png, slim, token));
            } catch (Exception e) {
                PulseClient.LOG.warn("Skin upload failed", e);
                status.accept("Upload failed: " + e.getClass().getSimpleName() + ". Check your internet connection.");
            } finally {
                busy = false;
            }
        }, "lone-skin-upload");
        t.setDaemon(true);
        t.start();
    }

    private static String send(byte[] png, boolean slim, String token) throws Exception {
        String boundary = "----LoneClient" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection c = (HttpURLConnection) URI.create(URL).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.getOutputStream().write(body.toByteArray());
        int code = c.getResponseCode();
        if (code >= 200 && code < 300) {
            return "Skin changed! Other players see it after they reload you (rejoin the server or world).";
        }
        String err = "";
        try (InputStream in = c.getErrorStream()) {
            if (in != null) err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\"errorMessage\"\\s*:\\s*\"([^\"]*)\"").matcher(err);
        String detail = m.find() ? m.group(1) : "";
        return switch (code) {
            case 401 -> "Mojang rejected your login session. Restart the launcher and sign in again.";
            case 429 -> "Mojang is rate-limiting skin changes. Wait a minute and try again.";
            case 400 -> "Mojang rejected that skin" + (detail.isEmpty() ? "." : ": " + detail);
            default -> "Skin change failed (HTTP " + code + ")" + (detail.isEmpty() ? "." : ": " + detail);
        };
    }
}
