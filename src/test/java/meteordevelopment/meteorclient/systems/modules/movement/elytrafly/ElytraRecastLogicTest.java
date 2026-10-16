package meteordevelopment.meteorclient.systems.modules.movement.elytrafly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElytraRecastLogicTest {
    private static ElytraRecastLogic.State falling(boolean jump, double fall, int ticks) {
        return new ElytraRecastLogic.State(true, false, false, false, false, jump, fall, ticks);
    }

    @Test
    void recastsWhenFallingHoldingJump() {
        assertTrue(ElytraRecastLogic.shouldRecast(falling(true, 1, 10), 0.5, 4));
    }

    @Test
    void respectsDelayFallAndJump() {
        assertFalse(ElytraRecastLogic.shouldRecast(falling(true, 1, 2), 0.5, 4));
        assertFalse(ElytraRecastLogic.shouldRecast(falling(true, 0.1, 10), 0.5, 4));
        assertFalse(ElytraRecastLogic.shouldRecast(falling(false, 1, 10), 0.5, 4));
    }

    @Test
    void blockedStates() {
        assertFalse(ElytraRecastLogic.shouldRecast(new ElytraRecastLogic.State(false, false, false, false, false, true, 2, 10), 0, 1));
        assertFalse(ElytraRecastLogic.shouldRecast(new ElytraRecastLogic.State(true, true, false, false, false, true, 2, 10), 0, 1));
        assertFalse(ElytraRecastLogic.shouldRecast(new ElytraRecastLogic.State(true, false, true, false, false, true, 2, 10), 0, 1));
        assertFalse(ElytraRecastLogic.shouldRecast(new ElytraRecastLogic.State(true, false, false, true, false, true, 2, 10), 0, 1));
    }
}
