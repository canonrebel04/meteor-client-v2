/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P1 C41-C50 escape models. */
class EscapeModelsTest {
    @Test
    void fleePlannerPrefersAwayAndAvoidsDeadEndsAndHazards() {
        FleePlanner fp = new FleePlanner();
        List<FleePlanner.Pursuer> ps = List.of(new FleePlanner.Pursuer(-5, 0, 0.28));
        List<FleePlanner.Candidate> cs = new ArrayList<>();
        cs.add(new FleePlanner.Candidate(-12, 64, 0, true, false, false, 0, 200, 0));   // toward the pursuer
        cs.add(new FleePlanner.Candidate(15, 64, 0, true, false, false, 0, 6, 0));      // away but dead end
        cs.add(new FleePlanner.Candidate(14, 64, 4, true, false, true, 3, 200, 0));     // away, LOS broken, open
        cs.add(new FleePlanner.Candidate(20, 64, 0, true, true, true, 6, 500, 0));      // lava
        FleePlanner.Scored best = fp.best(0, 0, cs, ps, 0.28);
        assertNotNull(best);
        assertEquals(14, best.candidate().x(), 1e-9);
        assertFalse(FleePlanner.cornered(best));
    }

    @Test
    void corneredWhenNothingUsable() {
        FleePlanner fp = new FleePlanner();
        List<FleePlanner.Candidate> cs = List.of(new FleePlanner.Candidate(3, 64, 0, false, false, false, 0, 0, 0));
        assertTrue(FleePlanner.cornered(fp.best(0, 0, cs, List.of(), 0.28)));
    }

    @Test
    void escapeSpaceFloodFill() {
        // 3x3 pit vs open field
        EscapeSpace.Standable pit = (x, y, z) -> y == 0 && Math.abs(x) <= 1 && Math.abs(z) <= 1;
        EscapeSpace.Standable field = (x, y, z) -> y == 0;
        assertEquals(9, EscapeSpace.reachable(pit, 0, 0, 0, 100));
        assertEquals(100, EscapeSpace.reachable(field, 0, 0, 0, 100));
    }

    @Test
    void pursuitCompletion() {
        PursuitTracker t = new PursuitTracker();
        for (int i = 0; i < 20; i++) t.sample(i, 10 - i * 0.3, true);
        assertTrue(t.closingIn());
        t.reset();
        for (int i = 0; i < 30; i++) t.sample(i, 30 + i * 0.1, false);
        assertFalse(t.closingIn());
        assertTrue(t.complete(MatchupAssessment.Verdict.LOSE, 24));
        assertFalse(t.complete(MatchupAssessment.Verdict.LOSE, 64));
        assertTrue(t.complete(MatchupAssessment.Verdict.WIN, 64));
        assertEquals(14.0 + 0.5, PursuitTracker.safeDistance(10, 8, 0.5), 1e-9);
    }

    @Test
    void pearlTrajectoryLandsOnFlatGround() {
        PearlSolver.Solid ground = (x, y, z) -> y < 64;
        PearlSolver.Landing l = PearlSolver.simulate(0.5, 65.62, 0.5, 0, -30, ground, 200);
        assertTrue(l.hit());
        assertTrue(l.z() > 20 && l.z() < 60, "z=" + l.z());
        assertEquals(0.5, l.x(), 1e-6);
        PearlSolver.Solution s = PearlSolver.bestAlongYaw(0.5, 65.62, 0.5, 0, ground, 0, -5, land -> true);
        assertNotNull(s);
        PearlSolver.Landing wall = PearlSolver.simulate(0.5, 65.62, 0.5, 0, 0, (x, y, z) -> z >= 3, 50);
        assertTrue(wall.hit() && wall.z() < 4.5);
    }

    @Test
    void fallClutch() {
        assertEquals(17, FallClutch.fallDamage(20, 0), 1e-9);
        assertTrue(FallClutch.fallDamage(20, 4) < 17);
        assertTrue(FallClutch.needed(10, 15, 0, 20, 2));
        assertFalse(FallClutch.needed(1, 2, 0, 20, 2));
        assertTrue(FallClutch.placeNow(2.0, 1.0));
        assertFalse(FallClutch.placeNow(10, 1.0));
        assertEquals(8, FallClutch.pickupDelayTicks());
    }

    private static EscapeSelector.Situation sit(boolean cornered, boolean hole, double hp) {
        return new EscapeSelector.Situation(true, false, true, true, true, true, hole, false, false, false,
            cornered, 3, true, hp, true, true);
    }

    @Test
    void selectorRespectsOptInFlags() {
        var safe = EscapeSelector.choose(MatchupAssessment.Verdict.HOPELESS, sit(false, false, 6), EscapeSelector.Flags.SAFE);
        assertEquals(EscapeSelector.Method.RUN, safe.method());
        var pearl = EscapeSelector.choose(MatchupAssessment.Verdict.HOPELESS, sit(false, false, 6),
            new EscapeSelector.Flags(true, false, false, false, false));
        assertEquals(EscapeSelector.Method.PEARL, pearl.method());
        assertEquals(EscapeSelector.Method.HOLE, EscapeSelector.choose(MatchupAssessment.Verdict.LOSE, sit(true, true, 15), EscapeSelector.Flags.SAFE).method());
        assertEquals(EscapeSelector.Method.FIGHT_CORNERED, EscapeSelector.choose(MatchupAssessment.Verdict.LOSE, sit(true, false, 15), EscapeSelector.Flags.SAFE).method());
    }

    @Test
    void ladderClimbsSlowlyAndOnlyLogsWhenAllowed() {
        DisengageLadder l = new DisengageLadder();
        var v = MatchupAssessment.Verdict.HOPELESS;
        assertEquals(DisengageLadder.Rung.DISENGAGE, l.update(0, v, false, false, 4, false));
        assertEquals(DisengageLadder.Rung.DISENGAGE, l.update(10, v, false, false, 4, false));
        assertEquals(DisengageLadder.Rung.SHELTER, l.update(40, v, false, false, 4, false));
        assertEquals(DisengageLadder.Rung.HEAL, l.update(41, v, false, true, 4, false));
        assertEquals(DisengageLadder.Rung.HEAL, l.update(200, v, false, true, 4, false));
        assertEquals(DisengageLadder.Rung.LOG, l.update(201, v, false, true, 4, true));
        assertEquals(DisengageLadder.Rung.NONE, l.update(202, MatchupAssessment.Verdict.WIN, false, true, 4, true));
    }

    @Test
    void jitterAlternatesAndIsBounded() {
        EvasiveJitter j = new EvasiveJitter(42);
        int flips = 0;
        double prev = 0;
        for (int i = 0; i < 100; i++) {
            double d = j.next(25);
            assertTrue(Math.abs(d) <= 25);
            if (i > 0 && Math.signum(d) != Math.signum(prev)) flips++;
            prev = d;
            int r = j.replanTicks();
            assertTrue(r >= 20 && r <= 50);
        }
        assertTrue(flips > 55);
    }
}
