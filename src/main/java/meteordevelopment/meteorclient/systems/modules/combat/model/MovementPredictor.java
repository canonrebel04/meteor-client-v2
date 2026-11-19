/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Alpha-beta (steady-state Kalman) filter over an entity's position (C13): smooths jittery server positions and
 * predicts where it will be {@code n} ticks ahead, for aim lead and intercept/flee planning.
 */
public final class MovementPredictor {
    private final double alpha, beta;
    private final double[] pos = new double[3];
    private final double[] vel = new double[3];
    private long lastTick = Long.MIN_VALUE;
    private boolean init;

    public MovementPredictor() {
        this(0.6, 0.3);
    }

    public MovementPredictor(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
    }

    public void update(long tick, double x, double y, double z) {
        double[] m = {x, y, z};
        if (!init) {
            System.arraycopy(m, 0, pos, 0, 3);
            lastTick = tick;
            init = true;
            return;
        }
        double dt = Math.max(1, tick - lastTick);
        lastTick = tick;
        for (int i = 0; i < 3; i++) {
            double pred = pos[i] + vel[i] * dt;
            double r = m[i] - pred;
            pos[i] = pred + alpha * r;
            vel[i] = vel[i] + (beta / dt) * r;
        }
    }

    public boolean ready() {
        return init;
    }

    /** Predicted position {@code ticks} ahead; vertical motion is clamped so jumps don't extrapolate to the sky. */
    public double[] predict(double ticks) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = pos[i] + vel[i] * ticks;
        if (vel[1] > 0) out[1] = pos[1] + Math.min(vel[1] * ticks, 1.3);
        return out;
    }

    public double[] velocity() {
        return vel.clone();
    }

    public double horizontalSpeed() {
        return Math.hypot(vel[0], vel[2]);
    }

    /** Rate (blocks/tick) at which this entity closes on point (px,pz); positive = approaching. */
    public double closingSpeed(double px, double pz) {
        double dx = px - pos[0], dz = pz - pos[2];
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return 0;
        return (vel[0] * dx + vel[2] * dz) / len;
    }
}
