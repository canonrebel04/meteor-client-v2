package meteordevelopment.meteorclient.systems.modules.render;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HitFeedbackTest {
    @Test
    void fadeCurve() {
        assertEquals(1, HitFeedback.fade(0, 100), 1e-9);
        assertEquals(0.5, HitFeedback.fade(50, 100), 1e-9);
        assertEquals(0, HitFeedback.fade(150, 100), 1e-9);
        assertEquals(0, HitFeedback.fade(10, 0), 1e-9);
    }

    @Test
    void trackerReportsDeltas() {
        var t = new HitFeedback.HealthTracker();
        assertEquals(0, t.update(1, 20));
        assertEquals(-4, t.update(1, 16), 1e-6);
        assertEquals(2, t.update(1, 18), 1e-6);
        t.update(2, 10);
        t.retain(Set.of(2));
        assertEquals(1, t.size());
        assertEquals(0, t.update(1, 5));
    }
}
