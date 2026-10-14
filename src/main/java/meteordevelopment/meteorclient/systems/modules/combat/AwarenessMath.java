/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

/**
 * Pure cover math over the {@link CombatTerrainGrid} 3D cell array
 * (index = gy * dim * dim + gz * dim + gx, player at the grid center,
 * feet on y-level {@code yLevels / 2}). No Minecraft dependencies so it can be unit tested.
 */
public final class AwarenessMath {
    private AwarenessMath() {}

    private static final int[] DIR_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DIR_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    private static boolean isSolid(byte[] grid3D, int dim, int gx, int gy, int gz) {
        if (gx < 0 || gz < 0 || gx >= dim || gz >= dim) return false;
        return grid3D[gy * dim * dim + gz * dim + gx] == CombatTerrainGrid.CELL_SOLID;
    }

    /**
     * Solid cells in the 2-block shell around the player at feet and head height
     * (spec: {@code getCoverCount()}). Max 2 * (5*5 - 1) = 48.
     */
    public static int coverCount(byte[] grid3D, int dim, int yLevels) {
        int c = dim / 2;
        int feet = yLevels / 2;
        int head = Math.min(feet + 1, yLevels - 1);
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (isSolid(grid3D, dim, c + dx, feet, c + dz)) count++;
                if (head != feet && isSolid(grid3D, dim, c + dx, head, c + dz)) count++;
            }
        }
        return count;
    }

    /**
     * Number of the 8 compass directions (N/S/E/W + diagonals) in which a solid
     * block exists at feet or head height within {@code range} blocks.
     */
    public static int coveredDirections(byte[] grid3D, int dim, int yLevels, int range) {
        int c = dim / 2;
        int feet = yLevels / 2;
        int head = Math.min(feet + 1, yLevels - 1);
        int covered = 0;
        for (int d = 0; d < 8; d++) {
            for (int step = 1; step <= range; step++) {
                int gx = c + DIR_X[d] * step;
                int gz = c + DIR_Z[d] * step;
                if (isSolid(grid3D, dim, gx, feet, gz) || isSolid(grid3D, dim, gx, head, gz)) {
                    covered++;
                    break;
                }
            }
        }
        return covered;
    }

    /** Player is "in the open" when fewer than 2 of 8 directions offer cover within 6 blocks. */
    public static boolean isInOpen(byte[] grid3D, int dim, int yLevels) {
        return coveredDirections(grid3D, dim, yLevels, 6) < 2;
    }

    /**
     * Threat multiplier from cover (spec section 7: 1.2 in the open, down to 0.8 fully covered).
     */
    public static double coverThreatFactor(int coverCount) {
        double frac = Math.min(1.0, coverCount / 16.0);
        return 1.2 - 0.4 * frac;
    }
}
