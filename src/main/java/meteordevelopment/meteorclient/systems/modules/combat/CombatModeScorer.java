/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

/**
 * Pure (Minecraft-free) scoring for the CombatBrain mode loop.
 * {@link CombatMode#evaluateCombatMode} gathers world state into {@link Inputs}
 * and delegates here, so the trigger table can be unit-tested without a client.
 */
public final class CombatModeScorer {
    private CombatModeScorer() {}

    /**
     * Snapshot of everything the mode trigger table depends on.
     *
     * @param threat              current threat level [0..1]
     * @param health              own health + absorption
     * @param totems              totems in inventory
     * @param hasTarget           whether a target is selected
     * @param viability           target viability [0..1] (0.5 when unknown)
     * @param nearbyEnemies       hostile entities within the burrow radius
     * @param targetVisible       line of sight to target
     * @param targetReach         target attack reach
     * @param myReach             own attack reach
     * @param targetHpRatio       target (health + absorption) / max health
     * @param myWeaponMelee       own main hand is melee (not bow/crossbow/trident)
     * @param targetHoldingRanged target holds bow/crossbow/trident
     * @param rangedKiteOpportunity we own a ranged weapon, target visible and airborne/elevated
     * @param crystalSetting      CombatBrain "crystal" setting enabled
     * @param hasCrystalOrAnchor  we carry end crystals or respawn anchors
     * @param playerInOpen        no cover around the player (AwarenessCube)
     */
    public record Inputs(
        double threat,
        float health,
        int totems,
        boolean hasTarget,
        double viability,
        int nearbyEnemies,
        boolean targetVisible,
        double targetReach,
        double myReach,
        float targetHpRatio,
        boolean myWeaponMelee,
        boolean targetHoldingRanged,
        boolean rangedKiteOpportunity,
        boolean crystalSetting,
        boolean hasCrystalOrAnchor,
        boolean playerInOpen
    ) {}

    public static double[] scores(Inputs in) {
        double[] s = new double[CombatMode.values().length];

        // RETREAT_HEAL: logistic on vulnerability x threat; hard override at critical HP with no totems.
        double vulnerability = 1.0 - Math.min(1.0, in.health() / 20.0);
        s[CombatMode.RETREAT_HEAL.ordinal()] = 1.0 / (1.0 + Math.exp(-5.0 * (vulnerability * in.threat() - 0.35)));
        if (in.health() <= 4.0f && in.totems() == 0 && in.threat() > 0.8) s[CombatMode.RETREAT_HEAL.ordinal()] = 1.0;

        // BURROW: crowded and exposed (spec trigger #3). Cover halves the pull to dig in.
        double burrow = Math.tanh(in.nearbyEnemies() / 3.0) * 0.75;
        if (!in.playerInOpen()) burrow *= 0.5;
        // Crystal/anchor breach (spec trigger #4): target behind cover and we can actually breach.
        if (in.hasTarget() && !in.targetVisible() && in.crystalSetting() && in.hasCrystalOrAnchor()) {
            burrow = Math.max(burrow, 0.65);
        }
        s[CombatMode.BURROW.ordinal()] = burrow;

        s[CombatMode.RUSH.ordinal()] = in.hasTarget() ? Math.max(0.0, (1.0 - in.targetHpRatio()) * in.viability()) : 0.0;

        if (in.hasTarget() && in.rangedKiteOpportunity()) s[CombatMode.RANGED_KITE.ordinal()] = 0.85;

        double reachDeficit = in.targetReach() - in.myReach();
        s[CombatMode.KITE.ordinal()] = (in.hasTarget() && reachDeficit > 0 && in.myWeaponMelee()) ? Math.tanh(reachDeficit) * 0.7 : 0.0;

        s[CombatMode.SNIPE.ordinal()] = (in.hasTarget() && in.targetHoldingRanged()) ? 0.7 : 0.0;

        s[CombatMode.DEFENSIVE.ordinal()] = Math.max(0.0, 0.6 - in.viability());

        s[CombatMode.STEALTH.ordinal()] = (in.hasTarget() && !in.targetVisible() && in.nearbyEnemies() == 0) ? 0.6 : 0.0;

        s[CombatMode.AGGRESSIVE.ordinal()] = 0.3 + in.viability() * 0.3;
        return s;
    }

    public static CombatMode best(Inputs in) {
        double[] s = scores(in);
        int bestIdx = 0;
        for (int i = 1; i < s.length; i++) {
            if (s[i] > s[bestIdx]) bestIdx = i;
        }
        return CombatMode.values()[bestIdx];
    }
}
