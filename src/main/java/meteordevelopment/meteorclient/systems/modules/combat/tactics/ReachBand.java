/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Reach management and kiting bands (C27, C32), pure. While our swing charges, stand just outside their reach;
 * when it is (nearly) ready, step to the edge of ours. A longer-reach opponent can't be out-spaced: then just
 * close in on ready.
 */
public final class ReachBand {
    public record Band(double min, double max) {
        public double mid() {
            return (min + max) / 2;
        }
    }

    private ReachBand() {
    }

    /**
     * @param myReach      our entity-interaction range (≈3.0, more with spears/attributes)
     * @param theirReach   theirs (from their attribute / weapon; mobs ≈ 2 + half width)
     * @param ticksToReady ticks until our charge is ready
     * @param approachTicks ticks we need to close {@code bandGap} (≈ 2-3 at sprint)
     */
    public static Band band(double myReach, double theirReach, int ticksToReady, int approachTicks) {
        double strike = Math.max(1.5, myReach - 0.25);
        if (ticksToReady <= approachTicks) return new Band(Math.max(0.8, strike - 1.0), strike);
        double outside = theirReach + 0.35;
        if (outside >= myReach + 1.5) return new Band(Math.max(0.8, strike - 1.0), strike); // can't out-space; brawl on ready
        return new Band(outside, outside + 1.0);
    }

    /** Kiting for ranged: keep between {@code lo} and {@code hi}, wider while reloading. */
    public static Band kite(double theirReach, boolean reloading) {
        double lo = Math.max(theirReach + 3.0, 6.0);
        return new Band(lo, reloading ? lo + 6 : lo + 3);
    }
}
