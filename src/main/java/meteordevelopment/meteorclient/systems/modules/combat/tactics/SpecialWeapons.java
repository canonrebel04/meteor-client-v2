/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Situational weapons (C62-C69), pure decisions. Fire/lava/TNT tactics and mace launches are opt-in.
 */
public final class SpecialWeapons {
    private SpecialWeapons() {
    }

    /** C62: load the crossbow out of combat range so the first shot is instant. */
    public static boolean preloadCrossbow(boolean haveCrossbow, boolean charged, double nearestEnemy, boolean inMelee) {
        return haveCrossbow && !charged && !inMelee && nearestEnemy > 6;
    }

    /** C62: piercing pays off when two+ enemies are lined up (within 0.8 of the line to the target). */
    public static boolean linedUp(double ex, double ez, double tx, double tz, double[][] others) {
        double dx = tx - ex, dz = tz - ez, len = Math.max(1e-6, Math.hypot(dx, dz));
        int n = 0;
        for (double[] o : others) {
            double ox = o[0] - ex, oz = o[1] - ez;
            double along = (ox * dx + oz * dz) / len;
            double off = Math.abs(ox * dz - oz * dx) / len;
            if (along > 0 && along < len + 6 && off < 0.8) n++;
        }
        return n >= 1;
    }

    public enum TridentUse { NONE, THROW, RIPTIDE_CLOSE, RIPTIDE_ESCAPE, CHANNEL }

    /** C63: riptide only works wet; channeling needs thunder and sky over the target. */
    public static TridentUse trident(boolean haveTrident, boolean riptide, boolean loyalty, boolean channeling, boolean wet,
                                     boolean thunder, boolean targetUnderSky, double distance, boolean fleeing) {
        if (!haveTrident) return TridentUse.NONE;
        if (riptide) {
            if (!wet) return TridentUse.NONE;
            if (fleeing) return TridentUse.RIPTIDE_ESCAPE;
            return distance > 5 && distance < 20 ? TridentUse.RIPTIDE_CLOSE : TridentUse.NONE;
        }
        if (channeling && thunder && targetUnderSky && distance < 30) return TridentUse.CHANNEL;
        if (loyalty && distance >= 5 && distance <= 25) return TridentUse.THROW;
        return TridentUse.NONE; // without loyalty a throw loses the weapon
    }

    public enum MaceUse { NONE, SMASH, LAUNCH }

    /** C64 mace: smash when falling onto the target; launch (wind charge at feet) only opt-in and fall-safe. */
    public static MaceUse mace(boolean haveMace, double fallDistance, boolean targetBelowInReach, boolean launchAllowed,
                               boolean haveWindCharge, boolean onGround, double distance, double health, boolean safeLanding) {
        if (!haveMace) return MaceUse.NONE;
        if (fallDistance >= 1.5 && targetBelowInReach) return MaceUse.SMASH;
        // a wind charge launches ~7 blocks: landing takes ≈ (7-3) = 4 hp without a safe landing
        if (launchAllowed && haveWindCharge && onGround && distance <= 3.0 && (safeLanding || health > 10)) return MaceUse.LAUNCH;
        return MaceUse.NONE;
    }

    /** C64: breach vs density: breach for heavy armour, density for big falls. */
    public static String maceEnchantPreference(double targetArmor, double fallDistance) {
        if (fallDistance >= 6) return "density";
        return targetArmor >= 15 ? "breach" : "density";
    }

    /** Smash damage bonus (vanilla 26.x): 4/block for the first 3, 2/block up to 8, 1/block after. */
    public static double smashBonus(double fall) {
        if (fall < 1.5) return 0;
        double b = Math.min(fall, 3) * 4;
        if (fall > 3) b += (Math.min(fall, 8) - 3) * 2;
        if (fall > 8) b += fall - 8;
        return b;
    }

    /** C65 spear: a charge needs a sprint run-up and a straight, clear line of 3-6 blocks. */
    public static boolean spearCharge(boolean haveSpear, boolean sprinting, boolean mounted, double distance, boolean lineClear) {
        return haveSpear && (sprinting || mounted) && lineClear && distance >= 2.5 && distance <= 6.5;
    }

    public enum Utility { NONE, SNOWBALL_POKE, ROD_PULL, ROD_KNOCK, SPLASH_WEAKNESS, SPLASH_SLOWNESS, SPLASH_HARMING }

    /**
     * C66/C67/C69 utility (opt-in): snowball/egg poke pushes an enemy standing at an edge; rod pull drags a
     * kiting enemy in; splash potions (weakness / slowness / harming) out of melee on an unaffected target.
     */
    public static Utility utility(boolean allowed, double distance, double targetEdgeDistance, boolean haveSnowball,
                                  boolean haveRod, boolean wantTargetCloser, boolean haveWeakness, boolean haveSlowness,
                                  boolean haveHarming, boolean targetWeak, boolean targetSlow, boolean targetUndead) {
        if (!allowed) return Utility.NONE;
        boolean outOfMelee = distance > 4;
        if (haveSnowball && targetEdgeDistance <= 1.2 && distance >= 3 && distance <= 20) return Utility.SNOWBALL_POKE;
        if (haveRod && targetEdgeDistance <= 1.2 && distance <= 6) return Utility.ROD_KNOCK;
        if (outOfMelee && distance <= 8) {
            if (haveWeakness && !targetWeak && !targetUndead) return Utility.SPLASH_WEAKNESS;
            if (haveSlowness && !targetSlow) return Utility.SPLASH_SLOWNESS;
            if (haveHarming && !targetUndead) return Utility.SPLASH_HARMING;
        }
        if (haveRod && wantTargetCloser && distance >= 5 && distance <= 12) return Utility.ROD_PULL;
        return Utility.NONE;
    }

    /**
     * Ticks after casting before the second use (retract) so the hook can reach the target.
     * About 2.5 ticks per block, clamped to a close-range PvP window.
     */
    public static int rodRetractTicks(double distance) {
        return (int) Math.max(6, Math.min(18, Math.round(distance * 2.5 + 4)));
    }

    public enum FireUse { NONE, FLINT_AND_STEEL, LAVA_BUCKET }

    /** C68 fire/lava (opt-in, never near us or our base): only on a target in a tunnel or on flammable ground. */
    public static FireUse fire(boolean allowed, boolean haveFlint, boolean haveLava, double distance, boolean targetFireResistant,
                               boolean targetInTunnel, boolean groundFlammableNearUs, boolean weAreUpwind) {
        if (!allowed || targetFireResistant || groundFlammableNearUs) return FireUse.NONE;
        if (distance < 2.5 || distance > 4.5) return FireUse.NONE;
        if (haveLava && targetInTunnel && weAreUpwind) return FireUse.LAVA_BUCKET;
        if (haveFlint) return FireUse.FLINT_AND_STEEL;
        return FireUse.NONE;
    }
}
