/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import meteordevelopment.meteorclient.systems.modules.combat.model.MatchupAssessment.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the P0 verdicts plus P1 corrections and hysteresis. */
class MatchupVerdictTest {
    static KitSnapshot unarmed() {
        return new KitSnapshot(20, 0, 0, 0, 0, 1, 4, false, false, false, 0, false, 0, 0, 0, true);
    }

    static KitSnapshot netherite() {
        return new KitSnapshot(20, 0, 20, 12, 16, 8, 1.6, true, true, false, 1, true, 0, 0, 0, true);
    }

    static final EscapeProfile OPEN = new EscapeProfile(false, false, false, false, false, 6, true);

    @Test
    void unarmedVsNetheriteIsHopelessAndFlees() {
        Result r = MatchupAssessment.evaluate(unarmed(), netherite(), OPEN, AggressionProfile.BALANCED);
        assertEquals(Verdict.HOPELESS, r.verdict());
        assertFalse(r.shouldEngage());
    }

    @Test
    void corneredHopelessMustFight() {
        EscapeProfile cornered = new EscapeProfile(false, false, false, false, true, 2, true);
        Result r = MatchupAssessment.evaluate(unarmed(), netherite(), cornered, AggressionProfile.BALANCED);
        assertTrue(r.mustFightBecauseCornered());
        assertTrue(r.shouldEngage());
    }

    @Test
    void evenFightsAreNotForcedEitherWay() {
        Result r = MatchupAssessment.evaluate(netherite(), netherite(), OPEN, AggressionProfile.BALANCED);
        assertNotEquals(Verdict.HOPELESS, r.verdict());
        assertTrue(r.shouldEngage());
    }

    @Test
    void correctionsShiftVerdict() {
        Result base = MatchupAssessment.evaluate(netherite(), netherite(), OPEN, AggressionProfile.BALANCED);
        var scary = new OpponentModel.Corrections(1.8, 1.0, 1.0, 1.0, List.of("crystals"));
        Result worse = MatchupAssessment.evaluate(netherite(), netherite(), OPEN, AggressionProfile.BALANCED, scary);
        assertTrue(worse.killRatio() < base.killRatio());
        assertTrue(worse.verdict().shouldFlee());
        assertTrue(worse.reason().contains("crystals"));
    }

    @Test
    void opponentModelCorrections() {
        OpponentModel m = new OpponentModel("Steve", true);
        m.intent = Intent.CRYSTALLING;
        var c = m.corrections(0, 0.8);
        assertTrue(c.theirDpsMult() > 1.3);
        assertEquals(0.8, c.ratioMult(), 1e-9);
    }

    @Test
    void gateHysteresis() {
        VerdictGate g = new VerdictGate(2, 5);
        assertEquals(Verdict.WIN, g.offer(Verdict.WIN));
        assertEquals(Verdict.WIN, g.offer(Verdict.LOSE));
        assertEquals(Verdict.LOSE, g.offer(Verdict.LOSE));
        for (int i = 0; i < 4; i++) assertEquals(Verdict.LOSE, g.offer(Verdict.WIN));
        assertEquals(Verdict.WIN, g.offer(Verdict.WIN));
        assertEquals(Verdict.HOPELESS, g.offer(Verdict.HOPELESS));
    }
}
