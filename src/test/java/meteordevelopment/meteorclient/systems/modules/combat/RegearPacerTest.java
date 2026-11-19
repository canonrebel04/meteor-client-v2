/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PS2 S40/S41: regear pacing falls back to a fixed interval when Baritone's shared layer is absent. */
class RegearPacerTest {
    @Test
    void fallsBackWithoutBaritone() {
        // Baritone is compileOnly, so the test runtime exercises the no-Baritone path
        RegearPacer p = RegearPacer.create(0);
        assertInstanceOf(RegearPacer.Fixed.class, p);
    }

    @Test
    void fixedIntervalAndOpenDelay() {
        RegearPacer p = new RegearPacer.Fixed(3);
        p.opened(10);
        int waited = 0;
        while (!p.ready(0, 9)) waited++;
        assertEquals(7, waited);
        int clicks = 1;
        for (int t = 0; t < 30; t++) if (p.ready(5, 9)) clicks++;
        assertEquals(11, clicks);
        assertTrue(p.closeDelay() > 0);
    }
}
