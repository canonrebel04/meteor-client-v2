/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatModeScorerTest {
    /** Healthy, even fight, target visible, nothing special. */
    private static CombatModeScorer.Inputs base() {
        return new CombatModeScorer.Inputs(
            0.1, 20f, 2, true, 0.6, 1, true,
            3.0, 3.0, 1.0f, true, false, false,
            false, false, true
        );
    }

    private static CombatModeScorer.Inputs with(CombatModeScorer.Inputs b, java.util.function.UnaryOperator<Object[]> f) {
        Object[] a = {b.threat(), b.health(), b.totems(), b.hasTarget(), b.viability(), b.nearbyEnemies(), b.targetVisible(),
            b.targetReach(), b.myReach(), b.targetHpRatio(), b.myWeaponMelee(), b.targetHoldingRanged(), b.rangedKiteOpportunity(),
            b.crystalSetting(), b.hasCrystalOrAnchor(), b.playerInOpen()};
        a = f.apply(a);
        return new CombatModeScorer.Inputs((double) a[0], (float) a[1], (int) a[2], (boolean) a[3], (double) a[4], (int) a[5],
            (boolean) a[6], (double) a[7], (double) a[8], (float) a[9], (boolean) a[10], (boolean) a[11], (boolean) a[12],
            (boolean) a[13], (boolean) a[14], (boolean) a[15]);
    }

    @Test
    void defaultIsAggressive() {
        assertEquals(CombatMode.AGGRESSIVE, CombatModeScorer.best(base()));
    }

    @Test
    void criticalHealthNoTotemsForcesRetreatHeal() {
        var in = with(base(), a -> { a[0] = 0.9; a[1] = 3f; a[2] = 0; return a; });
        assertEquals(CombatMode.RETREAT_HEAL, CombatModeScorer.best(in));
    }

    @Test
    void crowdedInOpenBurrows() {
        var in = with(base(), a -> { a[5] = 5; return a; });
        assertEquals(CombatMode.BURROW, CombatModeScorer.best(in));
    }

    @Test
    void coverReducesBurrowPull() {
        var open = CombatModeScorer.scores(with(base(), a -> { a[5] = 5; return a; }));
        var covered = CombatModeScorer.scores(with(base(), a -> { a[5] = 5; a[15] = false; return a; }));
        assertTrue(covered[CombatMode.BURROW.ordinal()] < open[CombatMode.BURROW.ordinal()]);
    }

    @Test
    void crystalBreachNeedsCrystalsInInventory() {
        var noCrystals = with(base(), a -> { a[5] = 0; a[6] = false; a[13] = true; a[14] = false; return a; });
        var crystals = with(base(), a -> { a[5] = 0; a[6] = false; a[13] = true; a[14] = true; return a; });
        assertEquals(0.0, CombatModeScorer.scores(noCrystals)[CombatMode.BURROW.ordinal()], 1e-9);
        assertEquals(CombatMode.BURROW, CombatModeScorer.best(crystals));
    }

    @Test
    void hiddenTargetAloneIsStealth() {
        var in = with(base(), a -> { a[5] = 0; a[6] = false; return a; });
        assertEquals(CombatMode.STEALTH, CombatModeScorer.best(in));
    }

    @Test
    void rangedTargetSnipes() {
        var in = with(base(), a -> { a[11] = true; return a; });
        assertEquals(CombatMode.SNIPE, CombatModeScorer.best(in));
    }

    @Test
    void airborneTargetWithBowRangedKites() {
        var in = with(base(), a -> { a[12] = true; return a; });
        assertEquals(CombatMode.RANGED_KITE, CombatModeScorer.best(in));
    }

    @Test
    void reachDeficitWithMeleeKites() {
        var in = with(base(), a -> { a[7] = 6.0; return a; });
        assertEquals(CombatMode.KITE, CombatModeScorer.best(in));
    }

    @Test
    void lowHpTargetWithGoodViabilityRushes() {
        var in = with(base(), a -> { a[9] = 0.05f; a[4] = 0.95; return a; });
        assertEquals(CombatMode.RUSH, CombatModeScorer.best(in));
    }

    @Test
    void undergearedGoesDefensive() {
        var in = with(base(), a -> { a[4] = 0.0; return a; });
        assertEquals(CombatMode.DEFENSIVE, CombatModeScorer.best(in));
    }

    @Test
    void noTargetNeverPicksTargetModes() {
        var in = with(base(), a -> { a[3] = false; a[11] = true; a[12] = true; a[7] = 6.0; a[5] = 0; a[6] = false; return a; });
        CombatMode m = CombatModeScorer.best(in);
        assertTrue(m != CombatMode.SNIPE && m != CombatMode.RANGED_KITE && m != CombatMode.KITE && m != CombatMode.STEALTH);
    }

    @Test
    void hysteresisHoldsUntilTimerElapses() {
        assertEquals(CombatMode.AGGRESSIVE, CombatMode.holdMode(CombatMode.AGGRESSIVE, CombatMode.KITE, 5, 20));
        assertEquals(CombatMode.KITE, CombatMode.holdMode(CombatMode.AGGRESSIVE, CombatMode.KITE, 20, 20));
        assertEquals(CombatMode.KITE, CombatMode.holdMode(null, CombatMode.KITE, 0, 20));
    }

    @Test
    void retreatHealBypassesHysteresis() {
        assertEquals(CombatMode.RETREAT_HEAL, CombatMode.holdMode(CombatMode.RUSH, CombatMode.RETREAT_HEAL, 0, 20));
    }

    @Test
    void modePaddingOrdering() {
        assertTrue(CombatMode.modePadding(CombatMode.RANGED_KITE) > CombatMode.modePadding(CombatMode.KITE));
        assertTrue(CombatMode.modePadding(CombatMode.KITE) > CombatMode.modePadding(CombatMode.AGGRESSIVE));
        assertEquals(0.5, CombatMode.modePadding(null), 1e-9);
    }
}
