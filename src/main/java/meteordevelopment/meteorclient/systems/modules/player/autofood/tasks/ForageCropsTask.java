/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Forages mature crops, sweet/glow berries, pumpkins, melons, mushrooms, and village haybales.
 */
public class ForageCropsTask implements BotTask<FoodContext> {
    private BlockPos targetCrop;
    private BlockPos pendingReplantPos;
    private Item pendingReplantSeed;
    private int replantDelay;
    private int ticks;

    @Override
    public String name() {
        return "Foraging crops";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.forageCrops || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        BlockPos crop = findBestCrop(ctx);
        if (crop == null) return 0.0;
        double dist = Math.sqrt(PlayerUtils.squaredDistanceTo(crop));
        double bonus = mc.level.getBlockState(crop).is(Blocks.HAY_BLOCK) ? 8.0 : 0.0;
        return Math.max(10.0, 56.0 + bonus - dist * 0.65);
    }

    @Override
    public void onStart(FoodContext ctx) {
        targetCrop = findBestCrop(ctx);
        pendingReplantPos = null;
        pendingReplantSeed = null;
        replantDelay = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;

        if (replantDelay > 0) {
            replantDelay--;
            if (replantDelay == 0) {
                tryReplant();
                return TaskStatus.SUCCEEDED;
            }
            return TaskStatus.RUNNING;
        }

        if (targetCrop == null || ticks > 220) {
            if (targetCrop != null) ctx.navigator.blacklistBlock(targetCrop, 600);
            return TaskStatus.FAILED;
        }

        if (!isHarvestableCrop(ctx, targetCrop)) {
            return TaskStatus.SUCCEEDED;
        }

        ctx.renderBlockTarget = targetCrop;
        if (PlayerUtils.squaredDistanceTo(targetCrop) > 16.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(targetCrop, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(targetCrop, new GoalNear(targetCrop, 3));
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        BlockState stateAt = mc.level.getBlockState(targetCrop);
        Block block = stateAt.getBlock();
        if (block instanceof SweetBerryBushBlock || stateAt.is(Blocks.CAVE_VINES) || stateAt.is(Blocks.CAVE_VINES_PLANT)) {
            ctx.interactBlock(targetCrop);
            return TaskStatus.SUCCEEDED;
        }

        if (ctx.autoReplant) {
            Item seed = getReplantSeed(block);
            if (seed != null) {
                pendingReplantPos = targetCrop.immutable();
                pendingReplantSeed = seed;
                replantDelay = 2;
            }
        }

        BlockUtils.breakBlock(targetCrop, true);
        return replantDelay > 0 ? TaskStatus.RUNNING : TaskStatus.SUCCEEDED;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        pendingReplantPos = null;
        pendingReplantSeed = null;
        ctx.clearTargets();
    }

    private void tryReplant() {
        BlockPos pos = pendingReplantPos;
        Item seed = pendingReplantSeed;
        pendingReplantPos = null;
        pendingReplantSeed = null;
        if (pos == null || seed == null || mc.level == null) return;
        if (!mc.level.getBlockState(pos).isAir()) return;
        if (!mc.level.getBlockState(pos.below()).is(Blocks.FARMLAND)) return;

        FindItemResult seedResult = InvUtils.findInHotbar(seed);
        if (seedResult.found()) {
            BlockUtils.place(pos, seedResult, true, 65, true, false, true);
        }
    }

    private Item getReplantSeed(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }

    private BlockPos findBestCrop(FoodContext ctx) {
        BlockPos center = mc.player.blockPosition();
        int r = Math.min(ctx.searchRadius, 32);
        BlockPos best = null;
        double bestDistSq = (double) r * r;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (ctx.navigator.isBlockBlacklisted(cursor)) continue;
                    if (!isHarvestableCrop(ctx, cursor)) continue;
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

    private boolean isHarvestableCrop(FoodContext ctx, BlockPos pos) {
        if (!ctx.isSafeToHarvestCrop(pos)) return false;
        BlockState stateAt = mc.level.getBlockState(pos);
        Block block = stateAt.getBlock();
        if (block instanceof CropBlock crop) return crop.isMaxAge(stateAt);
        if (block instanceof SweetBerryBushBlock) return stateAt.getValue(SweetBerryBushBlock.AGE) >= 2;
        if (stateAt.is(Blocks.CAVE_VINES) || stateAt.is(Blocks.CAVE_VINES_PLANT)) return CaveVines.hasGlowBerries(stateAt);
        return block == Blocks.HAY_BLOCK || block == Blocks.MELON || block == Blocks.PUMPKIN
            || block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM;
    }
}
