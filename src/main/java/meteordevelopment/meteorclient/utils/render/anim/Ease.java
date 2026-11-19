/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

/**
 * Plan S74: one easing library for GUI, HUD and rendering. All functions map t in [0,1] to [0,1] (clamped),
 * except {@link #spring} which may overshoot slightly by design.
 */
public final class Ease {
    private Ease() {
    }

    public static double clamp01(double t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static double linear(double t) {
        return clamp01(t);
    }

    public static double smoothstep(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    public static double cubicIn(double t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static double cubicOut(double t) {
        t = 1 - clamp01(t);
        return 1 - t * t * t;
    }

    public static double cubicInOut(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    public static double expoOut(double t) {
        t = clamp01(t);
        return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    public static double expoInOut(double t) {
        t = clamp01(t);
        if (t == 0 || t == 1) return t;
        return t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
    }

    /** Under-damped spring response (overshoots ~4% then settles): playful pop-ins. */
    public static double spring(double t) {
        t = clamp01(t);
        if (t >= 1) return 1;
        return 1 - Math.exp(-6 * t) * Math.cos(9 * t);
    }

    /** Ease by name ("cubic", "expo", "spring", "smooth", "linear"), for theme constants. */
    public static double apply(String curve, double t) {
        switch (curve) {
            case "cubic": return cubicOut(t);
            case "cubic-in-out": return cubicInOut(t);
            case "expo": return expoOut(t);
            case "spring": return spring(t);
            case "smooth": return smoothstep(t);
            default: return linear(t);
        }
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
