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
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Cooks raw food on campfires or soul campfires (no fuel required, up to 4 items at once).
 */
public class CampfireCookTask implements BotTask<FoodContext> {
    private BlockPos campfireTarget;
    private int cooldown;
    private int ticks;

    @Override
    public String name() {
        return "Campfire cooking";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.cookFood || !ctx.useCampfires || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        if (ctx.evaluator.countRawCookables() <= 0) return 0.0;

        BlockPos existing = findCampfire(ctx);
        boolean canPlace = InvUtils.findInHotbar(Items.CAMPFIRE, Items.SOUL_CAMPFIRE).found();
        if (existing == null && !canPlace) return 0.0;
        return 78.0; // Slightly preferred over furnace since campfires require zero fuel
    }

    @Override
    public void onStart(FoodContext ctx) {
        campfireTarget = findCampfire(ctx);
        cooldown = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;
        if (ticks > 700) return TaskStatus.FAILED;

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (campfireTarget == null || !isCampfire(campfireTarget)) {
            campfireTarget = findCampfire(ctx);
            if (campfireTarget == null) {
                campfireTarget = tryPlaceCampfire();
                if (campfireTarget == null) return TaskStatus.FAILED;
                cooldown = Math.max(2, ctx.actionDelayTicks);
                return TaskStatus.RUNNING;
            }
        }

        ctx.renderBlockTarget = campfireTarget;
        if (PlayerUtils.squaredDistanceTo(campfireTarget) > 16.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(campfireTarget, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(campfireTarget, new GoalNear(campfireTarget, 3));
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        FindItemResult raw = ctx.evaluator.findBestRawCookable();
        if (!raw.found()) {
            // Wait briefly for placed items to pop off as cooked drops
            BlockEntity be = ctx.getBlockEntity(campfireTarget);
            if (be instanceof CampfireBlockEntity cbe) {
                boolean anyCooking = false;
                for (int i = 0; i < cbe.getItems().size(); i++) {
                    if (!cbe.getItems().get(i).isEmpty()) {
                        anyCooking = true;
                        break;
                    }
                }
                if (anyCooking) return TaskStatus.RUNNING;
            }
            return TaskStatus.SUCCEEDED;
        }

        int slot = raw.slot();
        if (slot >= 9) {
            InvUtils.move().from(slot).toHotbar(8);
            slot = 8;
        }
        InvUtils.swap(slot, false);
        ctx.interactBlock(campfireTarget);
        cooldown = Math.max(3, ctx.actionDelayTicks + 1);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        ctx.clearTargets();
    }

    private BlockPos findCampfire(FoodContext ctx) {
        BlockPos normal = ctx.findNearestBlock(Blocks.CAMPFIRE, ctx.searchRadius);
        if (normal != null) return normal;
        return ctx.findNearestBlock(Blocks.SOUL_CAMPFIRE, ctx.searchRadius);
    }

    private BlockPos tryPlaceCampfire() {
        FindItemResult item = InvUtils.findInHotbar(Items.CAMPFIRE, Items.SOUL_CAMPFIRE);
        if (!item.found()) return null;

        BlockPos feet = mc.player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = feet.relative(dir);
            if (BlockUtils.canPlaceBlock(candidate, true, Blocks.CAMPFIRE) && BlockUtils.getPlaceSide(candidate) != null) {
                if (BlockUtils.place(candidate, item, true, 75, true, true, true)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean isCampfire(BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.CAMPFIRE) || mc.level.getBlockState(pos).is(Blocks.SOUL_CAMPFIRE);
    }
}
