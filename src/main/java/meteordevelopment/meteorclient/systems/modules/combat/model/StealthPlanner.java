/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * STEALTH mode (C92): approach from outside the target's view, sneak inside a radius, strike from behind, and leave
 * quietly (no sprinting while watched, no jumping). Angles in degrees, Minecraft yaw convention. Pure.
 */
public final class StealthPlanner {
    public record Advice(boolean sneak, boolean sprint, boolean jumpOk, boolean flank, double flankX, double flankZ,
                         boolean holdAttack, boolean seen) {
    }

    /** The target "sees" us when we are inside this half-angle of its view direction. */
    public static final double VIEW_HALF_ANGLE = 60;
    /** Behind the target: at least this far from its view direction. */
    public static final double BACKSTAB_ANGLE = 110;
    public static final double SNEAK_RADIUS = 8;
    public static final double FLANK_DISTANCE = 2.2;

    private StealthPlanner() {
    }

    /** Angle between the target's view yaw and the direction from it to us (0 = looking straight at us). */
    public static double viewAngle(double targetX, double targetZ, double targetYaw, double myX, double myZ) {
        double toMe = Math.toDegrees(Math.atan2(-(myX - targetX), myZ - targetZ));
        double d = ((toMe - targetYaw) % 360 + 540) % 360 - 180;
        return Math.abs(d);
    }

    /**
     * @param reach our melee reach
     */
    public static Advice advise(double myX, double myZ, double targetX, double targetZ, double targetYaw,
                                double reach, boolean targetHasLos) {
        double dist = Math.hypot(targetX - myX, targetZ - myZ);
        double angle = viewAngle(targetX, targetZ, targetYaw, myX, myZ);
        boolean seen = targetHasLos && angle <= VIEW_HALF_ANGLE;
        boolean behind = angle >= BACKSTAB_ANGLE;
        boolean close = dist <= SNEAK_RADIUS;
        // point behind the target, opposite its view
        double yawRad = Math.toRadians(targetYaw);
        double lookX = -Math.sin(yawRad), lookZ = Math.cos(yawRad);
        double fx = targetX - lookX * FLANK_DISTANCE, fz = targetZ - lookZ * FLANK_DISTANCE;
        boolean flank = !behind && !seen && dist > reach * 0.6;
        // seen: stop creeping and just fight normally (no point sneaking, and sneaking halves our speed)
        boolean sneak = close && !seen;
        boolean sprint = !close && !seen;
        // hold the swing until behind, unless they've noticed us or we're already at point blank
        boolean holdAttack = !seen && !behind && dist > 1.2;
        return new Advice(sneak, sprint, seen, flank, fx, fz, holdAttack, seen);
    }

    /** Silent disengage: walk (no sprint particles/noise) while unseen; sprint once seen; never jump. */
    public static boolean disengageSprint(boolean seen) {
        return seen;
    }
}
