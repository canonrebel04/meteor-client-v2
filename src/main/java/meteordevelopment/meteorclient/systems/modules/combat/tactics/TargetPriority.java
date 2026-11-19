/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.List;

/**
 * Multi-threat kill order and sweep-aware target choice (C24, C81, C84), pure. Ranged attackers and healers
 * (witches, players drinking) first, then whoever deals the most damage per remaining HP; a target that has other
 * enemies within the sweep box scores extra while we hold a sword; picking a target near idle mobs (that would join
 * in) costs.
 */
public final class TargetPriority {
    public record Threat(int id, double x, double y, double z, double health, double dps, boolean ranged, boolean healer,
                         boolean player, boolean aggressive, double distance) {
    }

    private TargetPriority() {
    }

    /** Vanilla sweep: enemies within 1 block (horizontally, 0.25 vertically) of the struck target's box, in our reach. */
    public static int sweepCount(Threat t, List<Threat> all, double reach) {
        int n = 0;
        for (Threat o : all) {
            if (o.id() == t.id() || !o.aggressive()) continue;
            double dx = o.x() - t.x(), dz = o.z() - t.z();
            if (dx * dx + dz * dz <= 1.6 * 1.6 && Math.abs(o.y() - t.y()) <= 0.75 && o.distance() <= reach + 0.5) n++;
        }
        return n;
    }

    /** Idle (non-aggressive) mobs within 8 blocks of where we'd fight {@code t}: pulling them is a risk (C84). */
    public static int pullRisk(Threat t, List<Threat> all) {
        int n = 0;
        for (Threat o : all) {
            if (o.id() == t.id() || o.aggressive()) continue;
            double dx = o.x() - t.x(), dy = o.y() - t.y(), dz = o.z() - t.z();
            if (dx * dx + dy * dy + dz * dz <= 64) n++;
        }
        return n;
    }

    public static double score(Threat t, List<Threat> all, double reach, boolean sweepWeapon) {
        if (!t.aggressive()) return -1e9 + -t.distance(); // never prefer hitting idle mobs
        double s = 0;
        if (t.ranged()) s += 40;
        if (t.healer()) s += 30;
        s += 20 * t.dps() / Math.max(1, t.health());   // damage per remaining hp: kill what hurts most and dies fastest
        s += 10 / Math.max(1, t.health());
        s -= 2.5 * t.distance();
        if (t.distance() <= reach) s += 15;               // already in reach: don't walk away from a hit
        if (sweepWeapon) s += 6 * sweepCount(t, all, reach);
        s -= 8 * pullRisk(t, all);
        return s;
    }

    /** Best target id, or -1. */
    public static int pick(List<Threat> all, double reach, boolean sweepWeapon) {
        int best = -1;
        double bs = Double.NEGATIVE_INFINITY;
        for (Threat t : all) {
            double s = score(t, all, reach, sweepWeapon);
            if (s > bs) {
                bs = s;
                best = t.id();
            }
        }
        return bs <= -1e8 ? -1 : best;
    }
}
