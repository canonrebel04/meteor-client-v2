/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.List;

/**
 * Allied play (C40, C82, C83), pure. Flank: stand at the angle around the target farthest from every ally's angle;
 * guard: step between a low-HP ally and the target; focus fire: the target most allies already fight; regroup: the
 * allies' centroid pushed away from the threats' centroid.
 */
public final class Formation {
    public record Ally(double x, double z, double health, int targetId) {
    }

    private Formation() {
    }

    /** Flank point at {@code radius} around (tx,tz). */
    public static double[] flank(double tx, double tz, List<Ally> allies, double radius, double myX, double myZ) {
        if (allies.isEmpty()) return new double[]{myX, myZ};
        double bestA = 0, bestGap = -1;
        for (int i = 0; i < 24; i++) {
            double a = 2 * Math.PI * i / 24;
            double gap = Math.PI;
            for (Ally al : allies) {
                double aa = Math.atan2(al.z() - tz, al.x() - tx);
                double d = Math.abs(Math.atan2(Math.sin(a - aa), Math.cos(a - aa)));
                gap = Math.min(gap, d);
            }
            // tie-break: closer to where we already are
            double mine = Math.atan2(myZ - tz, myX - tx);
            double pref = gap - 0.1 * Math.abs(Math.atan2(Math.sin(a - mine), Math.cos(a - mine)));
            if (pref > bestGap) {
                bestGap = pref;
                bestA = a;
            }
        }
        return new double[]{tx + Math.cos(bestA) * radius, tz + Math.sin(bestA) * radius};
    }

    /** Guard point between a low-HP ally (≤ {@code lowHp}) and the target, or null when nobody needs it. */
    public static double[] guard(double tx, double tz, List<Ally> allies, double lowHp) {
        Ally weak = null;
        for (Ally a : allies) if (a.health() <= lowHp && (weak == null || a.health() < weak.health())) weak = a;
        if (weak == null) return null;
        double dx = tx - weak.x(), dz = tz - weak.z(), d = Math.max(1e-6, Math.hypot(dx, dz));
        double k = Math.min(1.8, d / 2);
        return new double[]{weak.x() + dx / d * k, weak.z() + dz / d * k};
    }

    /** Target id most allies are fighting (ties: lowest id), or -1. */
    public static int focus(List<Ally> allies) {
        java.util.TreeMap<Integer, Integer> n = new java.util.TreeMap<>();
        for (Ally a : allies) if (a.targetId() >= 0) n.merge(a.targetId(), 1, Integer::sum);
        int best = -1, bn = 0;
        for (var e : n.entrySet()) if (e.getValue() > bn) {
            bn = e.getValue();
            best = e.getKey();
        }
        return best;
    }

    /** Rally point: allies' centroid, pushed {@code push} blocks away from the threats' centroid. */
    public static double[] rally(List<Ally> allies, List<double[]> threats, double push) {
        if (allies.isEmpty()) return null;
        double ax = 0, az = 0;
        for (Ally a : allies) {
            ax += a.x();
            az += a.z();
        }
        ax /= allies.size();
        az /= allies.size();
        if (threats.isEmpty()) return new double[]{ax, az};
        double hx = 0, hz = 0;
        for (double[] t : threats) {
            hx += t[0];
            hz += t[1];
        }
        hx /= threats.size();
        hz /= threats.size();
        double dx = ax - hx, dz = az - hz, d = Math.max(1e-6, Math.hypot(dx, dz));
        return new double[]{ax + dx / d * push, az + dz / d * push};
    }
}
