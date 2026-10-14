/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

/** Pure projection for {@link EntityRadarHud}: world offset to radar pixels, player facing up. */
public final class RadarMath {
    private RadarMath() {}

    /**
     * @param dx       entity x minus player x
     * @param dz       entity z minus player z
     * @param yawDeg   player yaw in Minecraft degrees (0 = +Z/south, 90 = -X/west)
     * @param range    world blocks from centre to edge
     * @param radiusPx radar half-size in pixels
     * @return {px, py} offset from the radar centre (py positive = down), or null if outside range
     */
    public static double[] project(double dx, double dz, double yawDeg, double range, double radiusPx) {
        if (dx * dx + dz * dz > range * range) return null;
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
        double right = dx * rx + dz * rz;
        double fwd = dx * fx + dz * fz;
        double k = radiusPx / range;
        return new double[]{right * k, -fwd * k};
    }
}
