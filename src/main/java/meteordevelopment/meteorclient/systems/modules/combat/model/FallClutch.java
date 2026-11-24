/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Fall-damage clutch timing (C47): when to place the water bucket during a fall.
 * Vanilla fall damage = ceil(fallDistance - 3 - safeFallBonus) (before feather falling / armor).
 */
public final class FallClutch {
    private FallClutch() {
    }

    public static double fallDamage(double fallDistance, int featherFalling) {
        double raw = Math.ceil(Math.max(0, fallDistance - 3.0));
        // Feather Falling is EPF 3 per level, 4% per EPF point, total capped at 80%
        double reduction = Math.min(0.8, Math.max(0, featherFalling) * 0.12);
        return raw * (1 - reduction);
    }

    /** A clutch is needed when the expected landing damage would leave us at <= {@code reserve} HP. */
    public static boolean needed(double fallDistanceSoFar, double blocksToGround, int featherFalling, double hp, double reserve) {
        return hp - fallDamage(fallDistanceSoFar + blocksToGround, featherFalling) <= reserve;
    }

    /**
     * Place the bucket when the ground is within reach this tick (placement reach ~4.5) and close enough that the
     * water lands under us before we do: between 1.0 and 2.5 + |vy| blocks above the ground.
     */
    public static boolean placeNow(double blocksToGround, double verticalSpeed) {
        double window = 2.5 + Math.abs(verticalSpeed);
        return blocksToGround >= 0.5 && blocksToGround <= Math.min(4.5, window);
    }

    /** Ticks to wait after placing before picking the water back up (land and let the source settle). */
    public static int pickupDelayTicks() {
        return 8;
    }
}
