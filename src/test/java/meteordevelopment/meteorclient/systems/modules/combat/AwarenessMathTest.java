/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwarenessMathTest {
    private static final int SIZE = 16;
    private static final int DIM = 2 * SIZE + 1;
    private static final int Y = 5;

    private static byte[] empty() {
        return new byte[DIM * Y * DIM];
    }

    private static void solid(byte[] g, int dx, int dy, int dz) {
        int c = DIM / 2, feet = Y / 2;
        g[(feet + dy) * DIM * DIM + (c + dz) * DIM + (c + dx)] = CombatTerrainGrid.CELL_SOLID;
    }

    @Test
    void emptyWorldIsOpenWithNoCover() {
        byte[] g = empty();
        assertEquals(0, AwarenessMath.coverCount(g, DIM, Y));
        assertEquals(0, AwarenessMath.coveredDirections(g, DIM, Y, 6));
        assertTrue(AwarenessMath.isInOpen(g, DIM, Y));
        assertEquals(1.2, AwarenessMath.coverThreatFactor(0), 1e-9);
    }

    @Test
    void oneByOneHoleIsCovered() {
        byte[] g = empty();
        for (int dy = 0; dy <= 1; dy++) {
            solid(g, 1, dy, 0); solid(g, -1, dy, 0); solid(g, 0, dy, 1); solid(g, 0, dy, -1);
        }
        assertEquals(8, AwarenessMath.coverCount(g, DIM, Y));
        assertEquals(4, AwarenessMath.coveredDirections(g, DIM, Y, 6)); // diagonals still open
        assertFalse(AwarenessMath.isInOpen(g, DIM, Y));
    }

    @Test
    void distantWallBeyondRangeDoesNotCount() {
        byte[] g = empty();
        solid(g, 7, 0, 0);
        assertEquals(0, AwarenessMath.coveredDirections(g, DIM, Y, 6));
        solid(g, 6, 0, 0);
        assertEquals(1, AwarenessMath.coveredDirections(g, DIM, Y, 6));
    }

    @Test
    void floorBlocksAreNotCover() {
        byte[] g = empty();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) solid(g, dx, -1, dz);
        assertEquals(0, AwarenessMath.coverCount(g, DIM, Y));
        assertTrue(AwarenessMath.isInOpen(g, DIM, Y));
    }

    @Test
    void coverFactorClampsAtFullCover() {
        assertEquals(0.8, AwarenessMath.coverThreatFactor(16), 1e-9);
        assertEquals(0.8, AwarenessMath.coverThreatFactor(48), 1e-9);
        assertEquals(1.0, AwarenessMath.coverThreatFactor(8), 1e-9);
    }
}
