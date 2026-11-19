/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Flood-fill escape-space check (C49): how many standable cells are reachable from a point within a budget.
 * A low count means a dead end (pit, cave pocket, walled yard) that a pursuer can trap us in.
 */
public final class EscapeSpace {
    /** World access: can a player stand at (x, y, z) (feet), i.e. two passable blocks over a solid one. */
    @FunctionalInterface
    public interface Standable {
        boolean test(int x, int y, int z);
    }

    private EscapeSpace() {
    }

    /** Reachable standable cells from (x,y,z), walking with steps of up to 1 up / 3 down, capped at {@code limit}. */
    public static int reachable(Standable world, int x, int y, int z, int limit) {
        if (!world.test(x, y, z)) return 0;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{x, y, z});
        seen.add(key(x, y, z));
        int count = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && count < limit) {
            int[] c = q.poll();
            count++;
            for (int[] d : dirs) {
                for (int dy = 1; dy >= -3; dy--) {
                    int nx = c[0] + d[0], ny = c[1] + dy, nz = c[2] + d[1];
                    if (world.test(nx, ny, nz)) {
                        if (seen.add(key(nx, ny, nz))) q.add(new int[]{nx, ny, nz});
                        break;
                    }
                }
            }
        }
        return count;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
