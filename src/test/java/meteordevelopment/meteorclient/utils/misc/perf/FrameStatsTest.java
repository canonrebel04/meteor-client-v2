/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S93/S94: frame telemetry lowers quality while slow, raises it back with hysteresis. */
class FrameStatsTest {
    private static void feed(FrameStats s, double ms, int frames) {
        for (int i = 0; i < frames; i++) s.record((long) (ms * 1e6));
    }

    @Test
    void degradesAndRecovers() {
        FrameStats s = new FrameStats(20, 30);
        feed(s, 10, 240);
        assertEquals(1.0, s.quality(), 1e-9);
        feed(s, 40, 240);
        assertTrue(s.level() > 0, s.summary());
        assertTrue(s.quality() < 1);
        // between 0.7*target and target: hold
        feed(s, 17, 240);
        int lvl = s.level();
        feed(s, 17, 240);
        assertEquals(lvl, s.level());
        feed(s, 8, 240 * 4);
        assertEquals(0, s.level(), s.summary());
        assertTrue(s.level() <= 3 && s.quality() >= 0.25);
    }

    @Test
    void percentiles() {
        FrameStats s = new FrameStats(20, 30);
        for (int i = 1; i <= 100; i++) s.record(i * 1_000_000L);
        assertEquals(95_000_000L, s.percentile(95));
    }
}
