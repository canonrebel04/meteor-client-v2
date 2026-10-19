package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.utils.misc.ListMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemESPTest {
    @Test
    void filter() {
        assertTrue(ItemESP.shouldShow(ListMode.Blacklist, false, 1, 1));
        assertFalse(ItemESP.shouldShow(ListMode.Blacklist, true, 1, 1));
        assertTrue(ItemESP.shouldShow(ListMode.Whitelist, true, 5, 1));
        assertFalse(ItemESP.shouldShow(ListMode.Whitelist, false, 5, 1));
        assertFalse(ItemESP.shouldShow(ListMode.Blacklist, false, 3, 8));
    }
}
