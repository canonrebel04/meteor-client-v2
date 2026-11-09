/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

/** Pure orbit math for {@link TargetStrafe}. */
public final class TargetStrafeMath {
    private TargetStrafeMath() {}

    /**
     * Horizontal velocity that orbits (tx, tz) at {@code radius}, moving {@code speed} blocks/tick.
     * The tangent component is blended with a radial correction toward the orbit ring.
     *
     * @param direction +1 counter-clockwise, -1 clockwise
     * @return {vx, vz}
     */
    public static double[] orbit(double px, double pz, double tx, double tz, double radius, int direction, double speed) {
        double dx = px - tx;
        double dz = pz - tz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0e-4) {
            // On top of the target: step straight out along +X.
            return new double[]{speed, 0};
        }
        double rx = dx / dist;
        double rz = dz / dist;
        // Tangent (perpendicular to radial).
        double tanX = -rz * direction;
        double tanZ = rx * direction;
        // Radial error: positive = too far, pull in.
        double err = dist - radius;
        double corr = Math.max(-1, Math.min(1, err / Math.max(radius, 0.5)));
        double vx = tanX - rx * corr;
        double vz = tanZ - rz * corr;
        double len = Math.sqrt(vx * vx + vz * vz);
        return new double[]{vx / len * speed, vz / len * speed};
    }
}
