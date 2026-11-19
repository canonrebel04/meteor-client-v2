/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.List;

/**
 * Armor durability model (C18): low-durability pieces are about to break, and a broken piece takes its armor
 * points with it. Gives the effective armor fraction expected over the next {@code hits} hits.
 */
public final class ArmorDurability {
    /** Armor points per piece by tier and slot (feet, legs, chest, head), vanilla values. */
    static double armorPoints(OpponentGear.Tier tier, int slot) {
        int[] v = switch (tier) {
            case LEATHER -> new int[]{1, 2, 3, 1};
            case GOLD -> new int[]{1, 3, 5, 2};
            case CHAIN -> new int[]{1, 4, 5, 2};
            case COPPER -> new int[]{1, 3, 4, 2};
            case IRON -> new int[]{2, 5, 6, 2};
            case TURTLE -> new int[]{0, 0, 0, 2};
            case DIAMOND, NETHERITE -> new int[]{3, 6, 8, 3};
            default -> new int[]{0, 0, 0, 0};
        };
        return v[Math.max(0, Math.min(3, slot))];
    }

    /** Lowest remaining durability fraction over worn pieces (1 if none worn). */
    public static double weakest(List<OpponentGear.Piece> armor) {
        double w = 1.0;
        if (armor == null) return w;
        for (OpponentGear.Piece p : armor) if (p != null) w = Math.min(w, p.durability());
        return w;
    }

    /**
     * Fraction [0..1] of current armor points still present after {@code hits} more hits, assuming each hit costs
     * a piece about {@code wearPerHit} of its durability fraction (pieces with mending are treated as stable).
     */
    public static double armorRemainingAfter(List<OpponentGear.Piece> armor, int hits, double wearPerHit) {
        if (armor == null) return 1.0;
        double total = 0, left = 0;
        for (int slot = 0; slot < armor.size(); slot++) {
            OpponentGear.Piece p = armor.get(slot);
            if (p == null) continue;
            double pts = armorPoints(p.tier(), slot);
            total += pts;
            boolean breaks = !p.mending() && p.durability() - hits * wearPerHit <= 0;
            if (!breaks) left += pts;
        }
        return total <= 0 ? 1.0 : left / total;
    }

    /** "Weak spot" when a piece is under 10%: the fight gets easier soon (verdict bonus). */
    public static boolean weakSpot(List<OpponentGear.Piece> armor) {
        return weakest(armor) < 0.10;
    }
}
