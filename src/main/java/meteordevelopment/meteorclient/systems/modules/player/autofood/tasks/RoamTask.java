/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import baritone.api.pathing.goals.GoalXZ;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Biome- and terrain-aware roaming fallback when no nearby food sources are detected.
 */
public class RoamTask implements BotTask<FoodContext> {
    private BlockPos roamTarget;
    private int angleIndex;
    private int ticks;

    @Override
    public String name() {
        return "Roaming";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.roamToFind || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        return 5.0; // Low-priority fallback when no higher-utility food source is available
    }

    @Override
    public void onStart(FoodContext ctx) {
        ticks = 0;
        roamTarget = pickSafeWaypoint();
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null || roamTarget == null) return TaskStatus.FAILED;

        if (ticks > 280 || ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
            return TaskStatus.FAILED;
        }

        double dx = mc.player.getX() - roamTarget.getX();
        double dz = mc.player.getZ() - roamTarget.getZ();
        if (dx * dx + dz * dz < 64.0) {
            return TaskStatus.SUCCEEDED;
        }

        ctx.renderBlockTarget = roamTarget;
        ctx.statusDetail = "(" + roamTarget.getX() + ", " + roamTarget.getZ() + ")";
        ctx.navigator.pathToBlock(roamTarget, new GoalXZ(roamTarget.getX(), roamTarget.getZ()));
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        ctx.clearTargets();
    }

    private BlockPos pickSafeWaypoint() {
        BlockPos origin = mc.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < 8; i++) {
            int idx = (angleIndex + i) % 8;
            double angle = idx * (Math.PI / 4.0);
            int dist = 56;
            int tx = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
            int tz = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
            int ty = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);

            BlockPos surface = new BlockPos(tx, ty - 1, tz);
            double score = 10.0;
            if (mc.level.getBlockState(surface).is(Blocks.WATER) || mc.level.getBlockState(surface).is(Blocks.LAVA)) {
                score -= 20.0;
            }
            if (Math.abs(ty - origin.getY()) > 24) {
                score -= 5.0;
            }
            if (i == 0) score += 1.0;

            if (score > bestScore) {
                bestScore = score;
                best = new BlockPos(tx, ty, tz);
            }
        }

        angleIndex = (angleIndex + 1) % 8;
        return best;
    }
}
