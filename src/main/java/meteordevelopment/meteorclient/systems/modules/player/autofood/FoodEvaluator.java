/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Food scoring, inventory reserve accounting, fuel/cookable lookup, and hotbar management.
 */
public final class FoodEvaluator {
    public static final Set<Item> RAW_COOKABLES = Set.of(
        Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN,
        Items.RABBIT, Items.COD, Items.SALMON, Items.POTATO, Items.KELP
    );

    public static final Set<Item> HAZARDOUS_FOODS = Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
        Items.PUFFERFISH, Items.CHORUS_FRUIT
    );

    public static final Set<Item> CRAFTABLE_INPUTS = Set.of(
        Items.WHEAT, Items.HAY_BLOCK, Items.GOLD_NUGGET, Items.RED_MUSHROOM,
        Items.BROWN_MUSHROOM, Items.BOWL, Items.SUGAR, Items.EGG
    );

    public static final Set<Item> FUEL_ITEMS = Set.of(
        Items.COAL, Items.CHARCOAL, Items.BLAZE_ROD, Items.DRIED_KELP_BLOCK,
        Items.COAL_BLOCK, Items.LAVA_BUCKET, Items.STICK
    );

    public static double scoreFood(ItemStack stack) {
        if (stack.isEmpty()) return 0.0;
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return 0.0;
        Item item = stack.getItem();
        if (item == Items.ENCHANTED_GOLDEN_APPLE || item == Items.GOLDEN_APPLE) return 5.0; // Save gaps for combat
        if (HAZARDOUS_FOODS.contains(item)) return -10.0;
        return food.nutrition() + food.saturation() * 1.25;
    }

    public static boolean isEdibleFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    public static boolean isUsefulFoodDrop(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (HAZARDOUS_FOODS.contains(item)) return false;
        return isEdibleFood(stack) || RAW_COOKABLES.contains(item) || CRAFTABLE_INPUTS.contains(item);
    }

    public int getGoodFoodNutritionTotal() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || HAZARDOUS_FOODS.contains(stack.getItem())) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food != null) {
                total += food.nutrition() * stack.getCount();
            }
        }
        return total;
    }

    public double getBestFoodScoreInInventory() {
        if (mc.player == null) return 0.0;
        double best = 0.0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            double s = scoreFood(stack);
            if (s > best) best = s;
        }
        return best;
    }

    public int findBestEdibleSlot() {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        double bestScore = 0.0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || HAZARDOUS_FOODS.contains(stack.getItem())) continue;
            double s = scoreFood(stack);
            if (s > bestScore) {
                bestScore = s;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public int countRawCookables() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && RAW_COOKABLES.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public FindItemResult findBestRawCookable() {
        return InvUtils.find(stack -> !stack.isEmpty() && RAW_COOKABLES.contains(stack.getItem()));
    }

    public boolean hasFuelForCooking(boolean allowWoodFuel) {
        return findBestFuel(allowWoodFuel).found();
    }

    public FindItemResult findBestFuel(boolean allowWoodFuel) {
        FindItemResult coal = InvUtils.find(stack -> FUEL_ITEMS.contains(stack.getItem()));
        if (coal.found() || !allowWoodFuel) return coal;
        return InvUtils.find(stack -> {
            if (stack.isEmpty()) return false;
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            return path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_wood");
        });
    }

    public void maintainBestHotbarFood(int hotbarSlotOneBased) {
        if (mc.player == null) return;
        int targetHotbarIdx = Math.max(0, Math.min(8, hotbarSlotOneBased - 1));
        ItemStack current = mc.player.getInventory().getItem(targetHotbarIdx);
        double currentScore = scoreFood(current);

        int bestSlot = -1;
        double bestScore = currentScore;
        for (int i = 0; i < 36; i++) {
            if (i == targetHotbarIdx) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            double s = scoreFood(stack);
            if (s > bestScore + 0.5) {
                bestScore = s;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) {
            InvUtils.move().from(bestSlot).toHotbar(targetHotbarIdx);
        }
    }

    public void pruneJunkFoodIfFull() {
        if (mc.player == null) return;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        if (empty > 2 || getGoodFoodNutritionTotal() < 16) return;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && HAZARDOUS_FOODS.contains(stack.getItem())) {
                InvUtils.drop().slot(i);
                return;
            }
        }
    }

    public int countEmptySlots() {
        if (mc.player == null) return 0;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) empty++;
        }
        return empty;
    }
}
