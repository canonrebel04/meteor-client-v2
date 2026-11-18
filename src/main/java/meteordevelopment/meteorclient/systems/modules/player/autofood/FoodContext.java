/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood;

import meteordevelopment.meteorclient.utils.bot.BotNavigator;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Shared per-tick snapshot and world/interaction helpers passed to {@code AutoFood} tasks.
 */
public class FoodContext {
    public final BotNavigator navigator;
    public final FoodEvaluator evaluator;
    public final Map<BlockPos, Long> visitedContainers = new HashMap<>();

    public long tickCounter;
    public int currentNutrition;
    public double bestFoodScore;
    public boolean reserveUnmet;
    public boolean wantUpgrade;

    // Settings snapshot
    public int targetFoodReserve;
    public boolean upgradeFood;
    public int searchRadius;
    public int huntRadius;
    public double minUpgradeScore;
    public boolean lootChests;
    public boolean huntAnimals;
    public boolean huntFish;
    public boolean preferFireAspect;
    public boolean forageCrops;
    public boolean autoReplant;
    public boolean craftFood;
    public boolean roamToFind;
    public boolean cookFood;
    public boolean placeFurnace;
    public boolean retrieveFurnace;
    public boolean useWoodFuel;
    public boolean useCampfires;
    public boolean autoEat;
    public int eatThreshold;
    public double lowHealthEatThreshold;
    public boolean autoFish;
    public boolean selfBootstrap;
    public boolean tradeVillagers;
    public boolean claimSafe;
    public int actionDelayTicks;
    public int maxClicksPerTick;
    public int stuckTimeoutTicks;
    public int minAirTicks;
    public int resumeAirTicks;

    // Owned furnace tracking
    public BlockPos placedFurnacePos;

    // Render / HUD state
    public BlockPos renderBlockTarget;
    public Entity renderEntityTarget;
    public String statusDetail = "";

    public FoodContext(BotNavigator navigator, FoodEvaluator evaluator) {
        this.navigator = navigator;
        this.evaluator = evaluator;
    }

    public void refreshInventorySnapshot() {
        currentNutrition = evaluator.getGoodFoodNutritionTotal();
        bestFoodScore = evaluator.getBestFoodScoreInInventory();
        reserveUnmet = currentNutrition < targetFoodReserve;
        wantUpgrade = upgradeFood && bestFoodScore < minUpgradeScore;
    }

    public boolean needsMoreFood() {
        return reserveUnmet || wantUpgrade;
    }

    public boolean isContainerVisited(BlockPos pos) {
        Long until = visitedContainers.get(pos);
        if (until == null) return false;
        if (tickCounter >= until) {
            visitedContainers.remove(pos);
            return false;
        }
        return true;
    }

    public void markContainerVisited(BlockPos pos, long ttlTicks) {
        visitedContainers.put(pos.immutable(), tickCounter + ttlTicks);
    }

    /**
     * Heuristic claim check for SMP Safe mode: skips containers near player-base markers
     * (beds, signs, banners) unless the block was placed by AutoFood itself.
     */
    public boolean isLikelyClaimedContainer(BlockPos pos) {
        if (!claimSafe || mc.level == null) return false;
        if (pos.equals(placedFurnacePos)) return false;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    Block b = mc.level.getBlockState(cursor).getBlock();
                    if (b instanceof BedBlock || b instanceof SignBlock || b instanceof WallSignBlock
                        || b instanceof BannerBlock || b instanceof WallBannerBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isSafeToHarvestCrop(BlockPos pos) {
        if (!claimSafe || mc.level == null) return true;
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock) {
            return autoReplant;
        }
        return true;
    }

    public void interactBlock(BlockPos pos) {
        if (mc.player == null || mc.gameMode == null) return;
        Vec3 hitVec = Vec3.atCenterOf(pos);
        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 50, () -> {
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            PlayerUtils.swingHand(InteractionHand.MAIN_HAND, true);
        });
    }

    public BlockPos findNearestBlock(Block block, int radius) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos center = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistSq = (double) radius * radius;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (mc.level.getBlockState(cursor).is(block) && !navigator.isBlockBlacklisted(cursor)) {
                        double dSq = cursor.distSqr(center);
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        if (mc.level == null) return null;
        return mc.level.getBlockEntity(pos);
    }

    public void clearTargets() {
        renderBlockTarget = null;
        renderEntityTarget = null;
        statusDetail = "";
    }
}
