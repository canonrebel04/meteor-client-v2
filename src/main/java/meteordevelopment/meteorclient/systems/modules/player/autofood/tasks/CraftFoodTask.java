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
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Crafts bread (from wheat or unpacked haybales), golden carrots, and mushroom stew.
 */
public class CraftFoodTask implements BotTask<FoodContext> {
    private int cooldown;
    private int craftStep;
    private int ticks;
    private BlockPos tableTarget;

    @Override
    public String name() {
        return "Crafting food";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.craftFood || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        if (!canCraftAnything(ctx)) return 0.0;
        return 82.0;
    }

    @Override
    public void onStart(FoodContext ctx) {
        cooldown = 0;
        craftStep = 0;
        ticks = 0;
        tableTarget = null;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return TaskStatus.FAILED;
        if (ticks > 260) return TaskStatus.FAILED;
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        // 2x2 Inventory crafting: unpack haybale -> 9 wheat, or 3 wheat -> bread
        if (mc.player.containerMenu instanceof InventoryMenu menu) {
            FindItemResult hay = InvUtils.find(Items.HAY_BLOCK);
            if (hay.found()) {
                if (craftStep == 0) {
                    moveSingleToGrid(menu, hay.slot(), 1);
                    craftStep = 1;
                    cooldown = Math.max(1, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                } else {
                    if (!menu.getSlot(0).getItem().isEmpty()) {
                        InvUtils.shiftClick().slotId(0);
                    }
                    craftStep = 0;
                    cooldown = Math.max(2, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                }
            }

            FindItemResult wheat = InvUtils.find(Items.WHEAT);
            if (wheat.found() && wheat.count() >= 3) {
                if (craftStep < 3) {
                    moveSingleToGrid(menu, wheat.slot(), craftStep + 1);
                    craftStep++;
                    cooldown = Math.max(1, ctx.actionDelayTicks);
                    return TaskStatus.RUNNING;
                } else {
                    if (!menu.getSlot(0).getItem().isEmpty()) {
                        InvUtils.shiftClick().slotId(0);
                    }
                    craftStep = 0;
                    cooldown = Math.max(2, ctx.actionDelayTicks);
                    return canCraftAnything(ctx) ? TaskStatus.RUNNING : TaskStatus.SUCCEEDED;
                }
            }
        }

        // 3x3 Crafting table recipes: Golden Carrots & Mushroom Stew
        if (canCraftGoldenCarrot() || canCraftMushroomStew()) {
            if (mc.player.containerMenu instanceof CraftingMenu craftingMenu) {
                ctx.navigator.stop();
                if (canCraftGoldenCarrot()) {
                    performGoldenCarrotCraft(craftingMenu);
                } else {
                    performMushroomStewCraft(craftingMenu);
                }
                cooldown = Math.max(2, ctx.actionDelayTicks);
                if (!canCraftGoldenCarrot() && !canCraftMushroomStew()) {
                    mc.player.closeContainer();
                    return TaskStatus.SUCCEEDED;
                }
                return TaskStatus.RUNNING;
            }

            tableTarget = ctx.findNearestBlock(Blocks.CRAFTING_TABLE, 16);
            if (tableTarget == null) return TaskStatus.SUCCEEDED;

            ctx.renderBlockTarget = tableTarget;
            if (PlayerUtils.squaredDistanceTo(tableTarget) > 16.0) {
                if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                    ctx.navigator.blacklistBlock(tableTarget, 600);
                    return TaskStatus.FAILED;
                }
                ctx.navigator.pathToBlock(tableTarget, new GoalNear(tableTarget, 3));
            } else {
                ctx.navigator.stop();
                ctx.interactBlock(tableTarget);
                cooldown = Math.max(4, ctx.actionDelayTicks + 2);
            }
            return TaskStatus.RUNNING;
        }

        return TaskStatus.SUCCEEDED;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        if (mc.player != null && mc.player.containerMenu instanceof CraftingMenu) {
            mc.player.closeContainer();
        }
        ctx.clearTargets();
    }

    private boolean canCraftAnything(FoodContext ctx) {
        if (InvUtils.find(Items.HAY_BLOCK).found()) return true;
        if (InvUtils.find(Items.WHEAT).count() >= 3) return true;
        if (!canCraftGoldenCarrot() && !canCraftMushroomStew()) return false;
        return ctx.findNearestBlock(Blocks.CRAFTING_TABLE, 16) != null;
    }

    private boolean canCraftGoldenCarrot() {
        return InvUtils.find(Items.CARROT).found() && InvUtils.find(Items.GOLD_NUGGET).count() >= 8;
    }

    private boolean canCraftMushroomStew() {
        return InvUtils.find(Items.RED_MUSHROOM).found()
            && InvUtils.find(Items.BROWN_MUSHROOM).found()
            && InvUtils.find(Items.BOWL).found();
    }

    private void moveSingleToGrid(AbstractContainerMenu menu, int invSlot, int targetSlotId) {
        int fromId = SlotUtils.indexToId(invSlot);
        if (fromId < 0) return;
        InvUtils.click().slotId(fromId);
        mc.gameMode.handleContainerInput(menu.containerId, targetSlotId, 1, ContainerInput.PICKUP, mc.player);
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            InvUtils.click().slotId(fromId);
        }
    }

    private void performGoldenCarrotCraft(CraftingMenu menu) {
        if (craftStep == 0) {
            FindItemResult carrot = InvUtils.find(Items.CARROT);
            FindItemResult nuggets = InvUtils.find(Items.GOLD_NUGGET);
            if (!carrot.found() || nuggets.count() < 8) return;
            moveSingleToGrid(menu, carrot.slot(), 5);
            int[] nuggetSlots = {1, 2, 3, 4, 6, 7, 8, 9};
            for (int targetSlot : nuggetSlots) {
                FindItemResult nug = InvUtils.find(Items.GOLD_NUGGET);
                if (!nug.found()) break;
                moveSingleToGrid(menu, nug.slot(), targetSlot);
            }
            craftStep = 1;
        } else {
            if (!menu.getSlot(0).getItem().isEmpty()) {
                InvUtils.shiftClick().slotId(0);
            }
            craftStep = 0;
        }
    }

    private void performMushroomStewCraft(CraftingMenu menu) {
        if (craftStep == 0) {
            FindItemResult red = InvUtils.find(Items.RED_MUSHROOM);
            FindItemResult brown = InvUtils.find(Items.BROWN_MUSHROOM);
            FindItemResult bowl = InvUtils.find(Items.BOWL);
            if (!red.found() || !brown.found() || !bowl.found()) return;
            moveSingleToGrid(menu, red.slot(), 1);
            moveSingleToGrid(menu, brown.slot(), 2);
            moveSingleToGrid(menu, bowl.slot(), 4);
            craftStep = 1;
        } else {
            if (!menu.getSlot(0).getItem().isEmpty()) {
                InvUtils.shiftClick().slotId(0);
            }
            craftStep = 0;
        }
    }
}
