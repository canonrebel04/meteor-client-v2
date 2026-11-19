/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Anticheat-safe action budget (C91): a sliding one-second click window (CPS cap), a reach limit measured to the
 * target's hitbox, and an impossible-turn check. Rotation GCD quantisation and the per-tick turn cap live in the
 * shared look profile behind {@code CameraArbiter}. Pure.
 */
public final class ActionBudget {
    private final long[] clicks = new long[64];
    private int head;
    private int count;
    public int maxCps = 10;
    public double maxReach = 3.0;
    /** Largest believable camera turn in one tick, degrees. */
    public double maxTurnPerTick = 70;

    /** Clicks inside the last second as of {@code nowMs}. */
    public int cps(long nowMs) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            long t = clicks[(head - 1 - i + clicks.length) % clicks.length];
            if (nowMs - t < 1000) n++;
            else break;
        }
        return n;
    }

    /** May we click now without going over {@link #maxCps}. {@code maxCps <= 0} means unlimited. */
    public boolean mayClick(long nowMs) {
        return maxCps <= 0 || cps(nowMs) < maxCps;
    }

    public void recordClick(long nowMs) {
        clicks[head] = nowMs;
        head = (head + 1) % clicks.length;
        if (count < clicks.length) count++;
    }

    /** Is a hitbox at {@code distanceToBox} (eyes to nearest box point) inside the reach limit. */
    public boolean inReach(double distanceToBox) {
        return maxReach <= 0 || distanceToBox <= maxReach;
    }

    /** Would turning by {@code yawDelta}/{@code pitchDelta} in one tick look impossible. */
    public boolean impossibleTurn(double yawDelta, double pitchDelta) {
        double y = Math.abs(((yawDelta % 360) + 540) % 360 - 180);
        return Math.hypot(y, pitchDelta) > maxTurnPerTick;
    }

    public void reset() {
        head = 0;
        count = 0;
    }
}
