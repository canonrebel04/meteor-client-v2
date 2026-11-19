/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Detects a stuck combat path (C96): Baritone reporting calculation failures, or no progress towards the goal for
 * {@link #STALL_TICKS}. When failed the caller falls back to direct movement for {@link #COOLDOWN_TICKS} before
 * asking Baritone again. Pure.
 */
public final class PathWatchdog {
    public enum Status { OK, FAILED }

    public static final int MAX_CALC_FAILS = 3;
    public static final int STALL_TICKS = 80;
    public static final int COOLDOWN_TICKS = 40;
    public static final double PROGRESS = 1.0;

    private int calcFails;
    private double best = Double.MAX_VALUE;
    private long bestTick;
    private long failedAt = Long.MIN_VALUE;
    private boolean started;

    /** A new goal: forget history. */
    public void reset(long tick) {
        calcFails = 0;
        best = Double.MAX_VALUE;
        bestTick = tick;
        started = true;
    }

    public Status tick(boolean calcFailed, double distanceToGoal, long tick) {
        if (!started) reset(tick);
        if (failed(tick)) return Status.FAILED;
        calcFails = calcFailed ? calcFails + 1 : 0;
        if (distanceToGoal < best - PROGRESS) {
            best = distanceToGoal;
            bestTick = tick;
        }
        boolean arrived = distanceToGoal <= 1.5;
        if (calcFails >= MAX_CALC_FAILS || (!arrived && tick - bestTick >= STALL_TICKS)) {
            failedAt = tick;
            calcFails = 0;
            best = Double.MAX_VALUE;
            bestTick = tick + COOLDOWN_TICKS;
            return Status.FAILED;
        }
        return Status.OK;
    }

    /** In the fallback window after a failure. */
    public boolean failed(long tick) {
        return failedAt != Long.MIN_VALUE && tick - failedAt < COOLDOWN_TICKS;
    }
}
