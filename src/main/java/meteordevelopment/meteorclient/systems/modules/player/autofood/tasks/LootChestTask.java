/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodEvaluator;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Loots food, raw ingredients, and fuel from unclaimed chests, barrels, and shulkers.
 */
public class LootChestTask implements BotTask<FoodContext> {
    private BlockPos targetChest;
    private boolean openedContainer;
    private int cooldown;
    private int ticks;

    @Override
    public String name() {
        return "Looting container";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.lootChests || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        BlockPos chest = findNextContainer(ctx);
        if (chest == null) return 0.0;
        double dist = Math.sqrt(PlayerUtils.squaredDistanceTo(chest));
        return Math.max(10.0, 68.0 - dist * 0.6);
    }

    @Override
    public void onStart(FoodContext ctx) {
        targetChest = findNextContainer(ctx);
        openedContainer = false;
        cooldown = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null) return TaskStatus.FAILED;
        if (targetChest == null) return TaskStatus.FAILED;
        if (ticks > 260) {
            ctx.navigator.blacklistBlock(targetChest, 600);
            return TaskStatus.FAILED;
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu) {
            openedContainer = true;
            int containerSlots = menu instanceof ChestMenu cm ? cm.getRowCount() * 9 : 27;
            int clicks = 0;
            for (int i = 0; i < containerSlots; i++) {
                ItemStack stack = menu.getSlot(i).getItem();
                if (stack.isEmpty() || !shouldLootItem(stack)) continue;
                InvUtils.shiftClick().slotId(i);
                clicks++;
                if (clicks >= Math.max(1, ctx.maxClicksPerTick)) {
                    cooldown = Math.max(1, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                }
            }

            ctx.markContainerVisited(targetChest, 1800);
            mc.player.closeContainer();
            return TaskStatus.SUCCEEDED;
        }

        if (openedContainer) {
            ctx.markContainerVisited(targetChest, 1800);
            return TaskStatus.SUCCEEDED;
        }

        ctx.renderBlockTarget = targetChest;
        if (PlayerUtils.squaredDistanceTo(targetChest) > 16.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistBlock(targetChest, 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.pathToBlock(targetChest, new GoalNear(targetChest, 3));
        } else {
            ctx.navigator.stop();
            ctx.interactBlock(targetChest);
            cooldown = Math.max(6, ctx.actionDelayTicks + 4);
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        if (mc.player != null && !(mc.player.containerMenu instanceof InventoryMenu)) {
            mc.player.closeContainer();
        }
        ctx.clearTargets();
    }

    private boolean shouldLootItem(ItemStack stack) {
        Item item = stack.getItem();
        if (FoodEvaluator.HAZARDOUS_FOODS.contains(item)) return false;
        if (FoodEvaluator.isEdibleFood(stack) && FoodEvaluator.scoreFood(stack) >= 6.0) return true;
        if (FoodEvaluator.RAW_COOKABLES.contains(item)) return true;
        if (FoodEvaluator.CRAFTABLE_INPUTS.contains(item)) return true;
        return FoodEvaluator.FUEL_ITEMS.contains(item);
    }

    private BlockPos findNextContainer(FoodContext ctx) {
        BlockPos best = null;
        double bestDistSq = (double) ctx.searchRadius * ctx.searchRadius;
        for (BlockEntity be : Utils.blockEntities()) {
            if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity)) {
                continue;
            }
            BlockPos pos = be.getBlockPos();
            if (ctx.isContainerVisited(pos) || ctx.navigator.isBlockBlacklisted(pos)) continue;
            if (ctx.isLikelyClaimedContainer(pos)) continue;
            double dSq = PlayerUtils.squaredDistanceTo(pos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = pos;
            }
        }
        return best;
    }
}
