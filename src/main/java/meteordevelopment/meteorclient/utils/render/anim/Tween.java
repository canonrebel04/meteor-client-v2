/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render.anim;

/**
 * Plan S68/S75: frame-rate independent value tween. A critically-damped spring towards the target, advanced by
 * real elapsed seconds (so 30 and 240 fps look the same), with {@link Anim#reduceMotion} snapping instantly.
 */
public final class Tween {
    private double value;
    private double velocity;
    private double target;
    private double smoothTime;
    private boolean initialized;

    /** @param smoothTime seconds to (nearly) reach a new target; ~{@link Anim#FAST}..{@link Anim#SLOW} */
    public Tween(double smoothTime) {
        this.smoothTime = Math.max(1e-4, smoothTime);
    }

    public Tween set(double v) {
        value = target = v;
        velocity = 0;
        initialized = true;
        return this;
    }

    public void target(double t) {
        if (!initialized) set(t);
        target = t;
    }

    public void smoothTime(double s) {
        smoothTime = Math.max(1e-4, s);
    }

    /** Advance by {@code dt} seconds and return the value. */
    public double update(double dt) {
        if (!initialized) return value;
        if (Anim.reduceMotion) {
            value = target;
            velocity = 0;
            return value;
        }
        if (dt <= 0) return value;
        // Unity-style SmoothDamp (critically damped, exact for any dt)
        double omega = 2.0 / smoothTime;
        double x = omega * dt;
        double exp = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
        double change = value - target;
        double temp = (velocity + omega * change) * dt;
        velocity = (velocity - omega * temp) * exp;
        double out = target + (change + temp) * exp;
        // no overshoot
        if ((target - value > 0) == (out > target)) {
            out = target;
            velocity = 0;
        }
        value = out;
        return value;
    }

    public double value() {
        return value;
    }

    public double target() {
        return target;
    }

    public boolean settled(double eps) {
        return Math.abs(value - target) <= eps && Math.abs(velocity) <= eps;
    }
}
