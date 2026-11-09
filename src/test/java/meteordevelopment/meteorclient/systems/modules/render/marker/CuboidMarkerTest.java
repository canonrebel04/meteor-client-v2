package meteordevelopment.meteorclient.systems.modules.render.marker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CuboidMarkerTest {
    @Test
    void boxCounts() {
        assertEquals(1, CuboidMarker.boxes(CuboidMarker.Mode.Full, 0, 0, 0, 4, 3, 5).length);
        assertEquals(1, CuboidMarker.boxes(CuboidMarker.Mode.Outline, 0, 0, 0, 4, 3, 5).length);
        assertEquals(4, CuboidMarker.boxes(CuboidMarker.Mode.Walls, 0, 0, 0, 4, 3, 5).length);
        double[][] floor = CuboidMarker.boxes(CuboidMarker.Mode.Floor, 0, 0, 0, 4, 3, 5);
        assertEquals(1, floor.length);
        assertEquals(CuboidMarker.SLAB, floor[0][4], 1e-12);
    }

    @Test
    void wallsStayInsideBounds() {
        for (double[] b : CuboidMarker.boxes(CuboidMarker.Mode.Walls, 1, 2, 3, 5, 6, 7)) {
            assertTrue(b[0] >= 1 && b[3] <= 5 && b[2] >= 3 && b[5] <= 7);
            assertEquals(2, b[1]);
            assertEquals(6, b[4]);
        }
    }
}
