/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Collapses repeated log / chat lines (plan S97). The first occurrence of a key passes; repeats inside the window
 * are counted and dropped; the next line after the window carries a {@code " (xN)"} suffix with the total.
 * Thread safe and bounded to {@link #MAX_KEYS} keys.
 */
public final class LogThrottle {
    public static final int MAX_KEYS = 512;
    /** Returned by {@link #check} when the line should be dropped. */
    public static final int SUPPRESS = -1;

    private final LongSupplier windowMs;
    private final LongSupplier clock;
    private final Map<String, long[]> entries = new HashMap<>(); // key -> {windowStart, suppressed}

    public LogThrottle(long windowMs) {
        this(() -> windowMs, System::currentTimeMillis);
    }

    public LogThrottle(LongSupplier windowMs, LongSupplier clock) {
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** @return {@link #SUPPRESS} to drop the line, else how many repeats were dropped since the last one shown. */
    public synchronized int check(String key) {
        long window = windowMs.getAsLong();
        if (window <= 0) return 0;
        long now = clock.getAsLong();
        long[] e = entries.get(key);
        if (e == null) {
            if (entries.size() >= MAX_KEYS) evict(now, window);
            entries.put(key, new long[]{now, 0});
            return 0;
        }
        if (now - e[0] < window) {
            e[1]++;
            return SUPPRESS;
        }
        int suppressed = (int) Math.min(Integer.MAX_VALUE, e[1]);
        e[0] = now;
        e[1] = 0;
        return suppressed;
    }

    private void evict(long now, long window) {
        Iterator<long[]> it = entries.values().iterator();
        while (it.hasNext()) {
            if (now - it.next()[0] >= window) it.remove();
        }
        if (entries.size() >= MAX_KEYS) entries.clear();
    }

    /** Suffix for a line shown after {@code suppressed} repeats: {@code " (x(N+1))"}, or empty. */
    public static String suffix(int suppressed) {
        return suppressed > 0 ? " (x" + (suppressed + 1) + ")" : "";
    }
}
