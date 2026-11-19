/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Knockback planning (C59): netherite armor gives 0.1 knockback resistance per piece; blocking a hit with a shield
 * cancels the knockback; sprint-reset/W-tap or Knockback enchant increases it.
 */
public final class KnockbackModel {
    private KnockbackModel() {
    }

    public static double resistance(int netheritePieces, double other) {
        return Math.min(1.0, Math.max(0, netheritePieces) * 0.1 + Math.max(0, other));
    }

    /** Horizontal knockback strength (blocks/tick) of a hit: base 0.4, +0.5 per knockback level/sprint, scaled. */
    public static double horizontal(boolean sprintHit, int knockbackLevel, double resistance, boolean blockedByShield) {
        if (blockedByShield) return 0;
        double kb = 0.4 + 0.5 * ((sprintHit ? 1 : 0) + Math.max(0, knockbackLevel));
        return kb * (1 - Math.min(1, resistance));
    }

    /** Hazard check: would this knockback push us off an edge/into lava within {@code edgeDistance} blocks. */
    public static boolean dangerous(double kb, double edgeDistance) {
        // a knocked entity travels roughly kb / (1 - 0.91*0.6 friction) ~ 2.2*kb blocks on ground
        return kb * 2.2 >= edgeDistance;
    }
}
