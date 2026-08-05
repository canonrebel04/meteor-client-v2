/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

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
    STEALTH;

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
        // 1. Emergency retreat/heal (health <= 4 && totems == 0 && threat > 0.8)
        if (health <= 4.0f && totems == 0 && threat > 0.8) {
            return RETREAT_HEAL;
        }

        // 2. Low health / high threat
        if (health < 10.0f || threat >= fleeThreshold) {
            return RETREAT_HEAL;
        }

        // 3. Multi-target threat: 3+ living enemies within 8 blocks
        if (grid != null && grid.getThreatMap().size() >= 3) {
            return BURROW;
        }

        // 4. Target behind cover + crystal setting enabled
        if (target != null && grid != null && grid.isTargetBehindCover(target) && crystalSetting) {
            return BURROW;
        }

        // 5. Target holds bow/crossbow/trident
        if (target != null && isHoldingRanged(target)) {
            return SNIPE;
        }

        // 6. Target reach > my reach && my weapon is melee
        if (target != null) {
            double targetReach = CombatTargetAnalyzer.getEntityReach(target);
            double myReach = CombatTargetAnalyzer.getEntityReach(net.minecraft.client.Minecraft.getInstance().player != null
                ? net.minecraft.client.Minecraft.getInstance().player
                : target);
            boolean myWeaponMelee = isMyWeaponMelee();
            if (targetReach > myReach && myWeaponMelee) {
                return KITE;
            }
        }

        // 7. Target low health (< 30%) && high viability (>= 0.6)
        if (target != null) {
            float targetMaxHealth = target.getMaxHealth();
            float currentTargetHealth = target.getHealth() + target.getAbsorptionAmount();
            double viability = CombatTargetAnalyzer.calculateViability(target);
            if (targetMaxHealth > 0 && (currentTargetHealth / targetMaxHealth) < 0.3f && viability >= 0.6) {
                return RUSH;
            }
        }

        // 8. Viability < 0.3 (undergeared / unfavorable fight)
        if (target != null) {
            double viability = CombatTargetAnalyzer.calculateViability(target);
            if (viability < 0.3) {
                return DEFENSIVE;
            }
        }

        // 9. Default
        return AGGRESSIVE;
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

    private static boolean isMyWeaponMelee() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return true;
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) return true;
        return !stack.is(Items.BOW) && !stack.is(Items.CROSSBOW) && !stack.is(Items.TRIDENT);
    }
}
