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
        var mc = net.minecraft.client.Minecraft.getInstance();
        double viability = (target != null) ? CombatTargetAnalyzer.calculateViability(target) : 0.5;
        int nearbyEnemies = (grid != null) ? grid.countHostiles(BURROW_RADIUS) : 0;
        boolean targetVisible = (target != null && grid != null) ? grid.isTargetVisible(target) : true;
        double targetReach = (target != null) ? CombatTargetAnalyzer.getEntityReach(target) : 2.0;
        double myReach = CombatTargetAnalyzer.getEntityReach(mc.player != null ? mc.player : target);
        float targetHpRatio = (target != null && target.getMaxHealth() > 0)
            ? (target.getHealth() + target.getAbsorptionAmount()) / target.getMaxHealth() : 1.0f;

        boolean rangedKite = false;
        if (target != null && targetVisible && hasRangedWeapon()) {
            boolean isElevated = mc.player != null && (target.getY() - mc.player.getY() > 2.5);
            rangedKite = isAirborneTarget(target) || isElevated;
        }

        boolean inOpen = grid == null || grid.isPlayerInOpen();

        return CombatModeScorer.best(new CombatModeScorer.Inputs(
            threat, health, totems, target != null, viability, nearbyEnemies, targetVisible,
            targetReach, myReach, targetHpRatio, isMyWeaponMelee(),
            target != null && isHoldingRanged(target), rangedKite,
            crystalSetting, hasCrystal || hasAnchor, inOpen
        ));
    }

    /** Radius (blocks) used for the "crowded" BURROW trigger. */
    public static final double BURROW_RADIUS = 8.0;

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
