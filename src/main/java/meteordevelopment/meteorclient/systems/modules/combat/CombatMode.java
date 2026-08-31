/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public enum CombatMode {
    AGGRESSIVE,
    DEFENSIVE,
    KITE,
    RUSH,
    BURROW,
    RETREAT_HEAL,
    SNIPE,
    STEALTH,
    RANGED_KITE;

    /**
     * Evaluates tactical combat mode in priority order based on spec section 3 table.
     */
    public static CombatMode evaluateCombatMode(
        double threat,
        double engageThreshold,
        double fleeThreshold,
        float health,
        int totems,
        LivingEntity target,
        CombatTerrainGrid grid,
        boolean hasCrystal,
        boolean hasAnchor,
        boolean crystalSetting
    ) {
        double viability = (target != null) ? CombatTargetAnalyzer.calculateViability(target) : 0.5;
        int nearbyEnemies = (grid != null) ? grid.getThreatMap().size() : 0;
        boolean targetVisible = (target != null && grid != null) ? grid.isTargetVisible(target) : true;
        double targetReach = (target != null) ? CombatTargetAnalyzer.getEntityReach(target) : 2.0;
        double myReach = CombatTargetAnalyzer.getEntityReach(
            net.minecraft.client.Minecraft.getInstance().player != null
                ? net.minecraft.client.Minecraft.getInstance().player : target);
        float targetHpRatio = (target != null && target.getMaxHealth() > 0)
            ? (target.getHealth() + target.getAbsorptionAmount()) / target.getMaxHealth() : 1.0f;

        double[] scores = new double[CombatMode.values().length];

        double vulnerability = 1.0 - Math.min(1.0, health / 20.0);
        scores[RETREAT_HEAL.ordinal()] = 1.0 / (1.0 + Math.exp(-5.0 * (vulnerability * threat - 0.35)));
        if (health <= 4.0f && totems == 0 && threat > 0.8) scores[RETREAT_HEAL.ordinal()] = 1.0;

        scores[BURROW.ordinal()] = Math.tanh(nearbyEnemies / 3.0) * 0.75;
        if (target != null && !targetVisible && crystalSetting) {
            scores[BURROW.ordinal()] = Math.max(scores[BURROW.ordinal()], 0.65);
        }

        scores[RUSH.ordinal()] = Math.max(0.0, (1.0 - targetHpRatio) * viability);

        // Ranged Kite evaluation: flying, elevated, or high-reach enemies.
        // Requires line-of-sight -- never pull out the bow for a target behind cover.
        if (target != null && hasRangedWeapon() && targetVisible) {
            boolean isAirborneOrFlying = isAirborneTarget(target);
            var mc = net.minecraft.client.Minecraft.getInstance();
            boolean isElevated = mc.player != null && (target.getY() - mc.player.getY() > 2.5);
            if (isAirborneOrFlying || isElevated) {
                scores[RANGED_KITE.ordinal()] = 0.85;
            }
        }

        double reachDeficit = targetReach - myReach;
        scores[KITE.ordinal()] = (reachDeficit > 0 && isMyWeaponMelee())
            ? Math.tanh(reachDeficit) * 0.7 : 0.0;

        scores[SNIPE.ordinal()] = (target != null && isHoldingRanged(target)) ? 0.7 : 0.0;

        scores[DEFENSIVE.ordinal()] = Math.max(0.0, 0.6 - viability);

        scores[STEALTH.ordinal()] = (target != null && !targetVisible && nearbyEnemies == 0)
            ? 0.6 : 0.0;

        scores[AGGRESSIVE.ordinal()] = 0.3 + viability * 0.3;

        int bestIdx = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[bestIdx]) bestIdx = i;
        }
        return CombatMode.values()[bestIdx];
    }

    public static double modePadding(CombatMode mode) {
        if (mode == null) return 0.5;
        return switch (mode) {
            case AGGRESSIVE -> 0.5;
            case DEFENSIVE -> 1.5;
            case KITE -> 2.0;
            case RUSH -> 0.5;
            case BURROW -> 1.0;
            case RETREAT_HEAL -> 2.0;
            case SNIPE -> 2.0;
            case STEALTH -> 1.5;
            case RANGED_KITE -> 6.0;
        };
    }

    public static CombatMode holdMode(CombatMode current, CombatMode proposed, int currentTimer, int hysteresisTicks) {
        if (current == null) return proposed;
        if (proposed == RETREAT_HEAL) return RETREAT_HEAL; // Emergency path bypasses hysteresis
        if (current == proposed) return current;
        if (currentTimer >= hysteresisTicks) return proposed;
        return current;
    }

    private static boolean isHoldingRanged(LivingEntity target) {
        ItemStack main = target.getMainHandItem();
        ItemStack off = target.getOffhandItem();
        return main.is(Items.BOW) || main.is(Items.CROSSBOW) || main.is(Items.TRIDENT)
            || off.is(Items.BOW) || off.is(Items.CROSSBOW) || off.is(Items.TRIDENT);
    }

    private static boolean hasRangedWeapon() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return false;
        return meteordevelopment.meteorclient.utils.player.InvUtils.find(Items.BOW, Items.CROSSBOW, Items.TRIDENT).found();
    }

    private static boolean isAirborneTarget(LivingEntity target) {
        EntityType<?> type = target.getType();
        return type == EntityTypes.GHAST
            || type == EntityTypes.BLAZE
            || type == EntityTypes.BREEZE
            || type == EntityTypes.PHANTOM
            || type == EntityTypes.ENDER_DRAGON
            || type == EntityTypes.WITHER
            || type == EntityTypes.BAT
            || type == EntityTypes.VEX;
    }

    private static boolean isMyWeaponMelee() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return true;
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) return true;
        return !stack.is(Items.BOW) && !stack.is(Items.CROSSBOW) && !stack.is(Items.TRIDENT);
    }
}
