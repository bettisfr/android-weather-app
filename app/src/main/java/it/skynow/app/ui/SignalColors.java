package it.skynow.app.ui;

/** Shared base colors for severity, brightness, and status indicators. */
public final class SignalColors {
    public static final int GREEN = 0xff65b878;
    public static final int LIME = 0xffa5c65c;
    public static final int YELLOW = 0xffe5bd36;
    public static final int ORANGE = 0xffe8872d;
    public static final int RED = 0xffd84b53;
    public static final int DEEP_RED = 0xffaa3e58;
    public static final int PURPLE = 0xff8550a0;
    public static final int BLUE = 0xff536ab4;
    public static final int GRAY = 0xff8d9bb2;

    private SignalColors() {}

    public static int tint(int color) {
        return (color & 0x00ffffff) | 0x40000000;
    }
}
