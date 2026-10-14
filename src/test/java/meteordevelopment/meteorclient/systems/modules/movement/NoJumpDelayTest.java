package meteordevelopment.meteorclient.systems.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoJumpDelayTest {
    @Test
    void clamps() {
        assertEquals(0, NoJumpDelay.clampCooldown(10, 0, true, true));
        assertEquals(3, NoJumpDelay.clampCooldown(10, 3, false, false));
        assertEquals(2, NoJumpDelay.clampCooldown(2, 5, true, true));
    }

    @Test
    void groundOnly() {
        assertEquals(10, NoJumpDelay.clampCooldown(10, 0, true, false));
    }
}
