/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Fail-open safety net (C100): counts exceptions from the brain's tick; more than {@link #maxErrors} inside
 * {@link #windowTicks} trips it so the caller can release every input and disable itself instead of looping on a
 * broken state. Pure.
 */
public final class SafetyNet {
    public int maxErrors = 5;
    public int windowTicks = 100;
    private final java.util.ArrayDeque<Long> errors = new java.util.ArrayDeque<>();
    private Throwable last;

    /** Record a failure at {@code tick}; true when the net trips. */
    public boolean fail(Throwable t, long tick) {
        last = t;
        errors.addLast(tick);
        while (!errors.isEmpty() && tick - errors.peekFirst() > windowTicks) errors.removeFirst();
        return errors.size() > maxErrors;
    }

    public int recent() {
        return errors.size();
    }

    public Throwable last() {
        return last;
    }

    public void reset() {
        errors.clear();
        last = null;
    }
}
