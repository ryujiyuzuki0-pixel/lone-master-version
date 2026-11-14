package dev.pulse.client;

/**
 * Single source of truth for every piece of client branding shown on screen. Anything that prints the client's
 * name (title screen, footer, HUD watermark, chat) should read it from here so the name can never drift, be
 * duplicated or be left behind as a hardcoded string.
 */
public final class Brand {
    /** Full name, used for titles, chat and the window. */
    public static final String NAME = "Lone Client";
    /** Two-part wordmark used by the title screen logo: LONE (large) + CLIENT (small, letter-spaced). */
    public static final String WORD = "LONE";
    public static final String SUB = "CLIENT";

    private Brand() {}

    /** "Lone Client 1.0.0" */
    public static String versionLine() {
        return NAME + " " + PulseClient.shortVersion();
    }
}
