/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Combat visual smoothness helpers (plan S52-S65). Pure maths: no world, no input writes, no Baritone
 * pathing yaw. Callers apply the numbers to Meteor key/look claims only while Baritone is not walking
 * a path, so pathing snap and jump lock-on stay untouched.
 */
public final class CombatSmooth {
    private CombatSmooth() {
    }

    /**
     * S52: continuous lateral signal in {@code [-1, 1]} (sine plus a slow hash-noise octave).
     * Keys should only be pressed when {@link #strafeKey} is non-zero, so direction never flips
     * in a single tick.
     */
    public static double strafeCurve(long seed, long tick) {
        long h = mix(seed);
        double period = 20.0 + Math.floorMod(h, 13);
        double phase = (tick / period) * (Math.PI * 2.0) + (h & 0xff) * 0.0245436926;
        double sine = Math.sin(phase);
        double noise = valueNoise(seed, tick / 7.0);
        return clamp(sine * 0.75 + noise * 0.35, -1.0, 1.0);
    }

    /** Maps a curve to a key: +1 right, -1 left, 0 neither (deadzone). */
    public static int strafeKey(double curve, double deadzone) {
        double z = Math.max(0.0, deadzone);
        if (curve > z) return 1;
        if (curve < -z) return -1;
        return 0;
    }

    /**
     * S53: eased kite along a distance band. {@code -1} is full backpedal, {@code +1} is full
     * approach, {@code 0} is hold inside the band. Ramps over ~1.5 blocks behind min and ~2
     * blocks past max, so speed is not a binary toggle.
     */
    public static double distanceAlong(double dist, double min, double max) {
        if (!(min < max) || !Double.isFinite(dist)) return 0.0;
        if (dist >= min && dist <= max) return 0.0;
        if (dist < min) return -clamp((min - dist) / 1.5, 0.0, 1.0);
        return clamp((dist - max) / 2.0, 0.0, 1.0);
    }

    /** Sprint only when the approach is clearly outside the band. */
    public static boolean shouldSprint(double along) {
        return along > 0.7;
    }

    /**
     * S56: midpoint of a flee segment, offset perpendicular to the from→to heading so the
     * path arcs instead of back-pedaling in a straight line. {@code side} &lt; 0 uses the left
     * perpendicular.
     */
    public static double[] retreatArc(double fromX, double fromZ, double toX, double toZ, double arcMeters, int side) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6 || !Double.isFinite(len)) return new double[]{toX, toZ};
        double px = -dz / len;
        double pz = dx / len;
        double s = side < 0 ? -1.0 : 1.0;
        double a = Math.max(0.0, arcMeters) * s;
        return new double[]{(fromX + toX) * 0.5 + px * a, (fromZ + toZ) * 0.5 + pz * a};
    }

    /**
     * S56 score bonus for a flee bearing that is off the direct-away axis (prefers a curve).
     * {@code awayDeg} and {@code candidateDeg} are atan2 bearings in degrees.
     */
    public static double retreatArcBonus(double awayDeg, double candidateDeg) {
        double diff = Math.abs(wrapDegrees(candidateDeg - awayDeg));
        if (diff > 20.0 && diff < 75.0) return 2.0;
        if (diff <= 15.0) return -1.0; // penalize a straight back-pedal
        return 0.0;
    }

    /** S57: raise-shield reaction, 4–10 ticks (~200–500 ms). */
    public static int shieldReactionTicks(long seed, long n) {
        return 4 + (int) Math.floorMod(mix(seed + n * 31L), 7);
    }

    /** S57: hold after the threat ends before lowering, 3–8 ticks. */
    public static int shieldLowerTicks(long seed, long n) {
        return 3 + (int) Math.floorMod(mix(seed + n * 17L + 11), 6);
    }

    /** S59: ticks to let a smoothed look settle before a pearl/bow release. */
    public static int aimSettleTicks(double angleDeg) {
        double a = Math.abs(angleDeg);
        if (a < 8.0) return 1;
        if (a < 25.0) return 3;
        if (a < 60.0) return 6;
        return 10;
    }

    public static boolean aimSettled(double yawErrorDeg, double pitchErrorDeg, double yawTol, double pitchTol) {
        return Math.abs(yawErrorDeg) <= yawTol && Math.abs(pitchErrorDeg) <= pitchTol;
    }

    /**
     * S61: add a little downward pitch as vertical speed approaches zero while airborne
     * (the jump peak). Minecraft pitch: positive is down.
     */
    public static float critPitch(float basePitch, double vy, boolean airborne) {
        if (!airborne) return basePitch;
        double t = clamp(1.0 - Math.abs(vy) / 0.42, 0.0, 1.0);
        return (float) clamp(basePitch + 8.0 * t, -90.0, 90.0);
    }

    /** S64: extra ticks on a crystal place/break delay for a natural rhythm. FAST keeps the base. */
    public static int crystalRhythmGap(int base, long n, boolean legit) {
        int b = Math.max(0, base);
        if (!legit) return b;
        int jitter = 1 + (int) Math.floorMod(mix(n * 13L + 7), 3);
        return b + jitter;
    }

    /** S65: ticks to keep combat inputs released after respawn. */
    public static int respawnFadeTicks() {
        return 20;
    }

    public static double wrapDegrees(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double valueNoise(long seed, double t) {
        int i = (int) Math.floor(t);
        double f = t - i;
        double a = hash01(seed, i);
        double b = hash01(seed, i + 1);
        double u = f * f * (3.0 - 2.0 * f);
        return (a * (1.0 - u) + b * u) * 2.0 - 1.0;
    }

    private static double hash01(long seed, int i) {
        long h = mix(seed ^ (long) i * 0x9E3779B97F4A7C15L);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
