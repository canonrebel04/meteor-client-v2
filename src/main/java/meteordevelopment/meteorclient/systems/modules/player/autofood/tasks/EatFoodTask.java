/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.world.InteractionHand;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Eats the best non-hazardous food when hunger or health drops below configured thresholds.
 */
public class EatFoodTask implements BotTask<FoodContext> {
    private int prevSlot = -1;
    private int eatingTicks;

    @Override
    public String name() {
        return "Eating";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.autoEat || mc.player == null) return 0.0;
        if (!mc.player.getFoodData().needsFood()) return 0.0;

        int foodLevel = mc.player.getFoodData().getFoodLevel();
        boolean lowHunger = foodLevel <= ctx.eatThreshold;
        boolean lowHealth = mc.player.getHealth() <= ctx.lowHealthEatThreshold && mc.player.getFoodData().getSaturationLevel() <= 0.1f;
        if (!lowHunger && !lowHealth) return 0.0;

        if (ctx.evaluator.findBestEdibleSlot() == -1) return 0.0;
        return 120.0; // Highest priority when player needs to eat
    }

    @Override
    public void onStart(FoodContext ctx) {
        eatingTicks = 0;
        prevSlot = mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        ctx.navigator.stop();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        eatingTicks++;
        if (mc.player == null || mc.gameMode == null) return TaskStatus.FAILED;
        if (eatingTicks > 60 || !mc.player.getFoodData().needsFood()) {
            return TaskStatus.SUCCEEDED;
        }

        int slot = ctx.evaluator.findBestEdibleSlot();
        if (slot == -1) return TaskStatus.FAILED;

        if (slot >= 9) {
            InvUtils.move().from(slot).toHotbar(8);
            slot = 8;
        }

        if (mc.player.getInventory().getSelectedSlot() != slot) {
            InvUtils.swap(slot, false);
        }

        mc.options.keyUse.setDown(true);
        if (!mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
        ctx.statusDetail = mc.player.getMainHandItem().getHoverName().getString();
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        mc.options.keyUse.setDown(false);
        if (mc.player != null && prevSlot >= 0 && prevSlot < 9) {
            InvUtils.swap(prevSlot, false);
        }
        prevSlot = -1;
        ctx.clearTargets();
    }
}
