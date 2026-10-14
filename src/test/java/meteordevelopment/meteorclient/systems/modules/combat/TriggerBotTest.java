package meteordevelopment.meteorclient.systems.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotTest {
    @Test
    void waitsForChargeAndReaction() {
        assertFalse(TriggerBot.shouldAttack(0.9f, 1.0, 5, 1));
        assertFalse(TriggerBot.shouldAttack(1.0f, 1.0, 0, 1));
        assertTrue(TriggerBot.shouldAttack(1.0f, 1.0, 1, 1));
        assertTrue(TriggerBot.shouldAttack(0.6f, 0.5, 3, 3));
    }
}
