package meteordevelopment.meteorclient.systems.modules.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketSanityTest {
    @Test
    void positions() {
        assertTrue(PacketSanity.validPosition(0, 64, 0));
        assertTrue(PacketSanity.validPosition(-2.9e7, -64, 2.9e7));
        assertFalse(PacketSanity.validPosition(Double.NaN, 0, 0));
        assertFalse(PacketSanity.validPosition(0, Double.POSITIVE_INFINITY, 0));
        assertFalse(PacketSanity.validPosition(3.0e7 + 1, 0, 0));
        assertFalse(PacketSanity.validPosition(0, 2.1e7, 0));
    }

    @Test
    void rotations() {
        assertTrue(PacketSanity.validRotationValues(720f, 45f));
        assertFalse(PacketSanity.validRotationValues(Float.NaN, 0));
        assertFalse(PacketSanity.pitchInRange(91f));
        assertEquals(90f, PacketSanity.clampPitch(120f));
        assertEquals(-90f, PacketSanity.clampPitch(-1000f));
    }

    @Test
    void slotsAndLengths() {
        assertTrue(PacketSanity.validHotbarSlot(0));
        assertTrue(PacketSanity.validHotbarSlot(8));
        assertFalse(PacketSanity.validHotbarSlot(9));
        assertFalse(PacketSanity.validHotbarSlot(-1));
        assertTrue(PacketSanity.validLength("a".repeat(256), PacketSanity.MAX_CHAT_LENGTH));
        assertFalse(PacketSanity.validLength("a".repeat(257), PacketSanity.MAX_CHAT_LENGTH));
        assertTrue(PacketSanity.validLength(null, 1));
    }

    @Test
    void rateLimiterWindow() {
        var r = new PacketSanity.RateLimiter();
        assertTrue(r.tryAcquire(1000, 2));
        assertTrue(r.tryAcquire(1100, 2));
        assertFalse(r.tryAcquire(1500, 2));
        assertTrue(r.tryAcquire(2000, 2));
    }

    @Test
    void chatThrottleMatchesVanilla() {
        var c = new PacketSanity.ChatThrottle();
        int sent = 0;
        while (c.tryAcquire(0)) sent++;
        assertEquals(10, sent); // 10 * 20 = 200
        for (int i = 0; i < 20; i++) c.tick();
        assertTrue(c.tryAcquire(0));
        var m = new PacketSanity.ChatThrottle();
        int sentWithMargin = 0;
        while (m.tryAcquire(20)) sentWithMargin++;
        assertEquals(9, sentWithMargin);
    }
}
