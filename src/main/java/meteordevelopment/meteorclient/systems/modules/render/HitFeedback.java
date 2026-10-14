/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import java.util.HashMap;
import java.util.Map;

/** Pure helpers for {@link Hitmarkers}: health-delta tracking and fade curves. */
public final class HitFeedback {
    private HitFeedback() {}

    /** Linear fade from 1 to 0 over {@code duration}; clamps outside. */
    public static double fade(double elapsed, double duration) {
        if (duration <= 0) return 0;
        return Math.max(0, Math.min(1, 1 - elapsed / duration));
    }

    /** Tracks last-known health per entity id and reports changes. */
    public static final class HealthTracker {
        private final Map<Integer, Float> last = new HashMap<>();

        /** @return health change since last observation (negative = damage), or 0 for the first sighting. */
        public float update(int id, float health) {
            Float prev = last.put(id, health);
            return prev == null ? 0 : health - prev;
        }

        public void retain(java.util.Set<Integer> alive) {
            last.keySet().retainAll(alive);
        }

        public void clear() {
            last.clear();
        }

        public int size() {
            return last.size();
        }
    }
}
