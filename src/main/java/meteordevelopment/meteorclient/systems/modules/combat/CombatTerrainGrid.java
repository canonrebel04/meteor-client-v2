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

    public static final byte CELL_AIR = 0;
    public static final byte CELL_SOLID = 1;
    public static final byte CELL_WATER = 2;
    public static final byte CELL_LAVA = 3;
    public static final byte CELL_UNKNOWN = 4;
    public static final byte CELL_PLAYER = 5;
    public static final byte CELL_TARGET = 6;

    private int centerX;
    private int centerY;
    private int centerZ;
    private int targetX;
    private int targetY;
    private int targetZ;
    private final int gridSize;
    private final int dim;
    private final int yLevels;
    private final byte[] grid2D;
    private final byte[] grid3D;

    public CombatTerrainGrid() {
        this(16, 5);
    }

    public CombatTerrainGrid(int gridSize) {
        this(gridSize, 5);
    }

    public CombatTerrainGrid(int gridSize, int yLevels) {
        this.gridSize = gridSize;
        this.yLevels = yLevels;
        this.dim = 2 * gridSize + 1;
        this.grid2D = new byte[dim * dim];
        this.grid3D = new byte[dim * yLevels * dim];
    }

    public void update(Entity target) {
        if (mc.player == null || mc.level == null) return;

        centerX = mc.player.blockPosition().getX();
        centerY = mc.player.blockPosition().getY();
        centerZ = mc.player.blockPosition().getZ();

        if (target != null) {
            targetX = target.blockPosition().getX();
            targetY = target.blockPosition().getY();
            targetZ = target.blockPosition().getZ();
        }

        int halfY = yLevels / 2;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int gx = 0; gx < dim; gx++) {
            int wx = centerX - gridSize + gx;
            for (int gz = 0; gz < dim; gz++) {
                int wz = centerZ - gridSize + gz;

                int dxSq = (wx - centerX);
                int dzSq = (wz - centerZ);
                boolean outOfRange = (dxSq * dxSq + dzSq * dzSq) > gridSize * gridSize;

                for (int gy = 0; gy < yLevels; gy++) {
                    int wy = centerY - halfY + gy;
                    int idx3D = gy * dim * dim + gz * dim + gx;
                    if (outOfRange) {
                        grid3D[idx3D] = CELL_UNKNOWN;
                    } else {
                        mutable.set(wx, wy, wz);
                        grid3D[idx3D] = classifyBlock(mutable);
                    }
                }

                int idx2D = gz * dim + gx;
                if (outOfRange) {
                    grid2D[idx2D] = CELL_UNKNOWN;
                } else {
                    mutable.set(wx, centerY, wz);
                    grid2D[idx2D] = classifyBlock(mutable);
                }
            }
        }

        int px = gridSize, pz = gridSize;
        if (target != null) {
            int tx = targetX - centerX + gridSize;
            int tz = targetZ - centerZ + gridSize;
            if (tx >= 0 && tx < dim && tz >= 0 && tz < dim) {
                grid2D[tz * dim + tx] = CELL_TARGET;
                int tgy = net.minecraft.util.Mth.clamp(targetY - centerY + halfY, 0, yLevels - 1);
                grid3D[tgy * dim * dim + tz * dim + tx] = CELL_TARGET;
            }
        }
        grid2D[pz * dim + px] = CELL_PLAYER;
        grid3D[halfY * dim * dim + pz * dim + px] = CELL_PLAYER;
    }

    private byte classifyBlock(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return CELL_AIR;
        if (state.is(Blocks.WATER)) return CELL_WATER;
        if (state.is(Blocks.LAVA)) return CELL_LAVA;
        return CELL_SOLID;
    }

    public String getGridString() {
        char[] charMap = {'.', '#', 'W', 'L', '?', 'P', 'T'};
        StringBuilder sb = new StringBuilder(dim * (dim + 1));
        for (int gz = 0; gz < dim; gz++) {
            for (int gx = 0; gx < dim; gx++) {
                int cell = grid2D[gz * dim + gx] & 0xFF;
                sb.append(cell < charMap.length ? charMap[cell] : '?');
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
    public byte[] getGrid2D() { return grid2D; }
    public byte[] getGrid3D() { return grid3D; }
}