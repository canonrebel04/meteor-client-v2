/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Melee timing (C21-C23), pure. Swings only on a charged cooldown; schedules legit jump-crits (a real jump, the hit
 * lands while falling; no packet crits); and the opt-in knockback taps after a hit.
 */
public final class MeleeTiming {
    /** Vanilla full-damage threshold we wait for (C21). */
    public static final float READY = 0.9f;

    public enum Crit { ATTACK, HOLD, JUMP }

    public enum Tap { NONE, W_TAP, S_TAP, BLOCK_HIT }

    /**
     * @param state           self state that vanilla's crit rule checks
     * @param charge          attack strength now (0..1)
     * @param ticksToCharged  ticks until charge reaches {@link #READY}
     * @param inReach         target is inside attack reach now
     * @param closeToReach    target is within reach + 1 (a jump closes the gap)
     * @param airborneTicks   ticks since we left the ground (0 on ground)
     */
    public record State(boolean onGround, boolean falling, double fallDistance, boolean inWater, boolean climbing,
                        boolean inWeb, boolean blind, boolean levitating, boolean riding, boolean sprinting) {
        /** Vanilla: a hit is critical while falling, not on ground, not climbing/in water/blind/riding. */
        public boolean critEligible() {
            return falling && !onGround && fallDistance > 0 && !inWater && !climbing && !inWeb && !blind && !levitating && !riding;
        }

        /** A jump only works (and only looks normal) on solid ground, free of water, webs and ladders. */
        public boolean canJump() {
            return onGround && !inWater && !climbing && !inWeb && !levitating && !riding;
        }
    }

    private MeleeTiming() {
    }

    public static boolean swingReady(float charge) {
        return charge >= READY;
    }

    /**
     * C22 crit scheduler. Jump about 3 ticks before the cooldown is ready so the swing lands on the way down
     * (vanilla jump: apex at ~6 ticks, landing at ~12); while rising hold the swing up to {@code maxHold} ticks;
     * fall back to a normal hit rather than waste a charged swing.
     */
    public static Crit crit(boolean enabled, State s, float charge, int ticksToCharged, boolean inReach, boolean closeToReach,
                            int airborneTicks, int maxHold) {
        if (!enabled) return swingReady(charge) && inReach ? Crit.ATTACK : Crit.HOLD;
        if (s.critEligible()) return swingReady(charge) && inReach ? Crit.ATTACK : Crit.HOLD;
        if (!s.onGround() && !s.inWater() && !s.climbing() && airborneTicks > 0 && airborneTicks < maxHold) {
            return Crit.HOLD; // rising: the crit comes in a few ticks
        }
        if (s.canJump() && closeToReach && ticksToCharged <= 3) return Crit.JUMP;
        return swingReady(charge) && inReach ? Crit.ATTACK : Crit.HOLD;
    }

    /** Ticks until a cooldown at {@code charge} reaches READY, at {@code attackSpeed} attacks/s. */
    public static int ticksToReady(float charge, double attackSpeed) {
        if (charge >= READY) return 0;
        double period = 20.0 / Math.max(0.1, attackSpeed);
        return (int) Math.ceil((READY - charge) * period);
    }

    /**
     * C23 key state for the {@code ticksSinceHit} ticks after a sprint hit, as {forward, back, sprint, use}.
     * W-tap: release forward for 1 tick (sprint reset); S-tap: tap back for 2 ticks (keeps spacing);
     * block-hit: raise the shield for 2 ticks (only with one in the offhand).
     */
    public static boolean[] tapKeys(Tap tap, int ticksSinceHit, boolean forwardHeld, boolean haveOffhandShield) {
        boolean fwd = forwardHeld, back = false, sprint = true, use = false;
        switch (tap) {
            case W_TAP -> {
                if (ticksSinceHit == 0) {
                    fwd = false;
                    sprint = false;
                }
            }
            case S_TAP -> {
                if (ticksSinceHit <= 1) {
                    fwd = false;
                    back = true;
                    sprint = false;
                }
            }
            case BLOCK_HIT -> {
                if (ticksSinceHit <= 1 && haveOffhandShield) use = true;
            }
            default -> {
            }
        }
        return new boolean[]{fwd, back, sprint, use};
    }

    /** Taps last at most this many ticks after a hit. */
    public static int tapLength(Tap tap) {
        return switch (tap) {
            case NONE -> 0;
            case W_TAP -> 1;
            default -> 2;
        };
    }
}
