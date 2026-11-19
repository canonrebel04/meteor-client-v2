/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.modules.combat.tactics.AuraAim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S51/S54/S55/S60/S62: KillAura camera-aim pacing. */
class AuraAimTest {
    private static final int NONE = Integer.MIN_VALUE;

    @Test
    void switchHesitationWithinRange() {
        for (long seed = 0; seed < 50; seed++) {
            AuraAim a = new AuraAim(seed);
            a.tick(1, false);
            assertTrue(a.mayAim(), "first acquisition is immediate");
            a.tick(2, false);
            int hold = a.switchHold();
            assertTrue(hold >= 2 && hold <= 5, "hold " + hold);
            int ticks = 0;
            while (!a.mayAim()) {
                a.tick(2, false);
                ticks++;
            }
            assertEquals(hold, ticks);
            // losing the target then reacquiring is not a "switch"
            a.tick(NONE, false);
            a.tick(3, false);
            assertTrue(a.mayAim());
        }
    }

    @Test
    void knockbackHold() {
        AuraAim a = new AuraAim(7);
        a.tick(1, false);
        a.tick(1, true);
        assertFalse(a.mayAim());
        assertTrue(a.hurtHold() >= 2 && a.hurtHold() <= 4);
    }

    @Test
    void cadenceJitterBounded() {
        int[] seen = new int[3];
        for (long seed = 0; seed < 200; seed++) {
            AuraAim a = new AuraAim(seed);
            a.tick(1, false);
            assertFalse(a.attackReady(false, true));
            int wait = 0;
            while (!a.attackReady(true, true)) wait++;
            assertTrue(wait <= 2);
            seen[wait]++;
        }
        for (int c : seen) assertTrue(c > 0, "all jitter values occur");
        AuraAim a = new AuraAim(1);
        a.cadenceJitter = 0;
        a.tick(1, false);
        assertFalse(a.attackReady(true, false), "never swings off target");
        assertTrue(a.attackReady(true, true));
    }

    @Test
    void toleranceAndError() {
        AuraAim a = new AuraAim(0);
        assertEquals(180, a.tolerance(0.3, 0), 1e-9);
        assertEquals(Math.toDegrees(Math.atan2(0.3, 3)), a.tolerance(0.3, 3), 1e-9);
        assertEquals(1.5, a.tolerance(0.3, 100), 1e-9);
        assertEquals(20, AuraAim.error(170, 0, -170, 0), 1e-9);
        assertEquals(5, AuraAim.error(0, 0, 3, 4), 1e-9);
    }
}
