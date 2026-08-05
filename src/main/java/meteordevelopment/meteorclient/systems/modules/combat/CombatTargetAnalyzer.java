/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatTargetAnalyzer {
    private CombatTargetAnalyzer() {}

    public static TargetAnalysis analyze(LivingEntity target) {
        if (target == null || mc.player == null) return null;

        double distance = mc.player.distanceTo(target);
        ItemStack weapon = target.getMainHandItem();
        String weaponName = weapon.isEmpty() ? "None" : weapon.getDisplayName().getString();
        float weaponDamage = DamageUtils.getAttackDamage(target, mc.player);
        String armorSummary = buildArmorSummary(target);
        float totalArmor = (float) target.getAttributeValue(Attributes.ARMOR);
        double targetReach = getEntityReach(target);
        float targetHealth = target.getHealth() + target.getAbsorptionAmount();
        double viabilityScore = calculateViability(target);

        // My side (FollowEngine inputs)
        double myReach = getEntityReach(mc.player);
        float rawMyDamage = DamageUtils.getAttackDamage(mc.player, target);
        float rawTheirDamage = DamageUtils.getAttackDamage(target, mc.player);
        double myPotionMod = getPotionDamageModifier(mc.player);
        double theirPotionMod = getPotionDamageModifier(target);
        float myDamage = (float) Math.max(0.0, rawMyDamage + myPotionMod);
        float theirDamage = (float) Math.max(0.0, rawTheirDamage + theirPotionMod);
        String potionEffects = buildPotionEffectsString(target);

        return new TargetAnalysis(
            target,
            distance,
            weaponName,
            weaponDamage,
            armorSummary,
            totalArmor,
            targetReach,
            targetHealth,
            viabilityScore,
            myReach,
            myDamage,
            theirDamage,
            potionEffects
        );
    }

    /**
     * Dynamic follow distance — keeps the player outside the target's effective
     * hit range based on reach, potion effects, and own weapon type.
     */
    public static double computeDynamicFollowDistance(TargetAnalysis a, double modePadding) {
        // Never stand inside either combatant's reach
        double base = Math.max(a.targetReach(), a.myReach());

        // Target hits hard (Strength II+) — stay further back
        if (a.potionEffects().contains("STR2")) base += 2.0;

        // We hit soft (Weakness) — kite harder
        if (a.potionEffects().contains("WEAK")) base += 3.0;

        // Ranged weapon — stay outside melee reach entirely
        ItemStack myWeapon = mc.player.getMainHandItem();
        if (myWeapon.is(Items.BOW) || myWeapon.is(Items.CROSSBOW) || myWeapon.is(Items.TRIDENT)) {
            base = Math.max(base, 6.0) + 1.0;
        }

        base += modePadding;

        return Mth.clamp(base, 1.0, 10.0);
    }

    /** Strength +3.0 damage per level, Weakness -4.0 per level, folded into effective damage. */
    private static double getPotionDamageModifier(LivingEntity entity) {
        double mod = 0.0;
        for (MobEffectInstance effect : entity.getActiveEffects()) {
            if (effect.getEffect().value() == MobEffects.STRENGTH) {
                mod += 3.0 * (effect.getAmplifier() + 1);
            } else if (effect.getEffect().value() == MobEffects.WEAKNESS) {
                mod -= 4.0 * (effect.getAmplifier() + 1);
            }
        }
        return mod;
    }

    /** Compact potion summary for the target: "STR2", "WEAK1", "STR2+WEAK1", or "NONE". */
    private static String buildPotionEffectsString(LivingEntity entity) {
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance effect : entity.getActiveEffects()) {
            if (effect.getEffect().value() == MobEffects.STRENGTH && effect.getAmplifier() >= 1) {
                sb.append("STR2");
            } else if (effect.getEffect().value() == MobEffects.WEAKNESS) {
                sb.append("WEAK").append(effect.getAmplifier() + 1);
            }
        }
        return sb.isEmpty() ? "NONE" : sb.toString();
    }

    public static double calculateViability(LivingEntity target) {
        if (mc.player == null || target == null) return 0.0;

        float myHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        float targetHealth = target.getHealth() + target.getAbsorptionAmount();
        if (myHealth <= 0.0f) return 0.0;

        float myDamage = DamageUtils.getAttackDamage(mc.player, target);
        float theirDamage = DamageUtils.getAttackDamage(target, mc.player);

        // Check for player with high-end gear — very dangerous
        if (target instanceof Player) {
            boolean hasDiamondOrNetherite = hasHighEndArmor(target);
            boolean hasSharpSword = hasSharpnessSword(target);
            if (hasDiamondOrNetherite && hasSharpSword) return 0.2;
        }

        // No armor, no weapon — easy prey
        boolean noArmor = target.getArmorValue() <= 0;
        boolean noWeapon = target.getMainHandItem().isEmpty();
        if (noArmor && noWeapon) return 0.9;

        // DPS comparison: who kills whom faster
        double myKillTime = myDamage > 0 ? targetHealth / myDamage : Double.MAX_VALUE;
        double theirKillTime = theirDamage > 0 ? myHealth / theirDamage : Double.MAX_VALUE;

        double score = 0.5;
        if (myKillTime < theirKillTime) {
            // I kill faster
            score = 0.5 + 0.4 * (1.0 - (myKillTime / Math.max(theirKillTime, 0.01)));
        } else {
            // They kill faster — penalize
            score = 0.5 - 0.4 * (1.0 - (theirKillTime / Math.max(myKillTime, 0.01)));
        }

        // Reach bonus: if I outrange them
        double myReach = getEntityReach(mc.player);
        double theirReach = getEntityReach(target);
        if (myReach > theirReach) {
            score += 0.1;
        } else if (theirReach > myReach) {
            score -= 0.1;
        }

        return Mth.clamp(score, 0.0, 1.0);
    }

    public static double getEntityReach(Entity entity) {
        if (entity.getType() == EntityType.PLAYER) return mc.player != null ? mc.player.entityInteractionRange() : 3.0;
        if (entity.getType() == EntityType.ENDERMAN) return 3.0;
        if (entity.getType() == EntityType.CREEPER) return 3.0;
        if (entity.getType() == EntityType.SPIDER || entity.getType() == EntityType.CAVE_SPIDER) return 2.0;
        if (entity.getType() == EntityType.ZOMBIE) return 2.0;
        if (entity.getType() == EntityType.SKELETON) return 2.0;
        if (entity.getType() == EntityType.PIGLIN) return 2.0;
        if (entity.getType() == EntityType.VINDICATOR) return 2.0;
        if (entity.getType() == EntityType.EVOKER) return 2.0;
        return 2.0;
    }

    /**
     * Calculates self-gear combat score for mc.player based on health, armor, and weapon quality.
     * Mirrors the viability structure:
     * - Health component (40%): (health + absorption) / 20.0 (clamped 0..1)
     * - Armor component (30%): armor attribute value / 20.0 (clamped 0..1)
     * - Weapon component (30%): weapon tier score + sharpness enchant bonus (clamped 0..1)
     */
    public static double myCombatScore() {
        if (mc.player == null) return 0.0;

        // Health term (40% weight): normalized current health + absorption
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double healthScore = Math.min(1.0, health / 20.0);

        // Armor term (30% weight): normalized armor attribute value
        double armorValue = mc.player.getAttributeValue(Attributes.ARMOR);
        double armorScore = Math.min(1.0, armorValue / 20.0);

        // Weapon term (30% weight): held item base score + sharpness enchant bonus
        ItemStack weapon = mc.player.getMainHandItem();
        double baseWeaponScore = getWeaponScore(weapon);
        int sharpnessLevel = Utils.getEnchantmentLevel(weapon, Enchantments.SHARPNESS);
        double weaponScore = Math.min(1.0, baseWeaponScore + (sharpnessLevel * 0.1));

        return (healthScore * 0.4) + (armorScore * 0.3) + (weaponScore * 0.3);
    }

    /**
     * Calculates effective combat score for a target entity.
     */
    public static double effectiveTargetScore(LivingEntity target) {
        if (target == null) return 0.0;

        float targetHealth = target.getHealth() + target.getAbsorptionAmount();
        double healthScore = Math.min(1.0, targetHealth / 20.0);

        double targetArmor = target.getAttributeValue(Attributes.ARMOR);
        double armorScore = Math.min(1.0, targetArmor / 20.0);

        ItemStack weapon = target.getMainHandItem();
        double baseWeaponScore = getWeaponScore(weapon);
        int sharpnessLevel = Utils.getEnchantmentLevel(weapon, Enchantments.SHARPNESS);
        double weaponScore = Math.min(1.0, baseWeaponScore + (sharpnessLevel * 0.1));

        return (healthScore * 0.4) + (armorScore * 0.3) + (weaponScore * 0.3);
    }

    /**
     * Determines if mc.player is undergeared relative to target by checking if
     * myCombatScore() is below the specified ratio of the target's score.
     */
    public static boolean undergeared(LivingEntity target, double ratio) {
        if (target == null || mc.player == null) return false;
        double myScore = myCombatScore();
        double targetScore = effectiveTargetScore(target);
        return myScore < ratio * targetScore;
    }

    /**
     * Calculates smart multi-target engagement score (0..1) based on distance, health, defense, and weapon threat.
     * Higher score = better target to engage.
     *
     * @param a target analysis snapshot
     * @param distanceWeight weight for distance (closer target = higher score)
     * @param healthWeight weight for target health (lower target health = higher score)
     * @param defenseWeight weight for target-armor defense (less armor = higher score)
     * @param weaponWeight weight for target-weapons threat (weaker weapon = higher score)
     * @param maxRange maximum search range for distance normalization (e.g. acquireRange / acquire-range from findBestTarget, replacing old targetRange)
     * @return normalized target score in range [0, 1]
     */
    public static double targetScore(TargetAnalysis a, double distanceWeight, double healthWeight, double defenseWeight, double weaponWeight, double maxRange) {
        if (a == null) return 0.0;

        double totalWeight = distanceWeight + healthWeight + defenseWeight + weaponWeight;
        if (totalWeight <= 0.0) return 0.5;

        // 1. Distance term (closer is better): normalized 0..1
        double distNorm = Math.min(1.0, a.distance() / Math.max(0.1, maxRange));
        double distanceTerm = Math.max(0.0, 1.0 - distNorm);

        // 2. Health term (lower health is better): normalized 0..1
        double healthNorm = Math.min(1.0, a.targetHealth() / 20.0);
        double healthTerm = Math.max(0.0, 1.0 - healthNorm);

        // 3. Defense / target-armor term (less armor is better): normalized 0..1
        double armorNorm = Math.min(1.0, a.totalArmor() / 20.0);
        double defenseTerm = Math.max(0.0, 1.0 - armorNorm);

        // 4. Target-weapons threat term (weaker weapon is better): normalized 0..1
        double weaponNorm = Math.min(1.0, a.weaponDamage() / 20.0);
        double weaponTerm = Math.max(0.0, 1.0 - weaponNorm);

        // Weighted scoring sum
        double score = (distanceWeight * distanceTerm
                      + healthWeight * healthTerm
                      + defenseWeight * defenseTerm
                      + weaponWeight * weaponTerm) / totalWeight;

        return Mth.clamp(score, 0.0, 1.0);
    }

    private static double getWeaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 0.4;
        var item = stack.getItem();
        if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD ||
            item == Items.NETHERITE_AXE || item == Items.DIAMOND_AXE) return 1.0;
        if (item == Items.IRON_SWORD || item == Items.IRON_AXE) return 0.7;
        if (item == Items.STONE_SWORD || item == Items.STONE_AXE) return 0.5;
        if (item == Items.WOODEN_SWORD || item == Items.WOODEN_AXE) return 0.3;
        if (item == Items.BOW || item == Items.CROSSBOW || item == Items.TRIDENT) return 0.6;
        return 0.4;
    }

    // --- Helpers ---

    private static String buildArmorSummary(LivingEntity entity) {
        if (!(entity instanceof Player)) return "None";

        Player player = (Player) entity;
        int protLevel = 0;
        int armorPieces = 0;
        String bestMaterial = "None";

        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack legs = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack feet = player.getItemBySlot(EquipmentSlot.FEET);

        for (ItemStack stack : new ItemStack[]{head, chest, legs, feet}) {
            if (stack.isEmpty()) continue;
            armorPieces++;

            protLevel += Utils.getEnchantmentLevel(stack, Enchantments.PROTECTION);

            String material = getArmorMaterial(stack);
            if (!material.equals("None")) bestMaterial = material;
        }

        if (armorPieces == 0) return "None";

        String result = bestMaterial + " " + armorPieces;
        if (protLevel > 0) result += " prot " + protLevel;
        return result;
    }

    private static String getArmorMaterial(ItemStack stack) {
        if (stack.isEmpty()) return "None";
        var item = stack.getItem();

        if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE ||
            item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) return "Netherite";
        if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE ||
            item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return "Diamond";
        if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE ||
            item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return "Iron";
        if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE ||
            item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) return "Chainmail";
        if (item == Items.GOLDEN_HELMET || item == Items.GOLDEN_CHESTPLATE ||
            item == Items.GOLDEN_LEGGINGS || item == Items.GOLDEN_BOOTS) return "Gold";
        if (item == Items.LEATHER_HELMET || item == Items.LEATHER_CHESTPLATE ||
            item == Items.LEATHER_LEGGINGS || item == Items.LEATHER_BOOTS) return "Leather";
        return "Unknown";
    }

    private static boolean hasHighEndArmor(LivingEntity entity) {
        if (!(entity instanceof Player player)) return false;

        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) return false;
            var item = stack.getItem();
            if (item != Items.NETHERITE_HELMET && item != Items.NETHERITE_CHESTPLATE &&
                item != Items.NETHERITE_LEGGINGS && item != Items.NETHERITE_BOOTS &&
                item != Items.DIAMOND_HELMET && item != Items.DIAMOND_CHESTPLATE &&
                item != Items.DIAMOND_LEGGINGS && item != Items.DIAMOND_BOOTS) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSharpnessSword(LivingEntity entity) {
        ItemStack weapon = entity.getMainHandItem();
        if (weapon.isEmpty()) return false;
        var item = weapon.getItem();
        if (item != Items.NETHERITE_SWORD && item != Items.DIAMOND_SWORD) return false;
        return Utils.getEnchantmentLevel(weapon, Enchantments.SHARPNESS) > 0;
    }

    // --- Target Analysis POJO ---

    public record TargetAnalysis(
        Entity entity,
        double distance,
        String weaponName,
        float weaponDamage,
        String armorSummary,
        float totalArmor,
        double targetReach,
        float targetHealth,
        double viabilityScore,
        double myReach,
        float myDamage,
        float theirDamage,
        String potionEffects
    ) {}
}
