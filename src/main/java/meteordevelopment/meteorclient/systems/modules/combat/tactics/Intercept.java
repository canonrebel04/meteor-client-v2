/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/** Predictive intercept (C39), pure: where to run to meet a moving target, given our speed (blocks/tick). */
public final class Intercept {
    private Intercept() {
    }

    /**
     * @return {x, z, ticks} of the earliest meeting point, or the target's current spot (ticks = -1) if we can't
     * catch it
     */
    public static double[] solve(double mx, double mz, double tx, double tz, double vx, double vz, double speed, int maxTicks) {
        double rx = tx - mx, rz = tz - mz;
        double a = vx * vx + vz * vz - speed * speed;
        double b = 2 * (rx * vx + rz * vz);
        double c = rx * rx + rz * rz;
        double t;
        if (Math.abs(a) < 1e-9) t = b == 0 ? -1 : -c / b;
        else {
            double disc = b * b - 4 * a * c;
            if (disc < 0) t = -1;
            else {
                double s = Math.sqrt(disc);
                double t1 = (-b - s) / (2 * a), t2 = (-b + s) / (2 * a);
                t = Math.min(t1, t2) > 0 ? Math.min(t1, t2) : Math.max(t1, t2);
            }
        }
        if (t <= 0 || t > maxTicks) return new double[]{tx, tz, -1};
        return new double[]{tx + vx * t, tz + vz * t, t};
    }
}
