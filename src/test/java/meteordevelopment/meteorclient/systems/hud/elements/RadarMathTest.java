package meteordevelopment.meteorclient.systems.hud.elements;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadarMathTest {
    @Test
    void facingSouthEntityAheadIsUp() {
        double[] p = RadarMath.project(0, 10, 0, 20, 50);
        assertEquals(0, p[0], 1e-9);
        assertEquals(-25, p[1], 1e-9);
    }

    @Test
    void facingSouthWestIsRight() {
        double[] p = RadarMath.project(-10, 0, 0, 20, 50);
        assertEquals(25, p[0], 1e-9);
        assertEquals(0, p[1], 1e-9);
    }

    @Test
    void facingNorthEntitySouthIsDown() {
        double[] p = RadarMath.project(0, 10, 180, 20, 50);
        assertEquals(0, p[0], 1e-9);
        assertEquals(25, p[1], 1e-9);
    }

    @Test
    void outOfRange() {
        assertNull(RadarMath.project(30, 0, 0, 20, 50));
    }
}
