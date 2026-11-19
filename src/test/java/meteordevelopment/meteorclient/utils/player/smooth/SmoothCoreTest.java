/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player.smooth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Plan PS1: S15 camera arbitration, S1/S3 fallback spring + GCD, S14 FOV smoothing. */
class SmoothCoreTest {
    @Test
    void higherPriorityClaimWins() {
        CameraClaimResolver r = new CameraClaimResolver();
        Object a = new Object(), b = new Object();
        r.beginTick();
        assertTrue(r.offer(a, 5, 10, 0, true));
        assertTrue(r.offer(b, 10, 20, 0, true));
        assertFalse(r.offer(a, 5, 11, 0, true));
        assertSame(b, r.owner());
        assertEquals(20, r.yaw());
    }

    @Test
    void equalPriorityKeepsLastTicksOwner() {
        CameraClaimResolver r = new CameraClaimResolver();
        Object a = new Object(), b = new Object();
        r.beginTick();
        assertTrue(r.offer(a, 10, 1, 0, true));
        for (int tick = 0; tick < 5; tick++) {
            r.beginTick();
            // b claims first this tick, but a held the camera last tick -> a takes it back
            r.offer(b, 10, 2, 0, true);
            r.offer(a, 10, 1, 0, true);
            assertSame(a, r.owner(), "tick " + tick);
        }
        // a stops claiming -> b gets it, and then keeps it on ties
        r.beginTick();
        r.offer(b, 10, 2, 0, true);
        assertSame(b, r.owner());
        r.beginTick();
        r.offer(a, 10, 1, 0, true);
        r.offer(b, 10, 2, 0, true);
        assertSame(b, r.owner());
        r.beginTick();
        r.offer(b, 10, 2, 0, true);
        assertFalse(r.offer(a, 10, 1, 0, true));
    }

    @Test
    void ownerCanUpdateItsClaim() {
        CameraClaimResolver r = new CameraClaimResolver();
        Object a = new Object();
        r.beginTick();
        r.offer(a, 10, 1, 0, true);
        assertTrue(r.offer(a, 10, 5, 3, false));
        assertEquals(5, r.yaw());
        assertEquals(3, r.pitch());
        assertFalse(r.smooth());
    }

    @Test
    void noClaimMeansNoOwner() {
        CameraClaimResolver r = new CameraClaimResolver();
        r.beginTick();
        assertFalse(r.hasClaim());
        assertNull(r.owner());
    }

    @Test
    void springSettlesWithoutOvershootAndRespectsCap() {
        AimSpring s = new AimSpring();
        s.reset(0);
        double max = 0, prev = 0;
        for (int i = 0; i < 80; i++) {
            double p = s.step(90, 1.6, 40);
            assertTrue(p - prev <= 40 + 1e-9, "cap");
            max = Math.max(max, p);
            prev = p;
        }
        assertTrue(max <= 90 + 1e-9, "overshoot " + max);
        assertEquals(90, s.position(), 1e-9);
    }

    @Test
    void springRetargetKeepsVelocityContinuous() {
        AimSpring s = new AimSpring();
        s.reset(0);
        for (int i = 0; i < 3; i++) s.step(60, 2.2, 40);
        double vBefore = s.velocity();
        s.step(-30, 2.2, 40);
        // Velocity changes, but it can't reverse instantly to full speed the other way
        assertTrue(s.velocity() > -Math.abs(vBefore) - 40);
        for (int i = 0; i < 80; i++) s.step(-30, 2.2, 40);
        assertEquals(-30, s.position(), 1e-6);
    }

    @Test
    void gcdQuantizeLandsOnGrid() {
        double step = AimSpring.gcdStep(0.5);
        assertEquals(Math.pow(0.5, 3) * 1.2, step, 1e-12);
        double q = AimSpring.quantize(12.3456, step);
        assertEquals((double) Math.round(q / step), q / step, 1e-9);
        assertEquals(12.3456, AimSpring.quantize(12.3456, 0), 0);
        assertEquals(AimSpring.gcdStep(0.5), AimSpring.gcdStep(-1), 0);
    }

    @Test
    void wrapDegreesRange() {
        assertEquals(-170, AimSpring.wrapDegrees(190), 1e-9);
        assertEquals(170, AimSpring.wrapDegrees(-190), 1e-9);
        assertEquals(-180, AimSpring.wrapDegrees(180), 1e-9);
    }

    @Test
    void fovSmootherEasesAndPassesThrough() {
        FovSmoother f = new FovSmoother();
        assertEquals(1.0f, f.apply(1.0f, 3));
        float v = f.apply(1.15f, 3);
        assertTrue(v > 1.0f && v < 1.15f);
        for (int i = 0; i < 60; i++) v = f.apply(1.15f, 3);
        assertEquals(1.15f, v, 1e-6);
        assertEquals(0.9f, f.apply(0.9f, 1));
        f.reset();
        assertEquals(1.3f, f.apply(1.3f, 5));
    }

    @Test
    void profilesMapToBaritoneNames() {
        assertEquals("LEGIT_SMOOTH", SmoothProfile.LegitSmooth.baritoneName);
        assertEquals("BALANCED", SmoothProfile.Balanced.baritoneName);
        assertEquals("FAST", SmoothProfile.Fast.baritoneName);
        assertTrue(SmoothProfile.LegitSmooth.fallbackTau > SmoothProfile.Fast.fallbackTau);
    }
}
