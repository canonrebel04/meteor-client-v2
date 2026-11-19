/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Terrain-scored flee planner (C41, C49): scores candidate retreat points by distance gained from every pursuer,
 * cover / line-of-sight break, hazards, escape space (no dead ends), and how far we have to run.
 */
public final class FleePlanner {
    /** A candidate destination with properties sampled from the world. */
    public record Candidate(double x, double y, double z, boolean standable, boolean hazard, boolean losBlocked,
                            int coverBlocks, int escapeSpace, double heightDelta) {
    }

    public record Pursuer(double x, double z, double speed) {
    }

    public record Scored(Candidate candidate, double score, String why) {
    }

    /** Weights; defaults chosen so distance dominates and dead ends / hazards are effectively excluded. */
    public double distanceWeight = 1.0;
    public double losWeight = 6.0;
    public double coverWeight = 0.8;
    public double escapeSpaceWeight = 8.0;
    public double runCostWeight = 0.15;
    public int deadEndThreshold = 24;

    /** Candidate points on rings around (x,z): {@code rings} radii x {@code spokes} bearings. */
    public static List<double[]> ring(double x, double z, double[] radii, int spokes) {
        List<double[]> out = new ArrayList<>();
        for (double r : radii) {
            for (int i = 0; i < spokes; i++) {
                double a = 2 * Math.PI * i / spokes;
                out.add(new double[]{x + Math.cos(a) * r, z + Math.sin(a) * r});
            }
        }
        return out;
    }

    public Scored score(double meX, double meZ, Candidate c, List<Pursuer> pursuers, double mySpeed) {
        if (!c.standable()) return new Scored(c, Double.NEGATIVE_INFINITY, "not standable");
        if (c.hazard()) return new Scored(c, Double.NEGATIVE_INFINITY, "hazard");
        double run = Math.hypot(c.x() - meX, c.z() - meZ);
        // distance margin: how much further from the nearest pursuer we'd be than they are from us now, weighted by
        // who gets there first (time for us to arrive vs time for them)
        double minMargin = Double.POSITIVE_INFINITY;
        for (Pursuer p : pursuers) {
            double theirDist = Math.hypot(c.x() - p.x(), c.z() - p.z());
            double tMe = run / Math.max(0.05, mySpeed);
            double tThem = theirDist / Math.max(0.05, p.speed());
            double margin = theirDist - run + (tThem - tMe) * Math.max(0.05, mySpeed);
            minMargin = Math.min(minMargin, margin);
        }
        if (pursuers.isEmpty()) minMargin = run;
        double s = distanceWeight * minMargin;
        if (c.losBlocked()) s += losWeight;
        s += coverWeight * Math.min(6, c.coverBlocks());
        if (c.escapeSpace() < deadEndThreshold) s -= escapeSpaceWeight * (1.0 + (deadEndThreshold - c.escapeSpace()) / 4.0);
        s -= runCostWeight * run;
        if (c.heightDelta() < -4) s -= 4; // big drops cost health
        String why = String.format(java.util.Locale.ROOT, "margin=%.1f los=%s cover=%d space=%d run=%.1f",
            minMargin, c.losBlocked() ? "broken" : "open", c.coverBlocks(), c.escapeSpace(), run);
        return new Scored(c, s, why);
    }

    /** Best candidate, or null when none is usable (that's the "cornered" signal). */
    public Scored best(double meX, double meZ, List<Candidate> candidates, List<Pursuer> pursuers, double mySpeed) {
        return candidates.stream()
            .map(c -> score(meX, meZ, c, pursuers, mySpeed))
            .filter(s -> s.score() > Double.NEGATIVE_INFINITY)
            .max(Comparator.comparingDouble(Scored::score))
            .orElse(null);
    }

    /**
     * Cornered when no candidate gains distance on the nearest pursuer: the only legitimate "fight anyway" case
     * (decision 1).
     */
    public static boolean cornered(Scored best) {
        return best == null || best.score() < 0;
    }
}
