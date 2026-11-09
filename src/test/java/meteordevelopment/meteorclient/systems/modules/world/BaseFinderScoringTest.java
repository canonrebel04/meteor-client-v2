package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseFinderScoringTest {
    @Test
    void thresholdRespected() {
        assertFalse(BaseFinderScoring.isBase(4, 5, false, true));
        assertTrue(BaseFinderScoring.isBase(5, 5, false, true));
    }

    @Test
    void structureMarkerExcludes() {
        assertFalse(BaseFinderScoring.isBase(50, 5, true, true));
        assertTrue(BaseFinderScoring.isBase(50, 5, true, false));
    }

    @Test
    void zeroThresholdClampedToOne() {
        assertFalse(BaseFinderScoring.isBase(0, 0, false, true));
        assertTrue(BaseFinderScoring.isBase(1, 0, false, true));
    }
}
