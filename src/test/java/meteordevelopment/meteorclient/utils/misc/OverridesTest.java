/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** C98: helper-module settings changed for combat are restored, unless the user changed them meanwhile. */
class OverridesTest {
    @Test
    void restoresOriginals() {
        AtomicReference<Boolean> pause = new AtomicReference<>(true);
        AtomicReference<Double> health = new AtomicReference<>(10.0);
        Overrides o = new Overrides();
        o.apply("pause", pause::get, pause::set, false);
        o.apply("health", health::get, health::set, 14.0);
        o.apply("health", health::get, health::set, 16.0); // second override keeps the first original
        assertFalse(pause.get());
        assertEquals(16.0, health.get());
        assertEquals(2, o.size());
        o.restoreAll();
        assertTrue(pause.get());
        assertEquals(10.0, health.get());
        assertEquals(0, o.size());
    }

    @Test
    void userChangeWins() {
        AtomicReference<Integer> v = new AtomicReference<>(1);
        Overrides o = new Overrides();
        o.apply("v", v::get, v::set, 5);
        v.set(7); // user edits the setting during combat
        o.restore("v");
        assertEquals(7, v.get());
        assertFalse(o.has("v"));
    }
}
