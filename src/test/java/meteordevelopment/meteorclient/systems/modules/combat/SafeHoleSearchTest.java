/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.misc.perf.TickBudget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Plan S86/S88: the emergency-hole search is time sliced, finds the best hole and caches it. */
class SafeHoleSearchTest {
    private static double score(int x, int y, int z, int[][] holes) {
        for (int[] h : holes) {
            if (h[0] == x && h[1] == y && h[2] == z) return h[3] + Math.sqrt(x * x + y * y + z * z);
        }
        return Double.NaN;
    }

    @Test
    void findsBestHoleOverSeveralTicksAndCaches() {
        int[][] holes = {{10, 0, 10, 0}, {3, 1, 2, 5}, {-12, -2, 4, 0}, {2, 0, 2, 0}};
        AtomicLong now = new AtomicLong();
        TickBudget budget = new TickBudget(500, now::get);
        SafeHoleSearch search = new SafeHoleSearch(40, 3);
        int[] probes = {0};
        SafeHoleSearch.Probe probe = (x, y, z) -> {
            probes[0]++;
            now.incrementAndGet();
            return score(x, y, z, holes);
        };
        int tick = 0;
        boolean ready = false;
        while (!ready && tick < 200) {
            budget.beginTick();
            ready = search.tick(tick, 0, 0, 0, 16, budget, probe);
            tick++;
        }
        assertTrue(ready);
        assertTrue(tick > 5, "must be spread over ticks, took " + tick);
        assertTrue(tick <= 33 * 33 * 9 / SafeHoleSearch.MIN_CELLS_PER_TICK + 1, "bounded, took " + tick);
        assertTrue(search.found());
        assertEquals(2, search.x());
        assertEquals(0, search.y());
        assertEquals(2, search.z());

        // Cached: no more probes while fresh and close
        int before = probes[0];
        budget.beginTick();
        assertTrue(search.tick(tick + 10, 1, 0, -1, 16, budget, probe));
        assertEquals(before, probes[0]);

        // Stale: rescans
        budget.beginTick();
        assertFalse(search.tick(tick + 100, 0, 0, 0, 16, budget, probe));
        assertTrue(search.isRunning());

        // Moving away restarts the scan from the new position
        budget.beginTick();
        assertFalse(search.tick(tick + 101, 10, 0, 10, 16, budget, probe));
        assertTrue(search.isRunning());
    }

    @Test
    void reportsNoHole() {
        SafeHoleSearch search = new SafeHoleSearch(40, 3);
        boolean ready = false;
        for (int t = 0; t < 100 && !ready; t++) {
            ready = search.tick(t, 0, 0, 0, 4, null, (x, y, z) -> Double.NaN);
        }
        assertTrue(ready);
        assertFalse(search.found());
    }

    @Test
    void invalidateForcesRescan() {
        SafeHoleSearch search = new SafeHoleSearch(40, 3);
        assertTrue(search.tick(0, 0, 0, 0, 2, null, (x, y, z) -> x == 1 && y == 0 && z == 1 ? 1 : Double.NaN));
        assertTrue(search.found());
        search.invalidate();
        assertFalse(search.found());
        assertTrue(search.tick(1, 0, 0, 0, 2, null, (x, y, z) -> Double.NaN));
        assertFalse(search.found());
    }
}
