/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

import java.util.Objects;

/**
 * Plan S73: overlay anti-flicker. A new value must stay the same for {@code settle} seconds before it is shown,
 * and a shown value stays at least {@code minDisplay} seconds. Time is passed in, so it's pure.
 */
public final class Debouncer<T> {
    private final double settle;
    private final double minDisplay;
    private T shown;
    private T pending;
    private double pendingSince;
    private double shownSince = Double.NEGATIVE_INFINITY;

    public Debouncer(double settle, double minDisplay) {
        this.settle = settle;
        this.minDisplay = minDisplay;
    }

    /** Offer the current raw value at time {@code now} (seconds); returns the value to display. */
    public T update(T raw, double now) {
        if (shown == null && raw != null && pending == null) {
            shown = raw;
            shownSince = now;
            return shown;
        }
        if (Objects.equals(raw, shown)) {
            pending = null;
            return shown;
        }
        if (!Objects.equals(raw, pending)) {
            pending = raw;
            pendingSince = now;
        }
        if (now - pendingSince >= settle && now - shownSince >= minDisplay) {
            shown = pending;
            shownSince = now;
            pending = null;
        }
        return shown;
    }

    public T shown() {
        return shown;
    }
}
