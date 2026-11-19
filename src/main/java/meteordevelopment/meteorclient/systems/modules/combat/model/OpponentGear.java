/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.List;
import java.util.Locale;

/**
 * Opponent gear snapshot (C11): per-slot armor tier, durability and enchantments, held/offhand items, elytra,
 * shield and offhand totem. Built from the entity by {@code OpponentTracker}; pure so verdict logic is testable.
 */
public record OpponentGear(
    List<Piece> armor,      // feet, legs, chest, head (null element = empty slot)
    WeaponKind mainHand,
    String mainHandId,
    WeaponKind offHand,
    boolean elytra,
    int sharpness,
    int knockback,
    int fireAspect,
    int breach,
    int density
) {
    public enum Tier {
        NONE(0), LEATHER(1), GOLD(2), CHAIN(2), COPPER(2), IRON(3), TURTLE(3), DIAMOND(4), NETHERITE(5);

        public final int rank;

        Tier(int rank) {
            this.rank = rank;
        }

        public static Tier fromItemId(String id) {
            if (id == null) return NONE;
            String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
            if (p.startsWith("netherite_")) return NETHERITE;
            if (p.startsWith("diamond_")) return DIAMOND;
            if (p.startsWith("iron_")) return IRON;
            if (p.startsWith("turtle_")) return TURTLE;
            if (p.startsWith("chainmail_")) return CHAIN;
            if (p.startsWith("golden_")) return GOLD;
            if (p.startsWith("copper_")) return COPPER;
            if (p.startsWith("leather_")) return LEATHER;
            return NONE;
        }
    }

    /** One armor piece: tier, remaining durability fraction [0..1], protection levels by type. */
    public record Piece(String itemId, Tier tier, double durability, int protection, int blastProtection,
                        int projectileProtection, int fireProtection, boolean mending) {
    }

    public boolean shield() {
        return offHand == WeaponKind.SHIELD || mainHand == WeaponKind.SHIELD;
    }

    public boolean totemOffhand() {
        return offHand == WeaponKind.TOTEM;
    }

    public int pieces() {
        int n = 0;
        if (armor != null) for (Piece p : armor) if (p != null) n++;
        return n;
    }

    public int netheritePieces() {
        int n = 0;
        if (armor != null) for (Piece p : armor) if (p != null && p.tier() == Tier.NETHERITE) n++;
        return n;
    }

    /** Average tier rank over 4 slots (empty = 0), 0..5. */
    public double averageTier() {
        if (armor == null) return 0;
        double s = 0;
        for (Piece p : armor) if (p != null) s += p.tier().rank;
        return s / 4.0;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(pieces()).append("pc avgTier=").append(String.format(Locale.ROOT, "%.1f", averageTier()));
        sb.append(" main=").append(mainHand);
        if (sharpness > 0) sb.append(" S").append(sharpness);
        if (shield()) sb.append(" shield");
        if (totemOffhand()) sb.append(" totem");
        if (elytra) sb.append(" elytra");
        return sb.toString();
    }
}
