package meteordevelopment.meteorclient.systems.modules.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DerpTest {
    @Test
    void spinWraps() {
        assertEquals(50, Derp.rotation(Derp.Mode.Spin, 2, 25, 0, 0)[0], 1e-4);
        assertEquals(10, Derp.rotation(Derp.Mode.Spin, 37, 10, 0, 0)[0], 1e-4);
    }

    @Test
    void randomInRange() {
        float[] lo = Derp.rotation(Derp.Mode.Random, 0, 0, 0, 0);
        float[] hi = Derp.rotation(Derp.Mode.Random, 0, 0, 0.999999, 0.999999);
        assertEquals(-180, lo[0], 1e-3);
        assertEquals(-90, lo[1], 1e-3);
        assertTrue(hi[0] < 180 && hi[1] < 90);
    }

    @Test
    void nodKeepsYaw() {
        float[] r = Derp.rotation(Derp.Mode.Nod, 1, 90, 0, 0);
        assertTrue(Float.isNaN(r[0]));
        assertEquals(90, r[1], 1e-3);
    }
}
