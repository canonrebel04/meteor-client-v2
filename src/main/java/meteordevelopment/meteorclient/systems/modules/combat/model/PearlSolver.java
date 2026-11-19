/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Ender pearl trajectory solver (C44). Vanilla thrown-projectile physics per tick: position += velocity, then
 * velocity *= 0.99 (drag) and velocity.y -= 0.03 (gravity); pearls launch at 1.5 blocks/tick.
 */
public final class PearlSolver {
    public static final double SPEED = 1.5, GRAVITY = 0.03, DRAG = 0.99;

    /** World probe: is the block at (x,y,z) solid (stops the pearl). */
    @FunctionalInterface
    public interface Solid {
        boolean test(int x, int y, int z);
    }

    public record Landing(double x, double y, double z, int ticks, boolean hit) {
    }

    private PearlSolver() {
    }

    /** Simulates a throw from eye position with yaw/pitch in degrees (MC convention). */
    public static Landing simulate(double x, double y, double z, float yaw, float pitch, Solid world, int maxTicks) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double vx = -Math.sin(yr) * Math.cos(pr) * SPEED;
        double vy = -Math.sin(pr) * SPEED;
        double vz = Math.cos(yr) * Math.cos(pr) * SPEED;
        for (int t = 1; t <= maxTicks; t++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            // sub-step so a fast pearl doesn't skip a 1-block wall
            int steps = (int) Math.ceil(Math.max(Math.abs(vx), Math.max(Math.abs(vy), Math.abs(vz))) / 0.5);
            for (int s = 1; s <= steps; s++) {
                double f = (double) s / steps;
                double sx = x + vx * f, sy = y + vy * f, sz = z + vz * f;
                if (world.test((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz))) {
                    return new Landing(sx, sy, sz, t, true);
                }
            }
            x = nx;
            y = ny;
            z = nz;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
            if (y < -128) break;
        }
        return new Landing(x, y, z, maxTicks, false);
    }

    /** Throw solution: yaw/pitch and where it lands. */
    public record Solution(float yaw, float pitch, Landing landing) {
    }

    /**
     * Searches pitches along {@code yaw} for the landing furthest from the pursuer that passes {@code accept}
     * (landing validation: standable, no void/lava, not inside the pursuer's reach). Null when none qualifies.
     */
    public static Solution bestAlongYaw(double x, double y, double z, float yaw, Solid world, double pursuerX, double pursuerZ,
                                        java.util.function.Predicate<Landing> accept) {
        Solution best = null;
        double bestD = -1;
        for (int pitch = -45; pitch <= 10; pitch += 5) {
            Landing l = simulate(x, y, z, yaw, pitch, world, 120);
            if (!l.hit() || !accept.test(l)) continue;
            double d = Math.hypot(l.x() - pursuerX, l.z() - pursuerZ);
            if (d > bestD) {
                bestD = d;
                best = new Solution(yaw, pitch, l);
            }
        }
        return best;
    }
}
