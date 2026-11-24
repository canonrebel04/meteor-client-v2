/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Plan S92: a coarse spatial hash for entity lookups. Build once per pass, then query a radius
 * without scanning every entity. Cell size is in blocks.
 */
public final class EntitySpatialIndex<T> {
    private final int cell;
    private final Map<Long, List<T>> cells = new HashMap<>();
    private int size;

    public EntitySpatialIndex(int cellSize) {
        this.cell = Math.max(1, cellSize);
    }

    public void clear() {
        cells.clear();
        size = 0;
    }

    public void add(double x, double z, T value) {
        if (value == null || !Double.isFinite(x) || !Double.isFinite(z)) return;
        long key = key(cellX(x), cellZ(z));
        cells.computeIfAbsent(key, k -> new ArrayList<>(4)).add(value);
        size++;
    }

    public int size() {
        return size;
    }

    /** Visits every entry whose cell overlaps the radius around {@code (x, z)}. */
    public void query(double x, double z, double radius, Consumer<T> consumer) {
        if (consumer == null || radius < 0 || !Double.isFinite(x) || !Double.isFinite(z)) return;
        int minX = cellX(x - radius);
        int maxX = cellX(x + radius);
        int minZ = cellZ(z - radius);
        int maxZ = cellZ(z + radius);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                List<T> bucket = cells.get(key(cx, cz));
                if (bucket == null) continue;
                for (T v : bucket) consumer.accept(v);
            }
        }
    }

    private int cellX(double x) {
        return (int) Math.floor(x / cell);
    }

    private int cellZ(double z) {
        return (int) Math.floor(z / cell);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }
}
