/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** S68/S69/S73-S75/S84/S85: easing, dt tweens, debounce, Kalman ETA. */
class AnimTest {
    @AfterEach
    void reset() {
        Anim.reduceMotion = false;
        Anim.highContrast = false;
    }

    @Test
    void easingEndpointsAndMonotone() {
        for (String c : new String[]{"linear", "smooth", "cubic", "cubic-in-out", "expo"}) {
            assertEquals(0, Ease.apply(c, 0), 1e-3, c);
            assertEquals(1, Ease.apply(c, 1), 1e-3, c);
            double prev = -1;
            for (int i = 0; i <= 100; i++) {
                double v = Ease.apply(c, i / 100.0);
                assertTrue(v >= prev - 1e-9, c + " not monotone at " + i);
                prev = v;
            }
        }
        assertEquals(0, Ease.apply("cubic", -5), 1e-9);
        assertEquals(1, Ease.apply("cubic", 5), 1e-9);
        assertEquals(1, Ease.spring(1), 0.01);
        assertEquals(5, Ease.lerp(0, 10, 0.5), 1e-9);
    }

    private static double run(double fps, double seconds) {
        Tween t = new Tween(Anim.NORMAL).set(0);
        t.target(100);
        double dt = 1 / fps;
        double max = 0;
        for (double time = 0; time < seconds - 1e-9; time += dt) max = Math.max(max, t.update(dt));
        assertTrue(max <= 100 + 1e-6, "overshoot " + max);
        return t.value();
    }

    @Test
    void tweenFrameRateIndependent() {
        double slow = run(30, 0.3), fast = run(240, 0.3);
        assertTrue(slow > 40 && slow < 100, "slow " + slow);
        assertEquals(slow, fast, 4, "30 vs 240 fps");
        assertEquals(100, run(60, 3), 0.5);
    }

    @Test
    void tweenZeroDtAndReduceMotion() {
        Tween t = new Tween(Anim.NORMAL).set(0);
        t.target(10);
        assertEquals(0, t.update(0), 1e-9);
        Anim.reduceMotion = true;
        assertEquals(10, t.update(0.016), 1e-9);
        assertTrue(t.settled(1e-6));
    }

    @Test
    void debouncerHoldsAndSettles() {
        Debouncer<String> d = new Debouncer<>(0.15, 0.6);
        assertEquals("A", d.update("A", 0));
        // flicker shorter than settle is ignored
        assertEquals("A", d.update("B", 0.7));
        assertEquals("A", d.update("A", 0.75));
        assertEquals("A", d.update("B", 0.8));
        assertEquals("A", d.update("B", 0.9));
        assertEquals("B", d.update("B", 0.96));
        // min display: a change right after is held
        assertEquals("B", d.update("C", 1.0));
        assertEquals("B", d.update("C", 1.3));
        assertEquals("C", d.update("C", 1.6));
    }

    @Test
    void etaFilterSmoothsCountsDownAndResets() {
        EtaFilter f = new EtaFilter();
        assertEquals(100, f.update(100, 0), 1e-9);
        // noisy measurements around a countdown stay close to the truth and move less than the noise
        double prev = 100;
        double[] noise = {8, -7, 6, -9, 5, -6, 7, -8};
        for (int i = 1; i <= noise.length; i++) {
            double est = f.update(100 - i + noise[i - 1], 1);
            assertTrue(Math.abs(est - (100 - i)) < 6, "est " + est);
            assertTrue(Math.abs(est - prev) < 6, "jump " + (est - prev));
            prev = est;
        }
        // a big change (plan replaced) resets
        assertEquals(400, f.update(400, 1), 1e-9);
        assertEquals("1m 05s", EtaFilter.format(65));
        assertEquals("42s", EtaFilter.format(42));
        assertEquals("?", EtaFilter.format(-1));
    }

    @Test
    void accessibilitySwitches() {
        assertEquals(110, Anim.backgroundAlpha(110));
        assertTrue(Anim.pulse(0.15, 0.6) > 0.9);
        Anim.highContrast = true;
        Anim.reduceMotion = true;
        assertEquals(255, Anim.backgroundAlpha(110));
        assertEquals(1, Anim.pulse(0.45, 0.6), 1e-9);
    }
}
