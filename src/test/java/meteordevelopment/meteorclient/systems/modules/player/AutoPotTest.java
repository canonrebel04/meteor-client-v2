package meteordevelopment.meteorclient.systems.modules.player;

import org.junit.jupiter.api.Test;

import static meteordevelopment.meteorclient.systems.modules.player.AutoPot.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoPotTest {
    @Test
    void decisions() {
        assertEquals(NONE, AutoPot.decide(15, 10, 0, true, true));
        assertEquals(NONE, AutoPot.decide(5, 10, 3, true, true));
        assertEquals(SOUP, AutoPot.decide(5, 10, 0, true, true));
        assertEquals(SPLASH, AutoPot.decide(10, 10, 0, false, true));
        assertEquals(NONE, AutoPot.decide(5, 10, 0, false, false));
    }
}
