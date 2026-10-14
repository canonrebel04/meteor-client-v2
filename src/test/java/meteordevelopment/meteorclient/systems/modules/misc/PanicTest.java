package meteordevelopment.meteorclient.systems.modules.misc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanicTest {
    @Test
    void rules() {
        assertTrue(Panic.shouldDisable(false, true, false, true, false));
        assertFalse(Panic.shouldDisable(true, true, false, true, false));
        assertFalse(Panic.shouldDisable(false, false, false, true, false));
        assertFalse(Panic.shouldDisable(false, true, true, true, false));
        assertTrue(Panic.shouldDisable(false, true, true, false, false));
        assertFalse(Panic.shouldDisable(false, true, false, false, true));
    }
}
