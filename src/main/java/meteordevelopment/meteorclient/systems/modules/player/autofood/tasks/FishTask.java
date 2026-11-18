/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.mixin.FishingHookAccessor;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Fishes for cod, salmon, and treasure when carrying a fishing rod near open water.
 */
public class FishTask implements BotTask<FoodContext> {
    private BlockPos waterTarget;
    private int cooldown;
    private int ticks;

    @Override
    public String name() {
        return "Fishing";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.autoFish || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        if (!InvUtils.find(Items.FISHING_ROD).found()) return 0.0;
        if (findOpenWater(ctx) == null) return 0.0;
        return 38.0; // Fallback below direct crops/chests/hunting, above blind roaming
    }

    @Override
    public void onStart(FoodContext ctx) {
        waterTarget = findOpenWater(ctx);
        cooldown = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return TaskStatus.FAILED;
        if (ticks > 900) return TaskStatus.SUCCEEDED;

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (waterTarget == null || !mc.level.getBlockState(waterTarget).is(Blocks.WATER)) {
            waterTarget = findOpenWater(ctx);
            if (waterTarget == null) return TaskStatus.FAILED;
        }

        ctx.renderBlockTarget = waterTarget;
        if (PlayerUtils.squaredDistanceTo(waterTarget) > 36.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(waterTarget, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(waterTarget, new GoalNear(waterTarget, 4));
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        FindItemResult rod = InvUtils.findInHotbar(Items.FISHING_ROD);
        if (!rod.found()) {
            FindItemResult invRod = InvUtils.find(Items.FISHING_ROD);
            if (!invRod.found()) return TaskStatus.FAILED;
            InvUtils.move().from(invRod.slot()).toHotbar(8);
            rod = InvUtils.findInHotbar(Items.FISHING_ROD);
            if (!rod.found()) return TaskStatus.FAILED;
        }
        InvUtils.swap(rod.slot(), false);

        if (mc.player.fishing == null) {
            Vec3 aim = Vec3.atCenterOf(waterTarget);
            Rotations.rotate(Rotations.getYaw(aim), Rotations.getPitch(aim), 55, () -> {
                if (mc.gameMode != null && mc.player != null) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }
            });
            cooldown = Math.max(15, ctx.actionDelayTicks + 10);
            return TaskStatus.RUNNING;
        }

        if (((FishingHookAccessor) mc.player.fishing).meteor$hasCaughtFish()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            cooldown = Math.max(10, ctx.actionDelayTicks + 6);
        }

        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        if (mc.player != null && mc.gameMode != null && mc.player.fishing != null) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
        ctx.clearTargets();
    }

    private BlockPos findOpenWater(FoodContext ctx) {
        BlockPos center = mc.player.blockPosition();
        int r = Math.min(ctx.searchRadius, 32);
        BlockPos best = null;
        double bestDistSq = (double) r * r;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx += 2) {
            for (int dy = -4; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz += 2) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (ctx.navigator.isBlockBlacklisted(cursor)) continue;
                    if (!mc.level.getBlockState(cursor).is(Blocks.WATER)) continue;
                    if (!mc.level.getBlockState(cursor.above()).isAir()) continue;
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
