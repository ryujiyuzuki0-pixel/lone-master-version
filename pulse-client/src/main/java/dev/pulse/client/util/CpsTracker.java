package dev.pulse.client.util;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/** Polls raw mouse buttons so CPS is correct regardless of key bindings. */
public final class CpsTracker {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();
    private static boolean prevL, prevR;

    private CpsTracker() {}

    public static void poll(Minecraft mc) {
        if (mc.getWindow() == null) return;
        long win = mc.getWindow().handle();
        boolean inGame = mc.screen == null && mc.player != null;
        boolean l = inGame && GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean r = inGame && GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        long now = System.currentTimeMillis();
        if (l && !prevL) LEFT.addLast(now);
        if (r && !prevR) RIGHT.addLast(now);
        prevL = l;
        prevR = r;
        trim(LEFT, now);
        trim(RIGHT, now);
    }

    private static void trim(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
    }

    public static int left() {
        return LEFT.size();
    }

    public static int right() {
        return RIGHT.size();
    }
}
