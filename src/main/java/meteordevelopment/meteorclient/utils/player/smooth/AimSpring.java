/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player.smooth;

/**
 * Exact one-tick critically-damped spring used to smooth camera claims when Baritone isn't installed (plan S1/S3
 * fallback). Velocity carries over between ticks, so retargeting never produces a velocity jump, and from rest the
 * spring never overshoots. Pure Java.
 */
public final class AimSpring {
    private double pos, vel;
    private boolean initialized;

    public void reset(double position) {
        pos = position;
        vel = 0;
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void invalidate() {
        initialized = false;
    }

    public double position() {
        return pos;
    }

    public double velocity() {
        return vel;
    }

    /** Re-anchors to an externally changed position (mouse, teleport) while keeping the velocity. */
    public void sync(double position) {
        pos = position;
    }

    /**
     * Advances one tick toward {@code goal}.
     *
     * @param tau    time constant in ticks (larger = slower)
     * @param maxVel per-tick cap in degrees
     * @return the new position
     */
    public double step(double goal, double tau, double maxVel) {
        if (!initialized) reset(pos);
        double w = 1.0 / Math.max(1e-3, tau);
        double x0 = pos - goal;
        double decay = Math.exp(-w);
        double c = vel + w * x0;
        double newVel = (vel - w * c) * decay;
        double newPos = goal + (x0 + c) * decay;
        double step = clamp(newPos - pos, -maxVel, maxVel);
        vel = clamp(newVel, -maxVel, maxVel);
        pos += step;
        if (Math.abs(goal - pos) < 1e-3 && Math.abs(vel) < 1e-3) {
            pos = goal;
            vel = 0;
        }
        return pos;
    }

    /** Mouse sensitivity GCD step in degrees: {@code (s*0.6+0.2)^3 * 8 * 0.15}. */
    public static double gcdStep(double sensitivity) {
        if (sensitivity < 0) sensitivity = 0.5;
        double f = sensitivity * 0.6 + 0.2;
        return f * f * f * 1.2;
    }

    /** Snaps an angle to a multiple of {@code step}. */
    public static double quantize(double degrees, double step) {
        if (step <= 0) return degrees;
        return Math.round(degrees / step) * step;
    }

    /** Wraps to [-180, 180). */
    public static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
