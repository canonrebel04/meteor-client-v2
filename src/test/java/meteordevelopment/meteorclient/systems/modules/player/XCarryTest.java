package meteordevelopment.meteorclient.systems.modules.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class XCarryTest {
    @Test
    void onlyPlayerInventory() {
        assertTrue(XCarry.shouldCancel(0));
        assertFalse(XCarry.shouldCancel(1));
        assertFalse(XCarry.shouldCancel(42));
    }
}
