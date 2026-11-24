package meteordevelopment.meteorclient.utils.misc.perf;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** S92/S96: entity spatial hash and trig lookup table. */
class PerfUtilsTest {

    @Test
    void trigLutMatchesMathSinCos() {
        for (int i = -720; i <= 720; i += 5) {
            double r = Math.toRadians(i);
            assertEquals(Math.sin(r), TrigLut.sin(r), 2e-4, "sin " + i);
            assertEquals(Math.cos(r), TrigLut.cos(r), 2e-4, "cos " + i);
        }
        assertTrue(Double.isNaN(TrigLut.sin(Double.NaN)));
        assertEquals(0.0, TrigLut.sinDeg(0), 2e-4);
        assertEquals(1.0, TrigLut.cosDeg(0), 2e-4);
    }

    @Test
    void spatialIndexFindsNearbyAndSkipsFar() {
        EntitySpatialIndex<String> idx = new EntitySpatialIndex<>(8);
        idx.add(0, 0, "origin");
        idx.add(3, 0, "near");
        idx.add(40, 0, "far");
        idx.add(Double.NaN, 0, "bad");
        assertEquals(3, idx.size());

        List<String> near = new ArrayList<>();
        idx.query(0, 0, 6, near::add);
        assertTrue(near.contains("origin"));
        assertTrue(near.contains("near"));
        assertFalse(near.contains("far"));

        List<String> wide = new ArrayList<>();
        idx.query(0, 0, 48, wide::add);
        assertEquals(3, wide.size());

        idx.clear();
        assertEquals(0, idx.size());
        List<String> empty = new ArrayList<>();
        idx.query(0, 0, 10, empty::add);
        assertTrue(empty.isEmpty());
    }
}
