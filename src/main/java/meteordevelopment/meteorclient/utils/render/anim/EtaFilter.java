/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

/**
 * Plan S69: Kalman-filtered ETA. Tracks remaining seconds with a constant-rate model (remaining drops one second
 * per second) and blends in noisy planner estimates, so the displayed number counts down steadily instead of
 * jumping each re-plan. Large re-plans (more than {@code resetRatio} off) reset it.
 */
public final class EtaFilter {
    private final double processNoise;
    private final double measurementNoise;
    private final double resetRatio;
    private double estimate = -1;
    private double variance;

    public EtaFilter() {
        this(0.5, 25, 3.0);
    }

    public EtaFilter(double processNoise, double measurementNoise, double resetRatio) {
        this.processNoise = processNoise;
        this.measurementNoise = measurementNoise;
        this.resetRatio = resetRatio;
    }

    /**
     * @param measured planner ETA in seconds (negative = none this time)
     * @param dt       seconds since the last update
     * @return filtered ETA (seconds, >= 0), or -1 when nothing is known yet
     */
    public double update(double measured, double dt) {
        if (estimate >= 0) {
            estimate = Math.max(0, estimate - dt);
            variance += processNoise * dt;
        }
        if (measured >= 0) {
            if (estimate < 0 || measured > estimate * resetRatio + 5 || measured * resetRatio + 5 < estimate) {
                estimate = measured;
                variance = measurementNoise;
            } else {
                double k = variance / (variance + measurementNoise);
                estimate += k * (measured - estimate);
                variance *= 1 - k;
            }
        }
        return estimate;
    }

    public void reset() {
        estimate = -1;
        variance = 0;
    }

    /** "1m 05s", "42s", "?" */
    public static String format(double seconds) {
        if (seconds < 0) return "?";
        long s = Math.round(seconds);
        if (s >= 3600) return (s / 3600) + "h " + String.format(java.util.Locale.ROOT, "%02dm", (s % 3600) / 60);
        if (s >= 60) return (s / 60) + "m " + String.format(java.util.Locale.ROOT, "%02ds", s % 60);
        return s + "s";
    }
}
