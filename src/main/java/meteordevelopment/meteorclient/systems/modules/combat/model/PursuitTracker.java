/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Pursuit tracking for flee completion (C42): distance samples to the pursuer give its gain rate (+ = closing),
 * plus a line-of-sight-broken counter. Completion delegates to {@link MatchupAssessment#isFleeComplete}.
 */
public final class PursuitTracker {
    private final double[] dist = new double[20];
    private final long[] ticks = new long[20];
    private int n, head;
    private int ticksWithoutLos;

    public void sample(long tick, double distance, boolean lineOfSight) {
        dist[head] = distance;
        ticks[head] = tick;
        head = (head + 1) % dist.length;
        if (n < dist.length) n++;
        ticksWithoutLos = lineOfSight ? 0 : ticksWithoutLos + 1;
    }

    /** Closing rate in blocks/second over the window (+ = pursuer gaining on us). */
    public double gainRate() {
        if (n < 4) return 0;
        int newest = (head - 1 + dist.length) % dist.length;
        int oldest = (head - n + dist.length) % dist.length;
        double dt = (ticks[newest] - ticks[oldest]) / 20.0;
        if (dt <= 0) return 0;
        return (dist[oldest] - dist[newest]) / dt;
    }

    public boolean closingIn() {
        return gainRate() > 0.25;
    }

    public int ticksWithoutLos() {
        return ticksWithoutLos;
    }

    public double lastDistance() {
        return n == 0 ? 0 : dist[(head - 1 + dist.length) % dist.length];
    }

    public boolean complete(MatchupAssessment.Verdict verdict, double safeDistance) {
        return MatchupAssessment.isFleeComplete(verdict, lastDistance(), safeDistance, ticksWithoutLos, closingIn());
    }

    public void reset() {
        n = 0;
        head = 0;
        ticksWithoutLos = 0;
    }

    /** Safe distance (C41 dynamic flee goal): max(configured, pursuer threat range + margin incl. latency). */
    public static double safeDistance(double configured, double pursuerRange, double latencyMargin) {
        return Math.max(configured, pursuerRange + 6.0 + latencyMargin);
    }
}
