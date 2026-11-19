/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Plan PS1 perf helpers: S86 tick budget + time-sliced scan, S97 log throttle. */
class PerfCoreTest {
    @Test
    void budgetRunsOutAndResets() {
        AtomicLong now = new AtomicLong();
        TickBudget b = new TickBudget(100, now::get);
        b.beginTick();
        assertTrue(b.hasTime());
        now.addAndGet(150);
        assertFalse(b.hasTime());
        b.beginTick();
        assertTrue(b.hasTime());
        assertEquals(1, b.overruns());
    }

    @Test
    void scanVisitsEveryCellOnceNearestRingFirst() {
        TimeSlicedBoxScan scan = new TimeSlicedBoxScan();
        scan.start(10, 64, -5, 3, 1);
        Set<Long> seen = new HashSet<>();
        int[] lastRing = {0};
        boolean done = scan.step(null, 1, (x, y, z) -> {
            assertTrue(seen.add(((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF)), "duplicate");
            int ring = Math.max(Math.abs(x - 10), Math.abs(z + 5));
            assertTrue(ring >= lastRing[0], "ring order");
            lastRing[0] = ring;
            assertTrue(Math.abs(y - 64) <= 1);
            return false;
        });
        assertTrue(done);
        assertEquals(7 * 7 * 3, seen.size());
        assertTrue(scan.isDone());
    }

    @Test
    void scanYieldsWhenBudgetIsSpentAndResumes() {
        AtomicLong now = new AtomicLong();
        TickBudget b = new TickBudget(1000, now::get);
        TimeSlicedBoxScan scan = new TimeSlicedBoxScan();
        scan.start(0, 0, 0, 16, 4); // 33*33*9 = 9801 cells
        int[] visited = {0};
        int ticks = 0;
        boolean done = false;
        while (!done && ticks < 1000) {
            b.beginTick();
            int before = visited[0];
            done = scan.step(b, 64, (x, y, z) -> {
                visited[0]++;
                now.incrementAndGet(); // 1 "ns" per cell
                return false;
            });
            assertTrue(visited[0] - before <= 1016, "overshoot " + (visited[0] - before));
            ticks++;
        }
        assertTrue(done);
        assertEquals(33 * 33 * 9, visited[0]);
        assertTrue(ticks >= 9);
    }

    @Test
    void scanStopsWhenVisitorSaysSo() {
        TimeSlicedBoxScan scan = new TimeSlicedBoxScan();
        scan.start(0, 0, 0, 5, 0);
        int[] n = {0};
        assertTrue(scan.step(null, 1, (x, y, z) -> ++n[0] == 3));
        assertEquals(3, n[0]);
        assertFalse(scan.isRunning());
    }

    @Test
    void logThrottleCollapsesRepeats() {
        AtomicLong now = new AtomicLong(5000);
        LogThrottle t = new LogThrottle(() -> 1000, now::get);
        assertEquals(0, t.check("a"));
        assertEquals(LogThrottle.SUPPRESS, t.check("a"));
        assertEquals(LogThrottle.SUPPRESS, t.check("a"));
        assertEquals(0, t.check("b"));
        now.addAndGet(1000);
        int n = t.check("a");
        assertEquals(2, n);
        assertEquals(" (x3)", LogThrottle.suffix(n));
        assertEquals("", LogThrottle.suffix(0));
    }

    @Test
    void logThrottleDisabledWithZeroWindowAndBounded() {
        LogThrottle off = new LogThrottle(() -> 0, () -> 0L);
        for (int i = 0; i < 3; i++) assertEquals(0, off.check("x"));
        AtomicLong now = new AtomicLong();
        LogThrottle t = new LogThrottle(() -> 1000, now::get);
        for (int i = 0; i < LogThrottle.MAX_KEYS * 3; i++) assertEquals(0, t.check("k" + i));
    }
}
