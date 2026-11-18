/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Optional self-bootstrap task: gathers logs or stone when stranded with raw food
 * but lacking fuel or a furnace/campfire.
 */
public class BootstrapTask implements BotTask<FoodContext> {
    private BlockPos targetBlock;
    private boolean gatheringStone;
    private int ticks;

    @Override
    public String name() {
        return gatheringStone ? "Bootstrapping stone" : "Bootstrapping wood";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.selfBootstrap || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood() || ctx.evaluator.countRawCookables() <= 0) return 0.0;

        boolean missingFuel = !ctx.evaluator.hasFuelForCooking(true);
        boolean missingFurnace = !InvUtils.find(Items.FURNACE, Items.SMOKER, Items.CAMPFIRE).found()
            && ctx.findNearestBlock(Blocks.FURNACE, ctx.searchRadius) == null
            && ctx.findNearestBlock(Blocks.CAMPFIRE, ctx.searchRadius) == null;

        if (!missingFuel && !missingFurnace) return 0.0;
        if (missingFuel && findNearestLog(ctx) != null) return 18.0;
        if (missingFurnace && ctx.findNearestBlock(Blocks.STONE, 24) != null) return 16.0;
        return 0.0;
    }

    @Override
    public void onStart(FoodContext ctx) {
        ticks = 0;
        boolean missingFuel = !ctx.evaluator.hasFuelForCooking(true);
        if (missingFuel) {
            gatheringStone = false;
            targetBlock = findNearestLog(ctx);
        } else {
            gatheringStone = true;
            targetBlock = ctx.findNearestBlock(Blocks.STONE, 24);
        }
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;
        if (targetBlock == null || ticks > 260) return TaskStatus.FAILED;

        ctx.renderBlockTarget = targetBlock;
        if (PlayerUtils.squaredDistanceTo(targetBlock) > 16.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(targetBlock, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(targetBlock, new GoalNear(targetBlock, 3));
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        if (mc.level.getBlockState(targetBlock).isAir()) return TaskStatus.SUCCEEDED;
        BlockUtils.breakBlock(targetBlock, true);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        targetBlock = null;
        ctx.clearTargets();
    }

    private BlockPos findNearestLog(FoodContext ctx) {
        BlockPos center = mc.player.blockPosition();
        int r = Math.min(ctx.searchRadius, 24);
        BlockPos best = null;
        double bestDistSq = (double) r * r;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 6; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (ctx.navigator.isBlockBlacklisted(cursor)) continue;
                    String path = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(cursor).getBlock()).getPath();
                    if (!path.endsWith("_log")) continue;
                    double dSq = cursor.distSqr(center);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }
}
