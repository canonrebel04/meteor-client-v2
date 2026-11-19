/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import java.util.function.LongSupplier;

/**
 * Per-tick time budget for spreading expensive scans over several ticks (plan S86). Work loops call
 * {@link #hasTime()} every few iterations and yield once the budget for this tick is spent.
 */
public final class TickBudget {
    public static final long DEFAULT_BUDGET_NANOS = 1_000_000L; // 1 ms

    private static final TickBudget GAME = new TickBudget(DEFAULT_BUDGET_NANOS, System::nanoTime);

    private final long budget;
    private final LongSupplier clock;
    private long tickStart;
    private boolean exhausted;
    private int overruns;

    public TickBudget(long budget, LongSupplier clock) {
        this.budget = budget;
        this.clock = clock;
        this.tickStart = clock.getAsLong();
    }

    /** The shared game-thread budget, reset at the start of every client tick. */
    public static TickBudget game() {
        return GAME;
    }

    public void beginTick() {
        if (exhausted) overruns++;
        exhausted = false;
        tickStart = clock.getAsLong();
    }

    /** True while this tick's budget isn't spent. */
    public boolean hasTime() {
        boolean ok = clock.getAsLong() - tickStart < budget;
        if (!ok) exhausted = true;
        return ok;
    }

    public long budgetNanos() {
        return budget;
    }

    /** Ticks in which a time-sliced job used up the whole budget. */
    public int overruns() {
        return overruns;
    }
}
