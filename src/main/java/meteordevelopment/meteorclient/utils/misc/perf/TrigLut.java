/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

/**
 * Plan S96: a precomputed sine/cosine table so per-tick combat and HUD maths can avoid
 * {@link Math#sin} in hot loops. Entries are linearly interpolated; accuracy is well
 * within 2e-4 of {@code Math.sin/cos}.
 */
public final class TrigLut {
    private static final int BITS = 12;
    private static final int SIZE = 1 << BITS;
    private static final int MASK = SIZE - 1;
    private static final double[] SIN = new double[SIZE];
    private static final double TO_INDEX = SIZE / (Math.PI * 2.0);

    static {
        for (int i = 0; i < SIZE; i++) {
            SIN[i] = Math.sin(i * Math.PI * 2.0 / SIZE);
        }
    }

    private TrigLut() {
    }

    public static double sin(double radians) {
        if (!Double.isFinite(radians)) return Double.NaN;
        double idx = radians * TO_INDEX;
        int i0 = (int) Math.floor(idx);
        double frac = idx - i0;
        int a = i0 & MASK;
        int b = (a + 1) & MASK;
        return SIN[a] + (SIN[b] - SIN[a]) * frac;
    }

    public static double cos(double radians) {
        if (!Double.isFinite(radians)) return Double.NaN;
        // cos(x) = sin(x + pi/2)
        return sin(radians + Math.PI * 0.5);
    }

    public static double sinDeg(double degrees) {
        return sin(Math.toRadians(degrees));
    }

    public static double cosDeg(double degrees) {
        return cos(Math.toRadians(degrees));
    }
}
