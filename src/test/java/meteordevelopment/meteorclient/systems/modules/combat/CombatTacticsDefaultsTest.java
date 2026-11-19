/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.modules.combat.tactics.MeleeTiming;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatTacticsDefaultsTest {
    @Test
    void riskierBehavioursAreOptIn() {
        CombatTactics.Config d = CombatTactics.Config.DEFAULTS;
        assertEquals(MeleeTiming.Tap.NONE, d.tap());
        assertFalse(d.comboPositioning());
        assertFalse(d.utility());
        assertFalse(d.fire());
        assertFalse(d.maceLaunch());
        assertFalse(d.breakCrystals());
        assertFalse(d.explosives());
        assertFalse(d.corneredBlocks());
        assertFalse(d.breakIn());
        assertFalse(d.fleeWalls());
        assertFalse(d.territory());
        // balanced, legit defaults stay on
        assertTrue(d.crits() && d.reach() && d.hitSelect() && d.weaponArbiter() && d.priority());
        assertTrue(d.ballistic() && d.crystalDefense() && d.tntRetreat() && d.closeDoors() && d.teamCheck() && d.encounterRules());
    }

    @Test
    void pacerFallsBackWithoutBaritone() {
        CombatPacer p = CombatPacer.create();
        assertTrue(p.useGap(0) >= 1);
        // Baritone is compileOnly (absent from the test classpath): fixed gaps, direct swaps
        assertInstanceOf(CombatPacer.Fixed.class, p);
        assertEquals(5, p.nextSlot(1, 5));
    }
}
