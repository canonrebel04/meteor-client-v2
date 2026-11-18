/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Cooks raw food in a nearby or placed furnace/smoker with paced slot transfers.
 */
public class CookAtFurnaceTask implements BotTask<FoodContext> {
    private BlockPos furnaceTarget;
    private boolean miningPlacedFurnace;
    private int cooldown;
    private int ticks;

    @Override
    public String name() {
        return "Cooking at furnace";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.cookFood || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        int rawCount = ctx.evaluator.countRawCookables();
        if (rawCount <= 0 || !ctx.evaluator.hasFuelForCooking(ctx.useWoodFuel)) return 0.0;
        if (findNearbyFurnace(ctx) == null && (!ctx.placeFurnace || !hasFurnaceInHotbar())) return 0.0;
        return 76.0 + Math.min(12.0, rawCount);
    }

    @Override
    public void onStart(FoodContext ctx) {
        furnaceTarget = null;
        miningPlacedFurnace = false;
        cooldown = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;
        if (ticks > 900) return TaskStatus.FAILED;

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (miningPlacedFurnace && ctx.placedFurnacePos != null) {
            if (isFurnaceBlock(ctx.placedFurnacePos)) {
                BlockUtils.breakBlock(ctx.placedFurnacePos, true);
                return TaskStatus.RUNNING;
            }
            miningPlacedFurnace = false;
            ctx.placedFurnacePos = null;
            return TaskStatus.SUCCEEDED;
        }

        if (mc.player.containerMenu instanceof AbstractFurnaceMenu menu) {
            ctx.navigator.stop();
            return handleOpenFurnace(ctx, menu);
        }

        if (furnaceTarget == null || !isFurnaceBlock(furnaceTarget)) {
            furnaceTarget = findNearbyFurnace(ctx);
            if (furnaceTarget == null && ctx.placeFurnace) {
                furnaceTarget = tryPlaceFurnace();
                if (furnaceTarget != null) {
                    ctx.placedFurnacePos = furnaceTarget.immutable();
                    cooldown = Math.max(2, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                }
            }
            if (furnaceTarget == null) return TaskStatus.FAILED;
        }

        ctx.renderBlockTarget = furnaceTarget;
        if (PlayerUtils.squaredDistanceTo(furnaceTarget) > 16.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(furnaceTarget, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(furnaceTarget, new GoalNear(furnaceTarget, 3));
        } else {
            ctx.navigator.stop();
            ctx.interactBlock(furnaceTarget);
            cooldown = Math.max(6, ctx.actionDelayTicks + 4);
        }

        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        if (mc.player != null && mc.player.containerMenu instanceof AbstractFurnaceMenu) {
            mc.player.closeContainer();
        }
        miningPlacedFurnace = false;
        ctx.clearTargets();
    }

    private TaskStatus handleOpenFurnace(FoodContext ctx, AbstractFurnaceMenu menu) {
        ItemStack outStack = menu.getSlot(2).getItem();
        if (!outStack.isEmpty()) {
            InvUtils.shiftClick().slotId(2);
            cooldown = Math.max(2, ctx.actionDelayTicks);
            return TaskStatus.RUNNING;
        }

        ItemStack inStack = menu.getSlot(0).getItem();
        if (inStack.isEmpty()) {
            FindItemResult raw = ctx.evaluator.findBestRawCookable();
            if (raw.found()) {
                int id = SlotUtils.indexToId(raw.slot());
                if (id >= 0) {
                    InvUtils.move().fromId(id).toId(0);
                    cooldown = Math.max(2, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                }
            }
        }

        ItemStack fuelStack = menu.getSlot(1).getItem();
        if (fuelStack.isEmpty() && !inStack.isEmpty()) {
            FindItemResult fuel = ctx.evaluator.findBestFuel(ctx.useWoodFuel);
            if (fuel.found()) {
                int id = SlotUtils.indexToId(fuel.slot());
                if (id >= 0) {
                    InvUtils.move().fromId(id).toId(1);
                    cooldown = Math.max(2, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                }
            }
        }

        boolean nothingLeftToCook = inStack.isEmpty() && !ctx.evaluator.findBestRawCookable().found();
        boolean noFuelForPending = !inStack.isEmpty() && fuelStack.isEmpty() && !ctx.evaluator.findBestFuel(ctx.useWoodFuel).found();
        if (nothingLeftToCook || noFuelForPending) {
            mc.player.closeContainer();
            if (ctx.retrieveFurnace && ctx.placedFurnacePos != null && ctx.placedFurnacePos.equals(furnaceTarget)) {
                miningPlacedFurnace = true;
                cooldown = Math.max(2, ctx.actionDelayTicks);
                return TaskStatus.RUNNING;
            }
            return TaskStatus.SUCCEEDED;
        }

        return TaskStatus.RUNNING;
    }

    private boolean hasFurnaceInHotbar() {
        return InvUtils.findInHotbar(Items.SMOKER, Items.FURNACE).found();
    }

    private BlockPos tryPlaceFurnace() {
        FindItemResult furnaceItem = InvUtils.findInHotbar(Items.SMOKER, Items.FURNACE);
        if (!furnaceItem.found()) return null;

        BlockPos feet = mc.player.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = feet.relative(dir);
            if (BlockUtils.canPlaceBlock(candidate, true, Blocks.FURNACE) && BlockUtils.getPlaceSide(candidate) != null) {
                if (BlockUtils.place(candidate, furnaceItem, true, 75, true, true, true)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private BlockPos findNearbyFurnace(FoodContext ctx) {
        BlockPos best = null;
        double bestDistSq = (double) ctx.searchRadius * ctx.searchRadius;
        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof AbstractFurnaceBlockEntity)) continue;
            BlockPos pos = be.getBlockPos();
            if (ctx.navigator.isBlockBlacklisted(pos)) continue;
            double dSq = PlayerUtils.squaredDistanceTo(pos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = pos;
            }
        }
        return best;
    }

    private boolean isFurnaceBlock(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return s.is(Blocks.FURNACE) || s.is(Blocks.SMOKER) || s.is(Blocks.BLAST_FURNACE);
    }
}
