package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoTraderTest {
    @Test
    void picksFirstWantedInStockAffordable() {
        assertEquals(-1, AutoTrader.nextTrade(List.of()));
        assertEquals(2, AutoTrader.nextTrade(List.of(
            new boolean[]{false, false, false, true},
            new boolean[]{true, false, true, true},
            new boolean[]{false, true, false, true}
        )));
        assertEquals(-1, AutoTrader.nextTrade(List.<boolean[]>of(new boolean[]{true, false, false, false})));
    }
}
