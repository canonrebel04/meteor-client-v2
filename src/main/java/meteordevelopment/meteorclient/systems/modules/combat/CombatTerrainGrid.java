/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatTerrainGrid {
    private int centerX;
    private int centerZ;
    private int targetX;
    private int targetZ;
    private final int gridSize;
    private final Character[][] grid;

    public CombatTerrainGrid() {
        this(16);
    }

    public CombatTerrainGrid(int gridSize) {
        this.gridSize = gridSize;
        int dim = 2 * gridSize + 1;
        this.grid = new Character[dim][dim];
    }

    public void update(Entity target) {
        if (mc.player == null || mc.level == null) return;

        centerX = mc.player.blockPosition().getX();
        centerZ = mc.player.blockPosition().getZ();

        if (target != null) {
            targetX = target.blockPosition().getX();
            targetZ = target.blockPosition().getZ();
        }

        int dim = 2 * gridSize + 1;
        int playerY = mc.player.blockPosition().getY();

        int gridSizeSq = gridSize * gridSize;

        for (int gx = 0; gx < dim; gx++) {
            for (int gz = 0; gz < dim; gz++) {
                int wx = centerX - gridSize + gx;
                int wz = centerZ - gridSize + gz;

                int dx = wx - centerX;
                int dz = wz - centerZ;
                if (dx * dx + dz * dz > gridSizeSq) {
                    grid[gx][gz] = '?';
                    continue;
                }

                BlockPos pos = new BlockPos(wx, playerY, wz);
                BlockState state = mc.level.getBlockState(pos);

                if (state.is(Blocks.WATER)) {
                    grid[gx][gz] = 'W';
                } else if (state.is(Blocks.LAVA)) {
                    grid[gx][gz] = 'L';
                } else if (state.isAir()) {
                    grid[gx][gz] = '.';
                } else {
                    grid[gx][gz] = '#';
                }
            }
        }

        // Place player and target markers
        int px = gridSize;
        int pz = gridSize;
        if (target != null) {
            int tx = (targetX - centerX + gridSize);
            int tz = (targetZ - centerZ + gridSize);
            if (tx >= 0 && tx < dim && tz >= 0 && tz < dim) {
                grid[tx][tz] = 'T';
            }
        }
        if (px >= 0 && px < dim && pz >= 0 && pz < dim) {
            grid[px][pz] = 'P';
        }
    }

    public String getGridString() {
        int dim = 2 * gridSize + 1;
        StringBuilder sb = new StringBuilder();

        for (int gz = 0; gz < dim; gz++) {
            for (int gx = 0; gx < dim; gx++) {
                char c = grid[gx][gz] != null ? grid[gx][gz] : '?';
                sb.append(c);
            }
            if (gz < dim - 1) sb.append('\n');
        }

        return sb.toString();
    }

    public List<BlockPos> getPathBlocks() {
        List<BlockPos> blockers = new ArrayList<>();
        if (mc.player == null || mc.level == null) return blockers;

        Vec3 start = mc.player.getEyePosition();
        Vec3 end = new Vec3(targetX + 0.5, mc.player.getEyePosition().y, targetZ + 0.5);

        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return blockers;

        // Normalize direction
        dx /= dist;
        dz /= dist;

        int steps = (int) Math.ceil(dist);
        if (steps <= 0) steps = 1;

        double stepSize = dist / steps;

        int playerY = mc.player.blockPosition().getY();

        for (int i = 1; i < steps; i++) {
            double px = start.x + dx * stepSize * i;
            double pz = start.z + dz * stepSize * i;

            BlockPos checkPos = new BlockPos((int) Math.floor(px), playerY, (int) Math.floor(pz));
            BlockState state = mc.level.getBlockState(checkPos);

            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                // Avoid duplicates
                if (!blockers.contains(checkPos)) {
                    blockers.add(checkPos);
                }
            }
        }

        // Also check at eye level and one above (walls)
        for (int dy = -1; dy <= 1; dy++) {
            for (int i = 0; i < steps; i++) {
                double px = start.x + dx * stepSize * i;
                double pz = start.z + dz * stepSize * i;
                BlockPos checkPos = new BlockPos((int) Math.floor(px), playerY + dy, (int) Math.floor(pz));
                BlockState state = mc.level.getBlockState(checkPos);
                if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                    if (!blockers.contains(checkPos)) {
                        blockers.add(checkPos);
                    }
                }
            }
        }

        return blockers;
    }

    // --- Getters ---

    public int getCenterX() { return centerX; }
    public int getCenterZ() { return centerZ; }
    public int getTargetX() { return targetX; }
    public int getTargetZ() { return targetZ; }
    public int getGridSize() { return gridSize; }
    public Character[][] getGrid() { return grid; }
}