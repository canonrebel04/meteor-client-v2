/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.bot;

/**
 * A self-contained, utility-scored autonomous behavior unit.
 *
 * @param <C> Shared per-tick context type passed to the task.
 */
public interface BotTask<C> {
    /**
     * Human-readable name for HUD display and diagnostics.
     */
    String name();

    /**
     * Computes the utility score of this task for the current tick.
     *
     * @return {@code <= 0.0} when ineligible; positive values compete for execution.
     */
    double score(C ctx);

    /**
     * Called once when this task becomes active.
     */
    default void onStart(C ctx) {}

    /**
     * Executes one tick of the task.
     */
    TaskStatus tick(C ctx);

    /**
     * Called whenever this task finishes, fails, is preempted, or the runner stops.
     * Implementations must release held keys, close screens, and cancel pathing if owned.
     */
    default void onStop(C ctx) {}
}
