/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import java.util.Arrays;

/**
 * Plan S93/S94: frame-time telemetry (ring buffer, p50/p95/p99) and an adaptive quality level with hysteresis:
 * quality drops a step while p95 frame time is above the target and rises again once it is comfortably below,
 * at most one step per {@code holdFrames}. Overlays and time-sliced scans read {@link #quality()} (0..1).
 */
public final class FrameStats {
    public static final int WINDOW = 240;
    private static final FrameStats GAME = new FrameStats(1000.0 / 50, 120);

    private final long[] frames = new long[WINDOW];
    private int count, next;
    private double targetMs;
    private final int holdFrames;
    private int sinceChange;
    private int level; // 0 = full quality, 3 = lowest

    public FrameStats(double targetMs, int holdFrames) {
        this.targetMs = targetMs;
        this.holdFrames = holdFrames;
    }

    public static FrameStats game() {
        return GAME;
    }

    public synchronized void setTargetMs(double ms) {
        targetMs = ms;
    }

    public synchronized void record(long frameNanos) {
        frames[next] = frameNanos;
        next = (next + 1) % WINDOW;
        if (count < WINDOW) count++;
        sinceChange++;
        if (count < 30 || sinceChange < holdFrames) return;
        double p95 = percentile(95) / 1e6;
        if (p95 > targetMs && level < 3) {
            level++;
            sinceChange = 0;
        } else if (p95 < targetMs * 0.7 && level > 0) {
            level--;
            sinceChange = 0;
        }
    }

    public synchronized long percentile(int p) {
        if (count == 0) return 0;
        long[] c = Arrays.copyOf(frames, count);
        Arrays.sort(c);
        int rank = (int) Math.ceil(p / 100.0 * count) - 1;
        return c[Math.max(0, Math.min(count - 1, rank))];
    }

    /** 1.0 = full detail, 0.25 = lowest. */
    public synchronized double quality() {
        return 1.0 - level * 0.25;
    }

    public synchronized int level() {
        return level;
    }

    public synchronized String summary() {
        return String.format(java.util.Locale.ROOT, "frames p50 %.1f ms, p95 %.1f, p99 %.1f, quality %d%%",
                percentile(50) / 1e6, percentile(95) / 1e6, percentile(99) / 1e6, Math.round(quality() * 100));
    }
}
