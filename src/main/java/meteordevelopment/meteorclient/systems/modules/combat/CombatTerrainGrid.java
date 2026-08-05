/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.mixin.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatTerrainGrid {
    public record ThreatEntry(Entity entity, double distance, boolean visible) {}

    private int centerX;
    private int centerZ;
    private int targetX;
    private int targetY;
    private int targetZ;
    private final int gridSize;
    private final Character[][] grid;
    private final Character[][][] grid3D;

    public CombatTerrainGrid() {
        this(16);
    }

    public CombatTerrainGrid(int gridSize) {
        this.gridSize = gridSize;
        int dim = 2 * gridSize + 1;
        this.grid = new Character[dim][dim];
        this.grid3D = new Character[dim][5][dim];
    }

    public void update(Entity target) {
        if (mc.player == null || mc.level == null) return;

        centerX = mc.player.blockPosition().getX();
        centerZ = mc.player.blockPosition().getZ();

        if (target != null) {
            targetX = target.blockPosition().getX();
            targetY = target.blockPosition().getY();
            targetZ = target.blockPosition().getZ();
        }

        int dim = 2 * gridSize + 1;
        int playerY = mc.player.blockPosition().getY();

        for (int gx = 0; gx < dim; gx++) {
            for (int gz = 0; gz < dim; gz++) {
                int wx = centerX - gridSize + gx;
                int wz = centerZ - gridSize + gz;

                double dist = Math.sqrt(Math.pow(wx - centerX, 2) + Math.pow(wz - centerZ, 2));

                // Populate 3D grid across 5 Y levels: gy 0..4 = playerY-2..playerY+2
                for (int gy = 0; gy < 5; gy++) {
                    int wy = playerY - 2 + gy;
                    if (dist > gridSize) {
                        grid3D[gx][gy][gz] = '?';
                    } else {
                        BlockPos pos = new BlockPos(wx, wy, wz);
                        BlockState state = mc.level.getBlockState(pos);
                        if (state.is(Blocks.WATER)) {
                            grid3D[gx][gy][gz] = 'W';
                        } else if (state.is(Blocks.LAVA)) {
                            grid3D[gx][gy][gz] = 'L';
                        } else if (state.isAir()) {
                            grid3D[gx][gy][gz] = '.';
                        } else {
                            grid3D[gx][gy][gz] = '#';
                        }
                    }
                }

                // Populate 2D grid for player-Y layer
                if (dist > gridSize) {
                    grid[gx][gz] = '?';
                } else {
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
        }

        // Place player and target markers at gy=2 in grid3D and in 2D grid
        int px = gridSize;
        int pz = gridSize;
        if (target != null) {
            int tx = (targetX - centerX + gridSize);
            int tz = (targetZ - centerZ + gridSize);
            if (tx >= 0 && tx < dim && tz >= 0 && tz < dim) {
                grid[tx][tz] = 'T';
                grid3D[tx][2][tz] = 'T';
            }
        }
        if (px >= 0 && px < dim && pz >= 0 && pz < dim) {
            grid[px][pz] = 'P';
            grid3D[px][2][pz] = 'P';
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

    private List<BlockPos> raycastDDA(Vec3 start, Vec3 end) {
        List<BlockPos> result = new ArrayList<>();

        int x = (int) Math.floor(start.x);
        int y = (int) Math.floor(start.y);
        int z = (int) Math.floor(start.z);

        int endX = (int) Math.floor(end.x);
        int endY = (int) Math.floor(end.y);
        int endZ = (int) Math.floor(end.z);

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tMaxX, tMaxY, tMaxZ;
        double tDeltaX, tDeltaY, tDeltaZ;

        if (dx > 0) {
            tMaxX = ((x + 1) - start.x) / dx;
            tDeltaX = 1.0 / dx;
        } else if (dx < 0) {
            tMaxX = (start.x - x) / (-dx);
            tDeltaX = 1.0 / (-dx);
        } else {
            tMaxX = Double.POSITIVE_INFINITY;
            tDeltaX = Double.POSITIVE_INFINITY;
        }

        if (dy > 0) {
            tMaxY = ((y + 1) - start.y) / dy;
            tDeltaY = 1.0 / dy;
        } else if (dy < 0) {
            tMaxY = (start.y - y) / (-dy);
            tDeltaY = 1.0 / (-dy);
        } else {
            tMaxY = Double.POSITIVE_INFINITY;
            tDeltaY = Double.POSITIVE_INFINITY;
        }

        if (dz > 0) {
            tMaxZ = ((z + 1) - start.z) / dz;
            tDeltaZ = 1.0 / dz;
        } else if (dz < 0) {
            tMaxZ = (start.z - z) / (-dz);
            tDeltaZ = 1.0 / (-dz);
        } else {
            tMaxZ = Double.POSITIVE_INFINITY;
            tDeltaZ = Double.POSITIVE_INFINITY;
        }

        int maxSteps = 200;
        int steps = 0;

        while (steps++ < maxSteps) {
            result.add(new BlockPos(x, y, z));

            if (x == endX && y == endY && z == endZ) {
                break;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }

        return result;
    }

    public List<BlockPos> getPathBlocks() {
        List<BlockPos> blockers = new ArrayList<>();
        if (mc.player == null || mc.level == null) return blockers;

        Vec3 start = mc.player.getEyePosition();
        Vec3 end = new Vec3(targetX + 0.5, targetY + 1.0, targetZ + 0.5);

        List<BlockPos> voxels = raycastDDA(start, end);
        Set<Long> seen = new HashSet<>();

        for (BlockPos pos : voxels) {
            BlockState state = mc.level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                if (seen.add(pos.asLong())) {
                    blockers.add(pos);
                }
            }
        }

        return blockers;
    }

    public boolean isTargetVisible(Entity target) {
        if (mc.player == null || mc.level == null || target == null) return false;

        Vec3 start = mc.player.getEyePosition();
        Vec3 end = new Vec3(target.blockPosition().getX() + 0.5, target.blockPosition().getY() + 1.0, target.blockPosition().getZ() + 0.5);

        List<BlockPos> voxels = raycastDDA(start, end);

        for (BlockPos pos : voxels) {
            BlockState state = mc.level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.WATER) && !state.is(Blocks.LAVA)) {
                return false;
            }
        }

        return true;
    }

    public boolean isTargetBehindCover(Entity target) {
        return !isTargetVisible(target);
    }

    public List<ThreatEntry> getThreatMap() {
        List<ThreatEntry> threats = new ArrayList<>();
        if (mc.player == null || mc.level == null) return threats;

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                double distance = mc.player.distanceTo(le);
                if (distance <= 2 * gridSize) {
                    boolean visible = isTargetVisible(le);
                    threats.add(new ThreatEntry(le, distance, visible));
                }
            }
        }

        return threats;
    }

    // --- Getters ---

    public int getCenterX() { return centerX; }
    public int getCenterZ() { return centerZ; }
    public int getTargetX() { return targetX; }
    public int getTargetY() { return targetY; }
    public int getTargetZ() { return targetZ; }
    public int getGridSize() { return gridSize; }
    public Character[][] getGrid() { return grid; }
    public Character[][][] getGrid3D() { return grid3D; }
}