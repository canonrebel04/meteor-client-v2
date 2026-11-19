/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** C95: verdict thresholds checked against a headless fight simulation. */
class CalibrationHarnessTest {
    static MatchupAssessment.KitSnapshot kit(double armor, double tough, int epf, double dmg, double speed, int totems, boolean gaps) {
        return new MatchupAssessment.KitSnapshot(20, 0, armor, tough, epf, dmg, speed, true, false, false, totems, gaps, 0, 0, 0, true);
    }

    static final MatchupAssessment.KitSnapshot[] KITS = {
        kit(0, 0, 0, 4, 1.6, 0, false),      // stone sword, no armor
        kit(15, 0, 0, 6, 1.6, 0, false),     // iron set, iron sword
        kit(20, 8, 8, 7, 1.6, 0, true),      // diamond prot, gaps
        kit(20, 12, 16, 8, 1.6, 1, true),    // netherite prot IV, totem
        kit(20, 12, 16, 10, 0.9, 0, true),   // netherite with axe
    };

    @Test
    void deterministic() {
        FightSim.Outcome a = FightSim.run(KITS[1], KITS[2], FightSim.Skill.AVERAGE, FightSim.Skill.AVERAGE, 200, 42);
        FightSim.Outcome b = FightSim.run(KITS[1], KITS[2], FightSim.Skill.AVERAGE, FightSim.Skill.AVERAGE, 200, 42);
        assertEquals(a, b);
    }

    @Test
    void mirrorIsEven() {
        for (MatchupAssessment.KitSnapshot k : KITS) {
            double wr = FightSim.run(k, k, FightSim.Skill.AVERAGE, FightSim.Skill.AVERAGE, 600, 7).winRate();
            assertEquals(0.5, wr, 0.08, "mirror " + k);
        }
    }

    @Test
    void verdictsAgreeWithSimulation() {
        int checked = 0, agree = 0;
        StringBuilder report = new StringBuilder();
        for (MatchupAssessment.KitSnapshot me : KITS) {
            for (MatchupAssessment.KitSnapshot them : KITS) {
                MatchupAssessment.Result r = MatchupAssessment.evaluate(me, them, null, MatchupAssessment.AggressionProfile.BALANCED);
                double wr = FightSim.run(me, them, FightSim.Skill.AVERAGE, FightSim.Skill.AVERAGE, 400, 11).winRate();
                report.append(String.format("%s ratio=%.2f sim=%.2f%n", r.verdict(), r.killRatio(), wr));
                checked++;
                boolean ok = switch (r.verdict()) {
                    case WIN -> wr >= 0.55;
                    case EVEN -> wr >= 0.2 && wr <= 0.8;
                    case LOSE -> wr <= 0.5;
                    case HOPELESS -> wr <= 0.25;
                };
                if (ok) agree++;
                // a WIN verdict must never be a likely loss; a HOPELESS one never a likely win
                if (r.verdict() == MatchupAssessment.Verdict.WIN) assertTrue(wr >= 0.45, report.toString());
                if (r.verdict() == MatchupAssessment.Verdict.HOPELESS) assertTrue(wr <= 0.4, report.toString());
            }
        }
        if (Boolean.getBoolean("calib.print")) System.out.println(report);
        assertTrue(agree >= checked * 0.85, agree + "/" + checked + "\n" + report);
    }

    @Test
    void balancedWinThresholdIsCalibrated() {
        // the kill ratio where the simulated win rate first reaches 65% should be near BALANCED's WIN ratio
        double sum = 0;
        int n = 0;
        for (MatchupAssessment.KitSnapshot them : new MatchupAssessment.KitSnapshot[]{KITS[1], KITS[2], KITS[3]}) {
            double s = FightSim.suggestWinRatio(KITS[2], them, 0.65, 300, 3);
            if (Boolean.getBoolean("calib.print")) System.out.println("suggested win ratio " + s);
            assertFalse(Double.isNaN(s));
            sum += s;
            n++;
        }
        double suggested = sum / n;
        assertEquals(MatchupAssessment.AggressionProfile.BALANCED.winRatio, suggested, 0.35, "suggested " + suggested);
    }
}
