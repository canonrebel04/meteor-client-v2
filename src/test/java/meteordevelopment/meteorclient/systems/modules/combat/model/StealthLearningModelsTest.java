/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P3: C91-C94, C96, C100 pure models. */
class StealthLearningModelsTest {
    @Test
    void actionBudgetCpsReachTurns() {
        ActionBudget b = new ActionBudget();
        b.maxCps = 3;
        long t = 10_000;
        for (int i = 0; i < 3; i++) {
            assertTrue(b.mayClick(t + i * 100));
            b.recordClick(t + i * 100);
        }
        assertFalse(b.mayClick(t + 300), "4th click inside one second");
        assertTrue(b.mayClick(t + 1001), "window slid");
        b.maxCps = 0;
        assertTrue(b.mayClick(t + 300), "0 = unlimited");
        b.maxReach = 3.0;
        assertTrue(b.inReach(2.99));
        assertFalse(b.inReach(3.2));
        b.maxReach = 0;
        assertTrue(b.inReach(10));
        assertTrue(b.impossibleTurn(170, 0));
        assertFalse(b.impossibleTurn(350, 5), "wraps: -10 deg");
    }

    @Test
    void stealthBehindVsSeen() {
        // target at origin looking +Z (yaw 0); we're behind it at z=-5
        StealthPlanner.Advice behind = StealthPlanner.advise(0, -5, 0, 0, 0, 3, true);
        assertFalse(behind.seen());
        assertTrue(behind.sneak());
        assertFalse(behind.sprint());
        assertFalse(behind.holdAttack(), "already behind: swing allowed");
        assertEquals(180, StealthPlanner.viewAngle(0, 0, 0, 0, -5), 1e-6);
        // in front of it at z=+5: seen -> fight normally
        StealthPlanner.Advice front = StealthPlanner.advise(0, 5, 0, 0, 0, 3, true);
        assertTrue(front.seen());
        assertFalse(front.sneak());
        assertFalse(front.holdAttack());
        // in front but it has no line of sight: unseen, flank to behind it, hold swing
        StealthPlanner.Advice side = StealthPlanner.advise(5, 1, 0, 0, 0, 3, false);
        assertFalse(side.seen());
        assertTrue(side.flank());
        assertTrue(side.holdAttack());
        assertEquals(0, side.flankX(), 1e-6);
        assertEquals(-StealthPlanner.FLANK_DISTANCE, side.flankZ(), 1e-6);
        // far away and unseen: sprint, don't sneak
        StealthPlanner.Advice far = StealthPlanner.advise(0, -30, 0, 0, 0, 3, true);
        assertTrue(far.sprint());
        assertFalse(far.sneak());
    }

    @Test
    void pathWatchdogFailsOnCalcFailuresAndStalls() {
        PathWatchdog w = new PathWatchdog();
        w.reset(0);
        assertEquals(PathWatchdog.Status.OK, w.tick(true, 20, 1));
        assertEquals(PathWatchdog.Status.OK, w.tick(true, 20, 2));
        assertEquals(PathWatchdog.Status.FAILED, w.tick(true, 20, 3));
        assertTrue(w.failed(10));
        assertFalse(w.failed(3 + PathWatchdog.COOLDOWN_TICKS));
        // progress keeps it alive; no progress for STALL_TICKS fails it
        PathWatchdog s = new PathWatchdog();
        s.reset(0);
        for (int t = 1; t <= 200; t++) assertEquals(PathWatchdog.Status.OK, s.tick(false, 200 - t, t));
        PathWatchdog stall = new PathWatchdog();
        stall.reset(0);
        PathWatchdog.Status last = PathWatchdog.Status.OK;
        int t = 1;
        for (; t <= PathWatchdog.STALL_TICKS + 5 && last == PathWatchdog.Status.OK; t++) last = stall.tick(false, 15, t);
        assertEquals(PathWatchdog.Status.FAILED, last);
        assertTrue(t - 1 >= PathWatchdog.STALL_TICKS);
        // arrived is never a stall
        PathWatchdog at = new PathWatchdog();
        at.reset(0);
        for (int k = 1; k < 300; k++) assertEquals(PathWatchdog.Status.OK, at.tick(false, 1.0, k));
    }

    @Test
    void safetyNetTripsOnBursts() {
        SafetyNet n = new SafetyNet();
        n.maxErrors = 3;
        n.windowTicks = 100;
        RuntimeException ex = new RuntimeException("x");
        assertFalse(n.fail(ex, 0));
        assertFalse(n.fail(ex, 50));
        assertFalse(n.fail(ex, 300), "old errors age out");
        assertFalse(n.fail(ex, 301));
        assertFalse(n.fail(ex, 302));
        assertTrue(n.fail(ex, 303));
        assertSame(ex, n.last());
    }

    @Test
    void telemetryTimelineBoundsAndJson() {
        CombatTelemetry t = new CombatTelemetry();
        t.event(0, CombatTelemetry.Kind.NOTE, "ignored without a fight", 0);
        assertNull(t.last());
        t.start("Steve", 100, 0);
        t.event(102, CombatTelemetry.Kind.VERDICT, "WIN", 1.4);
        t.event(104, CombatTelemetry.Kind.VERDICT, "WIN", 1.5); // collapsed
        t.event(106, CombatTelemetry.Kind.DEALT, "target hp 14", 6);
        t.event(108, CombatTelemetry.Kind.TAKEN, "hp 16", 4);
        t.event(110, CombatTelemetry.Kind.DEALT, "target hp 6", 8);
        CombatTelemetry.Fight f = t.end("WIN", 140);
        assertNotNull(f);
        assertEquals(14, f.dealt, 1e-9);
        assertEquals(4, f.taken, 1e-9);
        assertEquals(2.0, f.durationSeconds(), 1e-9);
        assertEquals(6, f.events.size(), "START, VERDICT, DEALT, TAKEN, DEALT, END");
        assertTrue(f.summary().startsWith("WIN vs Steve"));
        List<String> tl = f.timeline(2);
        assertEquals(2, tl.size());
        assertTrue(tl.get(1).contains("END WIN"));
        CombatTelemetry.Fight back = CombatTelemetry.fromJsonLine(CombatTelemetry.toJsonLine(f));
        assertEquals("Steve", back.opponent);
        assertEquals(f.events.size(), back.events.size());
        assertEquals(CombatTelemetry.Kind.VERDICT, back.events.get(1).kind());
        // bounds
        t.start("Spam", 0, 0);
        for (int i = 0; i < CombatTelemetry.MAX_EVENTS * 2; i++) t.event(i, CombatTelemetry.Kind.DEALT, "x", 1);
        assertEquals(CombatTelemetry.MAX_EVENTS, t.current().events.size());
        for (int i = 0; i < CombatTelemetry.MAX_FIGHTS + 5; i++) {
            t.start("f" + i, i, 0);
            t.end("WIN", i + 1);
        }
        assertEquals(CombatTelemetry.MAX_FIGHTS, t.history().size());
        assertEquals(List.of("b", "c"), CombatTelemetry.trim(List.of("a", "b", "c"), 2));
    }

    @Test
    void weaponLearningIsClampedPersistentAndDecays() {
        OpponentMemory m = new OpponentMemory();
        assertEquals(1.0, m.weaponBias("AXE", null), 1e-9);
        for (int i = 0; i < 12; i++) m.recordOutcome("Steve", OpponentMemory.Outcome.LOSS, "AXE", i);
        for (int i = 0; i < 12; i++) m.recordOutcome("Alex", OpponentMemory.Outcome.WIN, "SWORD", i);
        assertTrue(m.weaponBias("AXE", null) < 1.0);
        assertEquals(0.85, m.weaponBias("AXE", null), 1e-9, "clamped");
        assertEquals(1.15, m.weaponBias("SWORD", null), 1e-9);
        assertEquals(1.0, m.weaponBias("MACE", null), 1e-9, "unknown weapon is neutral");
        assertTrue(m.combinedBias("Steve", "AXE") >= 0.75);
        assertTrue(m.combinedBias("Steve", "AXE") < m.combinedBias("Steve", null) + 1e-9);
        // persists (decision 4: file in the mod config folder)
        StringWriter w = new StringWriter();
        m.write(w);
        OpponentMemory back = new OpponentMemory();
        back.read(new StringReader(w.toString()));
        assertEquals(m.weaponBias("AXE", null), back.weaponBias("AXE", null), 1e-9);
        assertEquals(m.combinedBias("Alex", "SWORD"), back.combinedBias("Alex", "SWORD"), 1e-9);
        // files written before P3 (no byWeapon) still load
        OpponentMemory old = new OpponentMemory();
        old.read(new StringReader("{\"bob\":{\"skill\":0.7,\"wins\":1,\"losses\":3,\"escapes\":0,\"lastSeen\":5}}"));
        assertEquals(1.0, old.weaponBias("SWORD", "bob"), 1e-9);
        assertTrue(old.ratioBias("bob") < 1.0);
        // decay keeps it adaptive
        OpponentMemory d = new OpponentMemory();
        for (int i = 0; i < 60; i++) d.recordOutcome("x", OpponentMemory.Outcome.LOSS, "SWORD", i);
        assertTrue(d.get("x").losses <= OpponentMemory.DECAY_AT);
    }
}
