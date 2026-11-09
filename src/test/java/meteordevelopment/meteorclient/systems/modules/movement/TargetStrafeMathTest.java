package meteordevelopment.meteorclient.systems.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetStrafeMathTest {
    @Test
    void onRingMovesTangentially() {
        double[] v = TargetStrafeMath.orbit(3, 0, 0, 0, 3, 1, 0.3);
        assertEquals(0, v[0], 1e-9);
        assertEquals(0.3, v[1], 1e-9);
        double[] cw = TargetStrafeMath.orbit(3, 0, 0, 0, 3, -1, 0.3);
        assertEquals(-0.3, cw[1], 1e-9);
    }

    @Test
    void tooFarPullsInward() {
        double[] v = TargetStrafeMath.orbit(10, 0, 0, 0, 3, 1, 0.3);
        assertTrue(v[0] < 0);
        assertEquals(0.3, Math.hypot(v[0], v[1]), 1e-9);
    }

    @Test
    void tooClosePushesOutward() {
        double[] v = TargetStrafeMath.orbit(1, 0, 0, 0, 3, 1, 0.3);
        assertTrue(v[0] > 0);
    }

    @Test
    void degenerateCenter() {
        double[] v = TargetStrafeMath.orbit(0, 0, 0, 0, 3, 1, 0.3);
        assertEquals(0.3, Math.hypot(v[0], v[1]), 1e-9);
    }
}
