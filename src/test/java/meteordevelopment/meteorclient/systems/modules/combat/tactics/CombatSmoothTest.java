package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S52-S65 combat smoothness helpers. */
class CombatSmoothTest {

    @Test
    void strafeCurveIsSmoothDeterministicAndAvoidsInstantFlips() {
        double prev = CombatSmooth.strafeCurve(42, 0);
        int instantFlips = 0;
        for (int t = 1; t < 400; t++) {
            double c = CombatSmooth.strafeCurve(42, t);
            assertEquals(c, CombatSmooth.strafeCurve(42, t));
            assertTrue(c >= -1.0 && c <= 1.0);
            int a = CombatSmooth.strafeKey(prev, 0.25);
            int b = CombatSmooth.strafeKey(c, 0.25);
            if (a != 0 && b != 0 && a != b) instantFlips++;
            prev = c;
        }
        assertEquals(0, instantFlips, "left/right must pass through a deadzone");
        assertEquals(1, CombatSmooth.strafeKey(0.4, 0.25));
        assertEquals(-1, CombatSmooth.strafeKey(-0.4, 0.25));
        assertEquals(0, CombatSmooth.strafeKey(0.1, 0.25));
    }

    @Test
    void distanceBandEasesInsteadOfBinaryToggles() {
        assertEquals(0.0, CombatSmooth.distanceAlong(3.0, 2.5, 3.5), 1e-9);
        assertTrue(CombatSmooth.distanceAlong(2.0, 2.5, 3.5) < 0);
        assertTrue(CombatSmooth.distanceAlong(1.0, 2.5, 3.5) <= CombatSmooth.distanceAlong(2.0, 2.5, 3.5));
        double near = CombatSmooth.distanceAlong(3.7, 2.5, 3.5);
        double far = CombatSmooth.distanceAlong(5.5, 2.5, 3.5);
        assertTrue(near > 0 && near < far);
        assertEquals(1.0, far, 1e-9);
        assertFalse(CombatSmooth.shouldSprint(0.4));
        assertTrue(CombatSmooth.shouldSprint(0.8));
    }

    @Test
    void retreatArcOffsetsPerpendicularAndPrefersOffAxisBearings() {
        double[] mid = CombatSmooth.retreatArc(0, 0, 10, 0, 4, 1);
        assertEquals(5.0, mid[0], 1e-9);
        assertEquals(4.0, mid[1], 1e-9);
        double[] left = CombatSmooth.retreatArc(0, 0, 10, 0, 4, -1);
        assertEquals(-4.0, left[1], 1e-9);
        assertEquals(2.0, CombatSmooth.retreatArcBonus(0, 45), 1e-9);
        assertTrue(CombatSmooth.retreatArcBonus(0, 0) < 0);
    }

    @Test
    void shieldReactionAndAimSettleAreBounded() {
        for (long n = 0; n < 40; n++) {
            int r = CombatSmooth.shieldReactionTicks(7, n);
            int l = CombatSmooth.shieldLowerTicks(7, n);
            assertTrue(r >= 4 && r <= 10, "raise " + r);
            assertTrue(l >= 3 && l <= 8, "lower " + l);
        }
        assertEquals(1, CombatSmooth.aimSettleTicks(3));
        assertEquals(3, CombatSmooth.aimSettleTicks(20));
        assertEquals(10, CombatSmooth.aimSettleTicks(90));
        assertTrue(CombatSmooth.aimSettled(4, 5, 8, 10));
        assertFalse(CombatSmooth.aimSettled(20, 0, 8, 10));
    }

    @Test
    void critPitchDipsAtTheJumpPeakAndRhythmAddsJitterOnlyWhenLegit() {
        assertEquals(10f, CombatSmooth.critPitch(10f, 0.0, false));
        float peak = CombatSmooth.critPitch(10f, 0.0, true);
        float rise = CombatSmooth.critPitch(10f, 0.42, true);
        assertTrue(peak > rise);
        assertEquals(10f, rise, 0.05f);
        assertEquals(4, CombatSmooth.crystalRhythmGap(4, 3, false));
        int g = CombatSmooth.crystalRhythmGap(4, 3, true);
        assertTrue(g >= 5 && g <= 7);
        assertEquals(20, CombatSmooth.respawnFadeTicks());
    }
}
