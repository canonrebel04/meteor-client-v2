/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

/**
 * Plan S84/S85: unified animation constants (durations in seconds, default curves) and the accessibility
 * switches. {@link #reduceMotion} makes tweens snap and pulses static; {@link #highContrast} asks overlays for
 * opaque backgrounds and full-alpha colours. Set from Meteor's config.
 */
public final class Anim {
    public static final double FAST = 0.12;
    public static final double NORMAL = 0.25;
    public static final double SLOW = 0.45;
    /** Minimum time a HUD state stays visible before it may change (S73). */
    public static final double MIN_DISPLAY = 0.6;
    public static final String CURVE_IN = "cubic";
    public static final String CURVE_POP = "spring";

    public static volatile boolean reduceMotion;
    public static volatile boolean highContrast;
    /** Render detail 0.25..1 from {@code FrameStats} when adaptive quality is on (S94), else 1. */
    public static volatile double quality = 1;

    private Anim() {
    }

    /** Alpha (0-255) for an overlay background given the accessibility switch. */
    public static int backgroundAlpha(int normal) {
        return highContrast ? 255 : normal;
    }

    /** A 0..1 pulse with period {@code seconds} (constant 1 with reduced motion). */
    public static double pulse(double timeSeconds, double periodSeconds) {
        if (reduceMotion || periodSeconds <= 0) return 1;
        return 0.5 + 0.5 * Math.sin(timeSeconds * 2 * Math.PI / periodSeconds);
    }
}
