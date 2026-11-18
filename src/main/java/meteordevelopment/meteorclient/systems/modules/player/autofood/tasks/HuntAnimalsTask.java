/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Hunts livestock, hoglins, and optional fish mobs with weapon selection,
 * stuck-target blacklisting, and underwater oxygen monitoring.
 */
public class HuntAnimalsTask implements BotTask<FoodContext> {
    private LivingEntity target;
    private boolean surfacingForAir;
    private int ticks;

    @Override
    public String name() {
        return surfacingForAir ? "Surfacing for air" : "Hunting animals";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.huntAnimals || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;

        LivingEntity best = findBestAnimal(ctx);
        if (best == null) return 0.0;
        double yield = getAnimalYieldScore(ctx, best.getType());
        double dist = Math.sqrt(best.distanceToSqr(mc.player));
        return Math.max(12.0, 50.0 + yield * 2.0 - dist * 0.7);
    }

    @Override
    public void onStart(FoodContext ctx) {
        target = findBestAnimal(ctx);
        surfacingForAir = false;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;

        // Oxygen guard for underwater fish hunting
        int air = mc.player.getAirSupply();
        if (mc.player.isUnderWater() && air <= ctx.minAirTicks) {
            surfacingForAir = true;
        }
        if (surfacingForAir) {
            ctx.navigator.stop();
            mc.options.keyJump.setDown(true);
            ctx.statusDetail = "Air " + air + "/" + mc.player.getMaxAirSupply();
            if (!mc.player.isUnderWater() && air >= ctx.resumeAirTicks) {
                mc.options.keyJump.setDown(false);
                surfacingForAir = false;
                ctx.navigator.resetProgress();
            }
            return TaskStatus.RUNNING;
        }

        if (target == null || !target.isAlive() || ticks > 320) {
            if (target != null && target.isAlive()) {
                ctx.navigator.blacklistEntity(target.getId(), 600);
            }
            return target != null && !target.isAlive() ? TaskStatus.SUCCEEDED : TaskStatus.FAILED;
        }

        if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
            ctx.navigator.blacklistEntity(target.getId(), 600);
            return TaskStatus.FAILED;
        }

        ctx.renderEntityTarget = target;
        ctx.renderBlockTarget = null;
        ctx.statusDetail = target.getType().getDescription().getString();

        double distSq = mc.player.distanceToSqr(target);
        if (distSq > 11.5) {
            ctx.navigator.followEntity(target);
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        equipBestHuntingWeapon(ctx);

        if (mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
            LivingEntity attackTarget = target;
            Rotations.rotate(Rotations.getYaw(attackTarget), Rotations.getPitch(attackTarget), 60, () -> {
                if (mc.gameMode != null && mc.player != null && attackTarget.isAlive()) {
                    mc.gameMode.attack(mc.player, attackTarget);
                    PlayerUtils.punch(true);
                }
            });
        }

        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        mc.options.keyJump.setDown(false);
        ctx.navigator.stop();
        surfacingForAir = false;
        target = null;
        ctx.clearTargets();
    }

    private LivingEntity findBestAnimal(FoodContext ctx) {
        LivingEntity best = null;
        double bestScore = -1.0;
        double maxDistSq = (double) ctx.huntRadius * ctx.huntRadius;

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof LivingEntity le) || !le.isAlive() || le.isBaby()) continue;
            if (ctx.navigator.isEntityBlacklisted(le.getId())) continue;
            double yield = getAnimalYieldScore(ctx, le.getType());
            if (yield <= 0.0) continue;
            double dSq = le.distanceToSqr(mc.player);
            if (dSq > maxDistSq) continue;
            double score = yield / (1.0 + Math.sqrt(dSq) * 0.15);
            if (score > bestScore) {
                bestScore = score;
                best = le;
            }
        }
        return best;
    }

    private double getAnimalYieldScore(FoodContext ctx, EntityType<?> type) {
        if (type == EntityTypes.COW || type == EntityTypes.MOOSHROOM || type == EntityTypes.HOGLIN) return 10.0;
        if (type == EntityTypes.PIG) return 9.0;
        if (type == EntityTypes.SHEEP) return 7.5;
        if (type == EntityTypes.CHICKEN || type == EntityTypes.RABBIT) return 5.5;
        if (ctx.huntFish && (type == EntityTypes.SALMON || type == EntityTypes.COD || type == EntityTypes.TROPICAL_FISH)) {
            return 4.5;
        }
        return 0.0;
    }

    private void equipBestHuntingWeapon(FoodContext ctx) {
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            double score = 0.0;
            if (id.contains("netherite_sword")) score = 10;
            else if (id.contains("diamond_sword")) score = 9;
            else if (id.contains("iron_sword")) score = 7;
            else if (id.contains("stone_sword")) score = 5;
            else if (id.contains("netherite_axe") || id.contains("diamond_axe")) score = 8;
            else if (id.contains("iron_axe")) score = 6;
            if (score <= 0) continue;

            if (ctx.preferFireAspect && Utils.getEnchantmentLevel(stack, Enchantments.FIRE_ASPECT) > 0) {
                score += 20.0;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) InvUtils.swap(bestSlot, false);
    }
}
