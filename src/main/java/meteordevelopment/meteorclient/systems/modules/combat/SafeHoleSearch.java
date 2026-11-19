/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.misc.perf.TickBudget;
import meteordevelopment.meteorclient.utils.misc.perf.TimeSlicedBoxScan;

/**
 * Time-sliced, cached search for the best emergency hole around the player (plan S86/S88). The box scan is spread
 * over ticks under a {@link TickBudget} instead of probing ~10k cells in one tick, and a finished result is reused
 * while it is fresh and the player hasn't moved far. Pure logic: the world is accessed only through {@link Probe}.
 */
final class SafeHoleSearch {
    /** Scores a candidate cell: lower is better, {@code NaN} when it isn't a usable hole. */
    @FunctionalInterface
    interface Probe {
        double score(int x, int y, int z);
    }

    static final int VERTICAL_RANGE = 4;
    /** Cells always probed per tick, so the scan finishes in a bounded number of ticks even on a slow machine. */
    static final int MIN_CELLS_PER_TICK = 1024;

    private final int maxAgeTicks;
    private final int moveTolerance;
    private final TimeSlicedBoxScan scan = new TimeSlicedBoxScan();

    private Probe probe;
    private int originX, originY, originZ;
    private double bestScore;
    private int bestX, bestY, bestZ;
    private boolean bestFound;

    private boolean hasResult;
    private boolean found;
    private int resultX, resultY, resultZ;
    private int completedAt;
    private int resultOriginX, resultOriginY, resultOriginZ;

    private final TimeSlicedBoxScan.Visitor visitor = (x, y, z) -> {
        double s = probe.score(x, y, z);
        if (!Double.isNaN(s) && s < bestScore) {
            bestScore = s;
            bestX = x;
            bestY = y;
            bestZ = z;
            bestFound = true;
        }
        return false;
    };

    SafeHoleSearch(int maxAgeTicks, int moveTolerance) {
        this.maxAgeTicks = maxAgeTicks;
        this.moveTolerance = moveTolerance;
    }

    /**
     * Advances the search one tick.
     *
     * @return true when a result for the current position is ready ({@link #found()} tells whether a hole exists)
     */
    boolean tick(int now, int px, int py, int pz, int radius, TickBudget budget, Probe probe) {
        if (!scan.isRunning() && hasResult && now - completedAt <= maxAgeTicks
            && near(px, py, pz, resultOriginX, resultOriginY, resultOriginZ)) {
            return true;
        }
        if (scan.isRunning() && !near(px, py, pz, originX, originY, originZ)) {
            scan.cancel();
        }
        if (!scan.isRunning()) {
            originX = px;
            originY = py;
            originZ = pz;
            bestScore = Double.MAX_VALUE;
            bestFound = false;
            scan.start(px, py, pz, radius, VERTICAL_RANGE);
        }
        this.probe = probe;
        boolean finished = scan.step(budget, MIN_CELLS_PER_TICK, visitor);
        this.probe = null;
        if (!finished) return false;

        hasResult = true;
        found = bestFound;
        resultX = bestX;
        resultY = bestY;
        resultZ = bestZ;
        completedAt = now;
        resultOriginX = originX;
        resultOriginY = originY;
        resultOriginZ = originZ;
        return true;
    }

    private boolean near(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.abs(ax - bx) <= moveTolerance && Math.abs(ay - by) <= moveTolerance && Math.abs(az - bz) <= moveTolerance;
    }

    /** Drops the cached result (e.g. the cached hole turned out to be unusable). */
    void invalidate() {
        hasResult = false;
        scan.cancel();
    }

    boolean isRunning() {
        return scan.isRunning();
    }

    boolean found() {
        return hasResult && found;
    }

    int x() {
        return resultX;
    }

    int y() {
        return resultY;
    }

    int z() {
        return resultZ;
    }
}
