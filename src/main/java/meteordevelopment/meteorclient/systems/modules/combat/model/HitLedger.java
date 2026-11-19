/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/** Damage dealt/taken with one opponent (C15): totals, hit counts, last-hit times and trade ratio. */
public final class HitLedger {
    private double dealt, taken;
    private int hitsDealt, hitsTaken;
    private long lastDealtTick = Long.MIN_VALUE / 2, lastTakenTick = Long.MIN_VALUE / 2;

    public void dealt(double damage, long tick) {
        if (damage <= 0) return;
        dealt += damage;
        hitsDealt++;
        lastDealtTick = tick;
    }

    public void taken(double damage, long tick) {
        if (damage <= 0) return;
        taken += damage;
        hitsTaken++;
        lastTakenTick = tick;
    }

    public double dealt() {
        return dealt;
    }

    public double taken() {
        return taken;
    }

    public int hitsDealt() {
        return hitsDealt;
    }

    public int hitsTaken() {
        return hitsTaken;
    }

    public long ticksSinceDealt(long now) {
        return now - lastDealtTick;
    }

    public long ticksSinceTaken(long now) {
        return now - lastTakenTick;
    }

    /** dealt / taken (1 = even trades); 1 until at least a few hits happened. */
    public double tradeRatio() {
        if (hitsDealt + hitsTaken < 3) return 1.0;
        return (dealt + 1.0) / (taken + 1.0);
    }

    /**
     * Correction on the modelled kill ratio from what actually happened: trades going much worse than modelled
     * pull the verdict down (and vice versa), clamped to [0.7, 1.3].
     */
    public double observedCorrection() {
        double r = tradeRatio();
        double c = Math.sqrt(r);
        return Math.max(0.7, Math.min(1.3, c));
    }
}
