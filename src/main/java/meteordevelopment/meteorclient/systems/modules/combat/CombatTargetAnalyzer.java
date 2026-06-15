/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.util.Mth;
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

        return new TargetAnalysis(
            target,
            distance,
            weaponName,
            weaponDamage,
            armorSummary,
            totalArmor,
            targetReach,
            targetHealth,
            viabilityScore
        );
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
        double viabilityScore
    ) {}
}
