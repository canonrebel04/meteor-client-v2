/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.Random;

/**
 * Plan S51/S54/S55/S60/S62: KillAura's visible-camera aim gate. Pure state machine fed once per tick:
 * <ul>
 *     <li>S51: the camera turns through the smoothing layer (no snap); attacks only land once the crosshair is
 *     on the target's box (tolerance = its angular radius), so nothing hits "around" the camera.</li>
 *     <li>S54: a new primary target costs a short reaction pause before the turn starts.</li>
 *     <li>S55/S60: swing when the cooldown is fully charged, plus a small random 0..n tick cadence delay drawn
 *     once per charge (never a partial swing, never frame-perfect).</li>
 *     <li>S62: after taking a hit the aim holds for a few ticks, then re-acquires smoothly.</li>
 * </ul>
 */
public final class AuraAim {
    public int reactionMin = 2;
    public int reactionMax = 5;
    public int cadenceJitter = 2;
    public int hurtHoldMin = 2;
    public int hurtHoldMax = 4;
    /** Minimum crosshair tolerance in degrees (tiny/far targets). */
    public double minTolerance = 1.5;

    private final Random random;
    private int targetId = Integer.MIN_VALUE;
    private int switchHold;
    private int hurtHold;
    private int cadenceWait = -1;

    public AuraAim(long seed) {
        this.random = new Random(seed);
    }

    private int between(int lo, int hi) {
        return hi <= lo ? Math.max(0, lo) : lo + random.nextInt(hi - lo + 1);
    }

    /** Call once per tick with the primary target (or {@code Integer.MIN_VALUE} for none). */
    public void tick(int primaryId, boolean justHurt) {
        if (switchHold > 0) switchHold--;
        if (hurtHold > 0) hurtHold--;
        if (primaryId != targetId) {
            if (targetId != Integer.MIN_VALUE && primaryId != Integer.MIN_VALUE) switchHold = between(reactionMin, reactionMax);
            targetId = primaryId;
            cadenceWait = -1;
        }
        if (justHurt) hurtHold = between(hurtHoldMin, hurtHoldMax);
    }

    /** May the camera be turned towards the target this tick. */
    public boolean mayAim() {
        return switchHold == 0 && hurtHold == 0;
    }

    /** Crosshair tolerance (degrees) for a target of half-width {@code halfWidth} at {@code distance}. */
    public double tolerance(double halfWidth, double distance) {
        if (distance <= 1e-3) return 180;
        return Math.max(minTolerance, Math.toDegrees(Math.atan2(halfWidth, distance)));
    }

    /** Angular error between the camera and the aim direction, degrees (yaw wrapped). */
    public static double error(double camYaw, double camPitch, double aimYaw, double aimPitch) {
        double dy = wrap(aimYaw - camYaw);
        double dp = aimPitch - camPitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    public static double wrap(double d) {
        d %= 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /**
     * Swing now? Needs a full charge, the crosshair on the target and no reaction hold; once charged a random
     * 0..{@link #cadenceJitter} tick delay is drawn and counted down first.
     */
    public boolean attackReady(boolean charged, boolean onTarget) {
        if (!charged) {
            cadenceWait = -1;
            return false;
        }
        if (cadenceWait < 0) cadenceWait = between(0, cadenceJitter);
        if (cadenceWait > 0) {
            cadenceWait--;
            return false;
        }
        if (!onTarget || switchHold > 0) return false;
        cadenceWait = -1;
        return true;
    }

    public int switchHold() {
        return switchHold;
    }

    public int hurtHold() {
        return hurtHold;
    }
}
