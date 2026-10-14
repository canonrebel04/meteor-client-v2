package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import static meteordevelopment.meteorclient.systems.modules.world.AutoFarmLogic.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoFarmLogicTest {
    @Test
    void agedCrops() {
        assertFalse(AutoFarmLogic.shouldHarvest(AGED, 6, 7, false));
        assertTrue(AutoFarmLogic.shouldHarvest(AGED, 7, 7, false));
        assertTrue(AutoFarmLogic.shouldBonemeal(AGED, 3, 7));
        assertFalse(AutoFarmLogic.shouldBonemeal(AGED, 7, 7));
    }

    @Test
    void stackedKeepsBase() {
        assertFalse(AutoFarmLogic.shouldHarvest(STACKED, 0, 0, false));
        assertTrue(AutoFarmLogic.shouldHarvest(STACKED, 0, 0, true));
    }

    @Test
    void gourdAndNone() {
        assertTrue(AutoFarmLogic.shouldHarvest(GOURD, 0, 0, false));
        assertFalse(AutoFarmLogic.shouldHarvest(NONE, 9, 0, true));
        assertFalse(AutoFarmLogic.shouldBonemeal(GOURD, 0, 7));
    }
}
