/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalBlock;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodEvaluator;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Collects nearby dropped food items, raw cookables, and craftable food ingredients.
 */
public class CollectDropsTask implements BotTask<FoodContext> {
    private static final double MAX_RANGE_SQ = 16.0 * 16.0;
    private int ticks;

    @Override
    public String name() {
        return "Collecting drops";
    }

    @Override
    public double score(FoodContext ctx) {
        if (mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood() && ctx.evaluator.countEmptySlots() <= 1) return 0.0;
        ItemEntity drop = findNearestDrop(ctx);
        if (drop == null) return 0.0;
        double dist = Math.sqrt(drop.distanceToSqr(mc.player));
        return 95.0 - dist * 1.5;
    }

    @Override
    public void onStart(FoodContext ctx) {
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;

        ItemEntity drop = findNearestDrop(ctx);
        if (drop == null) return TaskStatus.SUCCEEDED;
        if (ticks > 140 || ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
            ctx.navigator.blacklistEntity(drop.getId(), 600);
            return TaskStatus.FAILED;
        }

        ctx.renderEntityTarget = drop;
        ctx.renderBlockTarget = null;
        ctx.statusDetail = drop.getItem().getHoverName().getString();
        ctx.navigator.pathToBlock(drop.blockPosition(), new GoalBlock(drop.blockPosition()));
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        ctx.clearTargets();
    }

    private ItemEntity findNearestDrop(FoodContext ctx) {
        ItemEntity best = null;
        double bestDistSq = MAX_RANGE_SQ;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof ItemEntity itemEntity) || !itemEntity.isAlive()) continue;
            if (ctx.navigator.isEntityBlacklisted(itemEntity.getId())) continue;
            if (!FoodEvaluator.isUsefulFoodDrop(itemEntity.getItem())) continue;
            double dSq = itemEntity.distanceToSqr(mc.player);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = itemEntity;
            }
        }
        return best;
    }
}
