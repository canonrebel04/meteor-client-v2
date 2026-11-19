/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player.smooth;

/**
 * Extra low-pass on the camera's FOV target (plan S14) so sprint / speed / bow FOV changes ease in over a few
 * ticks instead of jumping by half the difference on the first tick. Pure Java.
 */
public final class FovSmoother {
    private float value = Float.NaN;

    /**
     * @param target the vanilla target FOV modifier for this tick
     * @param ticks  smoothing time constant in ticks; 1 or less passes the target straight through
     */
    public float apply(float target, int ticks) {
        if (ticks <= 1 || Float.isNaN(value) || Float.isNaN(target) || Float.isInfinite(target)) {
            value = target;
            return target;
        }
        value += (target - value) / ticks;
        if (Math.abs(target - value) < 1e-4f) value = target;
        return value;
    }

    public void reset() {
        value = Float.NaN;
    }
}
