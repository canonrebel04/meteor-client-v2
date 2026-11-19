/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Hit selection (C28), pure: the point of the target's (slightly shrunk) hitbox closest to where we already look,
 * so the camera turns as little as possible. Falls back to the box point nearest our eyes.
 */
public final class HitPoint {
    private HitPoint() {
    }

    /**
     * @param eye  our eye position {x,y,z}
     * @param look unit look vector
     * @param box  {minX,minY,minZ,maxX,maxY,maxZ}
     */
    public static double[] aim(double[] eye, double[] look, double[] box, double margin) {
        double[] b = shrink(box, margin);
        // project the box centre onto the look ray, take the box point nearest that ray point (a few refinements)
        double[] c = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        double t = Math.max(0, dot(sub(c, eye), look));
        double[] p = clamp(add(eye, mul(look, t)), b);
        for (int i = 0; i < 4; i++) {
            t = Math.max(0, dot(sub(p, eye), look));
            p = clamp(add(eye, mul(look, t)), b);
        }
        return p;
    }

    /** Rotation (degrees, yaw+pitch summed abs) needed to look from {@code eye} at {@code p}, starting at {@code yaw,pitch}. */
    public static double turn(double[] eye, double[] p, double yaw, double pitch) {
        double dx = p[0] - eye[0], dy = p[1] - eye[1], dz = p[2] - eye[2];
        double ty = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        double tp = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        double dyaw = ((ty - yaw) % 360 + 540) % 360 - 180;
        return Math.abs(dyaw) + Math.abs(tp - pitch);
    }

    static double[] shrink(double[] b, double m) {
        double mx = Math.min(m, (b[3] - b[0]) / 2 - 0.01), my = Math.min(m, (b[4] - b[1]) / 2 - 0.01), mz = Math.min(m, (b[5] - b[2]) / 2 - 0.01);
        return new double[]{b[0] + mx, b[1] + my, b[2] + mz, b[3] - mx, b[4] - my, b[5] - mz};
    }

    private static double[] clamp(double[] p, double[] b) {
        return new double[]{Math.max(b[0], Math.min(b[3], p[0])), Math.max(b[1], Math.min(b[4], p[1])), Math.max(b[2], Math.min(b[5], p[2]))};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] mul(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
