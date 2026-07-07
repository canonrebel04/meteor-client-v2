/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TerrainProfile {
    private final List<BlockPos> safeHoles = new ArrayList<>();

    public TerrainProfile(int radius) {
        if (mc.player == null || mc.level == null) return;
        BlockPos playerPos = mc.player.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (isSafeHole(pos)) {
                        safeHoles.add(pos);
                    }
                }
            }
        }
    }

    public List<BlockPos> getSafeHoles() {
        return safeHoles;
    }

    public BlockPos getNearestSafeHole() {
        if (safeHoles.isEmpty()) return null;
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        for (BlockPos hole : safeHoles) {
            double dist = mc.player.distanceToSqr(hole.getX() + 0.5, hole.getY(), hole.getZ() + 0.5);
            if (dist < minDist) {
                minDist = dist;
                nearest = hole;
            }
        }
        return nearest;
    }

    private boolean isSafeHole(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir()) return false;
        
        BlockState stateAbove = mc.level.getBlockState(pos.above());
        if (!stateAbove.isAir()) return false;

        // Check North, South, East, West, and Down for blast-resistant blocks
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;
            BlockState neighbor = mc.level.getBlockState(pos.relative(dir));
            if (neighbor.is(Blocks.BEDROCK) || neighbor.is(Blocks.OBSIDIAN) || neighbor.is(Blocks.RESPAWN_ANCHOR)) {
                // Blast resistant wall
            } else {
                return false;
            }
        }
        return true;
    }
}
