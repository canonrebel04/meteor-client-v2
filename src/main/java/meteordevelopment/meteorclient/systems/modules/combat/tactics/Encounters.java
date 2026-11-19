/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.Locale;

/**
 * Boss / elite / raid / dimension / environment rules (C85-C88), pure.
 */
public final class Encounters {
    public enum Rule { NORMAL, AVOID, FLEE, RANGED_ONLY, BREAK_LOS, PRIORITY_CRYSTALS, KITE, LEAVE_WATER }

    private Encounters() {
    }

    /**
     * C85/C86 per-mob rule. Warden: never fight, leave (sonic boom ignores armour; break line of sight when it
     * charges); wither: ranged only unless well geared; dragon: crystals first; elder guardian: leave the water
     * (mining fatigue); piglin brute: kite (always hostile, axe); ravager/evoker in a raid: kite.
     */
    public static Rule rule(String entityId, boolean wellGeared, boolean sonicCharging, boolean dragonCrystalsLeft, boolean inRaid) {
        String p = entityId == null ? "" : entityId.substring(entityId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return switch (p) {
            case "warden" -> sonicCharging ? Rule.BREAK_LOS : Rule.FLEE;
            case "wither" -> wellGeared ? Rule.NORMAL : Rule.RANGED_ONLY;
            case "ender_dragon" -> dragonCrystalsLeft ? Rule.PRIORITY_CRYSTALS : Rule.RANGED_ONLY;
            case "elder_guardian" -> Rule.LEAVE_WATER;
            case "piglin_brute" -> wellGeared ? Rule.NORMAL : Rule.KITE;
            case "ravager", "evoker" -> inRaid && !wellGeared ? Rule.KITE : Rule.NORMAL;
            default -> Rule.NORMAL;
        };
    }

    /** C86: a raid wave is too much to stand in: retreat when its threat exceeds what we can handle. */
    public static boolean raidRetreat(int raiders, int ravagers, int evokers, boolean wellGeared, double health) {
        double threat = raiders + 3 * ravagers + 4 * evokers;
        double cap = wellGeared ? 14 : 6;
        if (health < 10) cap /= 2;
        return threat > cap;
    }

    /** C86: one gold armour piece keeps piglins neutral in the Nether. */
    public static boolean wantGoldPiece(String dimension, boolean wearingGold, boolean piglinsNear) {
        return "the_nether".equals(dimension) && !wearingGold && piglinsNear;
    }

    /** C87: edge margin (blocks we keep from drops) per dimension. */
    public static double edgeMargin(String dimension) {
        return switch (dimension == null ? "" : dimension) {
            case "the_end" -> 3.0;     // void
            case "the_nether" -> 2.0;  // lava seas below
            default -> 1.2;
        };
    }

    /** C87: ghast fireballs should be swatted back (deflection already handles it) or dodged sideways. */
    public static boolean dodgeFireball(double distance, double closingSpeed, boolean canDeflect) {
        return !canDeflect && distance < 12 && closingSpeed > 0.5;
    }

    public enum Env { NONE, SURFACE, LEAVE_FIRE, LEAVE_HAZARD, DOUSE }

    /** C88 environment: drowning (surface below 5 air bubbles), standing in fire/magma/cactus/berries/powder snow. */
    public static Env environment(int air, int maxAir, boolean onFire, boolean inFire, boolean haveWaterBucket, Hazard standingOn,
                                  int freezeTicks) {
        if (maxAir > 0 && air < maxAir / 6) return Env.SURFACE;
        if (inFire) return Env.LEAVE_FIRE;
        if (standingOn != null && (standingOn == Hazard.MAGMA || standingOn == Hazard.CACTUS || standingOn == Hazard.SWEET_BERRY
                || standingOn == Hazard.WITHER_ROSE)) return Env.LEAVE_HAZARD;
        if (freezeTicks > 100) return Env.LEAVE_HAZARD;
        if (onFire && haveWaterBucket) return Env.DOUSE;
        return Env.NONE;
    }
}
