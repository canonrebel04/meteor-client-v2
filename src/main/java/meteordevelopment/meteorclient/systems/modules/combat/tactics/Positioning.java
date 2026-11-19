/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.function.IntBinaryOperator;

/**
 * Where to stand (C29, C31, C33-C37, C87), pure. Candidate spots on a ring around the target are scored by: our
 * hazards (drops/void/lava/traps; void and lava margins doubled in the End/Nether), high ground, our back to a drop,
 * the knockback direction (me -> target) pointing the target at a combo hazard, and staying in water (no fall
 * damage). The strafe pattern flips direction at irregular intervals.
 */
public final class Positioning {
    /** Hazard lookup at a block offset from the target's feet: (dx, dz) -> Hazard ordinal; height via {@link Ground}. */
    public interface Ground {
        Hazard hazard(int dx, int dz);

        /** Standing height (relative to target feet) at offset, or Integer.MIN_VALUE for no floor nearby. */
        int height(int dx, int dz);
    }

    public record Spot(double dx, double dz, double score, Hazard pushInto) {
    }

    public enum Dim { OVERWORLD, NETHER, END }

    private Positioning() {
    }

    /** Hazard the target would be knocked toward, scanning 1..3 blocks along the unit (ux,uz) from it. */
    public static Hazard pushTarget(Ground g, double ux, double uz) {
        for (int d = 1; d <= 3; d++) {
            int x = (int) Math.round(ux * d), z = (int) Math.round(uz * d);
            Hazard h = g.hazard(x, z);
            if (h == Hazard.WALL) return Hazard.WALL;
            if (h.comboTarget) return h;
        }
        return Hazard.NONE;
    }

    public static Spot best(Ground g, double radius, int spokes, Dim dim, boolean preferWater, boolean comboPush) {
        Spot best = null;
        for (int i = 0; i < spokes; i++) {
            double a = 2 * Math.PI * i / spokes;
            double dx = Math.cos(a) * radius, dz = Math.sin(a) * radius;
            int bx = (int) Math.round(dx), bz = (int) Math.round(dz);
            double s = 0;
            Hazard here = g.hazard(bx, bz);
            int h = g.height(bx, bz);
            if (h == Integer.MIN_VALUE) s -= 100;                               // no floor
            if (here == Hazard.WALL) s -= 50;
            s -= here.cost * (dimMul(dim, here));
            // our back: the spot one further out
            int ox = (int) Math.round(dx * (radius + 1.2) / radius), oz = (int) Math.round(dz * (radius + 1.2) / radius);
            Hazard behind = g.hazard(ox, oz);
            if (behind == Hazard.DROP || behind == Hazard.VOID || behind == Hazard.LAVA) s -= behind.cost * 1.5 * dimMul(dim, behind);
            if (h != Integer.MIN_VALUE) s += Math.max(-2, Math.min(2, h)) * 3.0;  // high ground (C33)
            if (preferWater && here == Hazard.WATER) s += 6;                    // C37: no fall damage, slows their crits
            Hazard push = Hazard.NONE;
            if (comboPush) {
                push = pushTarget(g, -dx / radius, -dz / radius);              // we hit from here -> target flies away from us
                if (push.comboTarget) s += Math.min(25, push.cost);
                else if (push == Hazard.WALL) s += 4;                          // pinned on a wall: easy combo
            }
            if (best == null || s > best.score()) best = new Spot(dx, dz, s, push);
        }
        return best;
    }

    static double dimMul(Dim dim, Hazard h) {
        if (dim == Dim.END && (h == Hazard.VOID || h == Hazard.DROP)) return 2;
        if (dim == Dim.NETHER && (h == Hazard.LAVA || h == Hazard.FIRE)) return 2;
        return 1;
    }

    /** C31 strafe: +1/-1 direction or 0 (brief pause), flipping at seeded irregular intervals of 8-20 ticks. */
    public static int strafe(long seed, long tick) {
        long t = 0;
        int dir = 1;
        long k = 0;
        while (true) {
            long h = mix(seed + k);
            long len = 8 + Math.floorMod(h, 13);
            if (tick < t + len) {
                // 1 in 6 segments starts with a 2-tick pause
                return (Math.floorMod(h >> 8, 6) == 0 && tick < t + 2) ? 0 : dir;
            }
            t += len;
            dir = -dir;
            k++;
            if (k > 100000) return dir;
        }
    }

    /** Strafe direction that doesn't lead into a hazard: flip if the side we'd move to is bad. */
    public static int safeStrafe(int dir, IntBinaryOperator sideHazardCost) {
        if (dir == 0) return 0;
        int here = sideHazardCost.applyAsInt(dir, 0);
        int other = sideHazardCost.applyAsInt(-dir, 0);
        if (here >= 10 && other < here) return -dir;
        if (here >= 10) return 0;
        return dir;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
