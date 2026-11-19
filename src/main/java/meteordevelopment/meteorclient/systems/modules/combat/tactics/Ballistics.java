/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Projectile aim (C61, C62, C63, C66, C69), pure. Simulates vanilla projectiles tick by tick (gravity, then drag)
 * to find the pitch that hits a point, and leads a moving target by the flight time.
 */
public final class Ballistics {
    public enum Projectile {
        ARROW(3.0, 0.05, 0.99), CROSSBOW_ARROW(3.15, 0.05, 0.99), TRIDENT(2.5, 0.05, 0.99),
        SNOWBALL(1.5, 0.03, 0.99), EGG(1.5, 0.03, 0.99), PEARL(1.5, 0.03, 0.99), SPLASH_POTION(0.5, 0.05, 0.99),
        WIND_CHARGE(1.5, 0.0, 1.0);

        public final double speed, gravity, drag;

        Projectile(double speed, double gravity, double drag) {
            this.speed = speed;
            this.gravity = gravity;
            this.drag = drag;
        }
    }

    public record Shot(double pitch, int ticks, double missBy) {
        public boolean hits(double tolerance) {
            return missBy <= tolerance;
        }
    }

    private Ballistics() {
    }

    /** Vanilla bow power after {@code useTicks} of drawing (0..1). */
    public static double bowPower(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2) / 3;
        return Math.min(1, f);
    }

    /** Draw ticks needed: full draw beyond 15 blocks, ~0.7 power close (faster shots, still flat enough). */
    public static int drawTicks(double distance) {
        if (distance >= 15) return 20;
        if (distance >= 8) return 16;
        return 12;
    }

    /** Height reached after flying {@code horiz} blocks at {@code pitch} (degrees, negative = up), with ticks taken. */
    public static double[] fly(double v0, double gravity, double drag, double pitchDeg, double horiz, int maxTicks) {
        double p = Math.toRadians(-pitchDeg);
        double vh = Math.cos(p) * v0, vy = Math.sin(p) * v0;
        double x = 0, y = 0;
        for (int t = 1; t <= maxTicks; t++) {
            double nx = x + vh, ny = y + vy;
            if (nx >= horiz) {
                double f = (horiz - x) / Math.max(1e-9, vh);
                return new double[]{y + vy * f, t};
            }
            x = nx;
            y = ny;
            vh *= drag;
            vy = vy * drag - gravity;
            if (vh < 1e-4) break;
        }
        return new double[]{Double.NaN, maxTicks};
    }

    /** Flattest pitch that hits (horiz, dy) with speed v0; NaN missBy = out of range. */
    public static Shot solve(Projectile pr, double power, double horiz, double dy) {
        double v0 = pr.speed * Math.max(0.05, power);
        double lo = -60, hi = 60; // pitch degrees: negative aims up
        double[] atLo = fly(v0, pr.gravity, pr.drag, lo, horiz, 200);
        // find the max reachable height pitch region by scanning, then bisect on the flat branch
        double bestPitch = Double.NaN, bestMiss = Double.MAX_VALUE;
        int bestT = 0;
        double prevPitch = hi;
        double[] prev = fly(v0, pr.gravity, pr.drag, hi, horiz, 200);
        for (double a = hi - 2; a >= lo - 1e-9; a -= 2) {
            double[] cur = fly(v0, pr.gravity, pr.drag, a, horiz, 200);
            if (!Double.isNaN(prev[0]) && !Double.isNaN(cur[0]) && (prev[0] - dy) * (cur[0] - dy) <= 0) {
                double l = a, h = prevPitch;
                for (int i = 0; i < 30; i++) {
                    double m = (l + h) / 2;
                    double[] r = fly(v0, pr.gravity, pr.drag, m, horiz, 200);
                    if (Double.isNaN(r[0])) break;
                    double[] rl = fly(v0, pr.gravity, pr.drag, l, horiz, 200);
                    if ((rl[0] - dy) * (r[0] - dy) <= 0) h = m;
                    else l = m;
                }
                double m = (l + h) / 2;
                double[] r = fly(v0, pr.gravity, pr.drag, m, horiz, 200);
                return new Shot(m, (int) r[1], Math.abs(r[0] - dy));
            }
            if (!Double.isNaN(cur[0]) && Math.abs(cur[0] - dy) < bestMiss) {
                bestMiss = Math.abs(cur[0] - dy);
                bestPitch = a;
                bestT = (int) cur[1];
            }
            prev = cur;
            prevPitch = a;
        }
        if (Double.isNaN(atLo[0]) && Double.isNaN(bestPitch)) return new Shot(Double.NaN, 0, Double.NaN);
        return new Shot(bestPitch, bestT, bestMiss);
    }

    /**
     * Lead a target moving at (vx,vy,vz)/tick: iterate flight time -> future position.
     *
     * @return {yawDeg, pitchDeg, ticks, missBy}
     */
    public static double[] lead(Projectile pr, double power, double ex, double ey, double ez, double tx, double ty, double tz,
                                double vx, double vy, double vz) {
        double fx = tx, fy = ty, fz = tz;
        Shot s = null;
        for (int i = 0; i < 4; i++) {
            double h = Math.hypot(fx - ex, fz - ez);
            s = solve(pr, power, h, fy - ey);
            if (Double.isNaN(s.pitch())) break;
            fx = tx + vx * s.ticks();
            fz = tz + vz * s.ticks();
            fy = ty + Math.max(-1.0, Math.min(1.0, vy * s.ticks()));
        }
        double yaw = Math.toDegrees(Math.atan2(fz - ez, fx - ex)) - 90;
        if (s == null) return new double[]{yaw, Double.NaN, 0, Double.NaN};
        return new double[]{yaw, s.pitch(), s.ticks(), s.missBy()};
    }
}
