/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/** Latency model (C19): smoothed ping for us and an opponent, turned into tick offsets and reach margins. */
public final class PingModel {
    private double ms = -1;

    public void sample(double pingMs) {
        if (pingMs < 0 || pingMs > 5000) return;
        ms = ms < 0 ? pingMs : ms + 0.15 * (pingMs - ms);
    }

    public double millis() {
        return Math.max(0, ms);
    }

    /** One-way delay in ticks (half the round trip). */
    public double oneWayTicks() {
        return millis() / 2.0 / 50.0;
    }

    /**
     * How far (blocks) an entity moving at {@code speed} blocks/tick travels during the combined one-way delay of
     * both sides: the extra margin to keep when judging "out of their reach".
     */
    public static double reachMargin(PingModel mine, PingModel theirs, double speed) {
        double t = (mine == null ? 0 : mine.oneWayTicks()) + (theirs == null ? 0 : theirs.oneWayTicks());
        return Math.min(1.5, Math.max(0, speed) * t);
    }
}
