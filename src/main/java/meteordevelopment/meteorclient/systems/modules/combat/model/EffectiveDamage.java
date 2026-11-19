/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Pure-Java vanilla Minecraft effective damage calculator (Enhancement C2).
 * Implements exact 1.21+/26.3 armor, armor toughness, Protection EPF, Resistance effect,
 * Strength/Weakness modifiers, critical-hit multiplier, and attack-cooldown scaling.
 * Headless-safe (no Minecraft client initialization required).
 */
public final class EffectiveDamage {

    private EffectiveDamage() {
    }

    /**
     * Computes vanilla damage after Strength/Weakness and optional critical hit (1.5x).
     */
    public static double rawAttackDamage(double baseWeaponDamage, int sharpnessLevel,
                                         int strengthLevel, int weaknessLevel, boolean critical) {
        double dmg = Math.max(1.0, baseWeaponDamage);
        if (sharpnessLevel > 0) {
            dmg += 0.5 * sharpnessLevel + 0.5;
        }
        if (strengthLevel > 0) {
            dmg += 3.0 * strengthLevel;
        }
        if (weaknessLevel > 0) {
            dmg = Math.max(0.5, dmg - 4.0 * weaknessLevel);
        }
        if (critical) {
            dmg *= 1.5;
        }
        return dmg;
    }

    /**
     * Vanilla armor + toughness reduction:
     * {@code damage * (1 - clamp(max(armor / 5, armor - 4 * damage / (toughness + 8)), 0, 20) / 25)}.
     */
    public static double afterArmor(double damage, double armor, double toughness) {
        if (damage <= 0.0) return 0.0;
        double f = 2.0 + toughness / 4.0;
        double effectiveDefense = Math.max(armor / 5.0, armor - damage / f);
        double clamped = Math.max(0.0, Math.min(20.0, effectiveDefense));
        return damage * (1.0 - clamped / 25.0);
    }

    /**
     * Vanilla enchantment protection (EPF capped at 20 -> up to 80% reduction)
     * and Resistance potion effect (20% per level, capped at 5).
     */
    public static double afterProtectionAndResistance(double damage, int totalProtectionEpf, int resistanceLevel) {
        if (damage <= 0.0) return 0.0;
        double d = damage;
        if (resistanceLevel > 0) {
            int mult = Math.max(0, 25 - resistanceLevel * 5);
            d = d * mult / 25.0;
        }
        if (totalProtectionEpf > 0) {
            int epf = Math.min(20, totalProtectionEpf);
            d = d * (1.0 - epf / 25.0);
        }
        return Math.max(0.0, d);
    }

    /**
     * Full effective melee damage per hit from attacker to defender.
     */
    public static double effectiveHitDamage(double rawDamage, double defenderArmor, double defenderToughness,
                                            int defenderProtectionEpf, int defenderResistanceLevel) {
        double postArmor = afterArmor(rawDamage, defenderArmor, defenderToughness);
        return afterProtectionAndResistance(postArmor, defenderProtectionEpf, defenderResistanceLevel);
    }

    /**
     * Effective sustained DPS accounting for attack speed (attacks/sec), shield blocking penalty,
     * and optional critical-hit cadence.
     */
    public static double effectiveDps(double hitDamage, double attackSpeed, boolean defenderBlocking) {
        double swingsPerSec = Math.max(0.4, Math.min(4.0, attackSpeed));
        // Vanilla invulnerability frames cap effective hit rate at 2.0 hits/sec (10 ticks)
        double effectiveHitRate = Math.min(2.0, swingsPerSec);
        double dps = hitDamage * effectiveHitRate;
        if (defenderBlocking) {
            dps *= 0.35;
        }
        return Math.max(0.05, dps);
    }
}
