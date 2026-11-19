/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.Random;

/**
 * Pursuer-shaking (C49) and diagonal running (C43): small, bounded bearing changes on each re-plan so a pursuer
 * can't pre-aim our path, without turning back towards them.
 */
public final class EvasiveJitter {
    private final Random random;
    private double last;

    public EvasiveJitter(long seed) {
        random = new Random(seed);
    }

    /** Next bearing offset in degrees within +/-maxDeg, changing sides more often than not. */
    public double next(double maxDeg) {
        double mag = maxDeg * (0.4 + 0.6 * random.nextDouble());
        double sign = last >= 0 ? (random.nextDouble() < 0.7 ? -1 : 1) : (random.nextDouble() < 0.7 ? 1 : -1);
        last = sign * mag;
        return last;
    }

    /** Re-plan interval in ticks: irregular 20-50. */
    public int replanTicks() {
        return 20 + random.nextInt(31);
    }
}
