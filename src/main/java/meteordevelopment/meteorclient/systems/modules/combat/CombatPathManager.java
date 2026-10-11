/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.debug.DebugLog;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.LongToDoubleFunction;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatPathManager {
    private final IBaritone baritone;
    private final CombatProcess process;
    private final LongToDoubleFunction favoringFn = this::lookupThreatMultiplier;

    private boolean active = false;
    private Goal activeGoal = null;
    private PathingCommandType activeCommandType = null;
    private boolean combatModeActive = false;

    private LivingEntity activeTarget = null;
    private BlockPos activeHole = null;
    private volatile Long2DoubleOpenHashMap activeThreatMap = null;

    public CombatPathManager() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.process = new CombatProcess();
        this.baritone.getPathingControlManager().registerProcess(process);
    }

    public void startRetreat(BlockPos hole, LivingEntity enemy) {
        if (hole == null) return;
        boolean goalChanged = !this.active || this.activeHole == null || !this.activeHole.equals(hole);
        enableCombatMode(true);
        if (goalChanged && this.combatModeActive) {
            baritone.getPathingBehavior().cancelEverything();
        }
        this.activeTarget = enemy;
        this.activeHole = hole;
        this.activeGoal = new GoalGetToBlock(hole);
        this.activeCommandType = PathingCommandType.SET_GOAL_AND_PATH;
        this.active = true;
        enableFavoring();
    }

    public void startFlee(LivingEntity enemy, double distance) {
        startFlee(enemy, distance, null);
    }

    public void startFlee(LivingEntity enemy, double distance, CombatGroupAwareness.GroupSnapshot groupSnap) {
        if (mc.player == null) return;
        this.activeTarget = enemy;

        double dx;
        double dz;
        if (groupSnap != null && groupSnap.targets().size() >= 2 && groupSnap.encirclementDeg() > 0.0) {
            // CombatGroupAwareness uses compass bearing (0 deg = -Z North, 90 deg = +X East)
            double bearingRad = Math.toRadians(groupSnap.largestGapBearing());
            dx = Math.sin(bearingRad) * distance;
            dz = -Math.cos(bearingRad) * distance;
        } else if (enemy != null) {
            dx = mc.player.getX() - enemy.getX();
            dz = mc.player.getZ() - enemy.getZ();
            double len = Math.hypot(dx, dz);
            if (len > 1e-4) {
                dx = (dx / len) * distance;
                dz = (dz / len) * distance;
            } else {
                dx = distance;
                dz = 0.0;
            }
        } else {
            double yawRad = Math.toRadians(mc.player.getYRot() + 180.0);
            dx = -Math.sin(yawRad) * distance;
            dz = Math.cos(yawRad) * distance;
        }

        BlockPos fleeTarget = mc.player.blockPosition().offset((int) Math.round(dx), 0, (int) Math.round(dz));
        boolean needNewGoal = !this.active || this.activeHole != null || !(this.activeGoal instanceof GoalXZ);
        if (!needNewGoal) {
            GoalXZ existing = (GoalXZ) this.activeGoal;
            double remX = existing.getX() - mc.player.getX();
            double remZ = existing.getZ() - mc.player.getZ();
            double remDistSq = remX * remX + remZ * remZ;
            double newLen = Math.hypot(dx, dz);
            double remLen = Math.sqrt(remDistSq);
            double dot = (remLen > 1e-4 && newLen > 1e-4)
                ? ((remX / remLen) * (dx / newLen) + (remZ / remLen) * (dz / newLen))
                : 0.0;
            if (remDistSq <= 16.0 || dot < 0.7) {
                needNewGoal = true;
            }
        }

        enableCombatMode(true);
        if (needNewGoal) {
            if (this.active && this.combatModeActive) {
                baritone.getPathingBehavior().cancelEverything();
            }
            this.activeHole = null;
            this.activeGoal = new GoalXZ(fleeTarget.getX(), fleeTarget.getZ());
            this.activeCommandType = PathingCommandType.SET_GOAL_AND_PATH;
        }
        this.active = true;
        enableFavoring();
    }

    public void releaseControl() {
        boolean wasActive = this.active;
        this.active = false;
        this.activeGoal = null;
        this.activeCommandType = null;
        this.activeTarget = null;
        this.activeHole = null;
        if (wasActive) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    public void pausePathing() {
        this.activeTarget = null;
        this.activeHole = null;
        this.activeGoal = null;
        this.activeCommandType = PathingCommandType.REQUEST_PAUSE;
        this.active = true;
        disableFavoring();
        enableCombatMode(false);
    }

    public void stop() {
        this.active = false;
        this.activeGoal = null;
        this.activeCommandType = null;
        this.activeTarget = null;
        this.activeHole = null;
        disableFavoring();
        enableCombatMode(false);
        baritone.getPathingBehavior().cancelEverything();
    }

    public boolean isActive() {
        return active;
    }

    public boolean isCombatModeActive() {
        return combatModeActive;
    }

    public void enableCombatMode(boolean enabled) {
        boolean wasActive = this.combatModeActive;
        this.combatModeActive = enabled;
        try {
            BaritoneAPI.getSettings().combatMode.value = enabled;
        } catch (Throwable t) {
            // Throwable: an incompatible Baritone jar throws NoSuchFieldError/NoSuchMethodError, not Exception
            DebugLog.warn("CombatPath", "Could not set Baritone combatMode={} (Baritone missing or incompatible jar?)", enabled, t);
        }
        // Fresh goal command: cancel stale pathing only on transition to combat mode
        // so repeated per-tick flee/retreat updates do not reset A* search mid-stride.
        if (enabled && !wasActive) {
            baritone.getPathingBehavior().cancelEverything();
        }
    }

    public void updateThreatFavoring(LivingEntity primaryEnemy, List<LivingEntity> threats) {
        if (mc.player == null || mc.level == null) {
            disableFavoring();
            return;
        }

        Long2DoubleOpenHashMap nextMap = new Long2DoubleOpenHashMap(512);
        nextMap.defaultReturnValue(1.0D);

        int count = 0;
        if (threats != null) {
            for (LivingEntity threat : threats) {
                if (threat == null || threat == mc.player || threat.isDeadOrDying()) continue;
                applyThreatPenalty(nextMap, threat);
                if (++count >= 5) break;
            }
        }
        if (primaryEnemy != null && primaryEnemy != mc.player && !primaryEnemy.isDeadOrDying()) {
            if (threats == null || !threats.contains(primaryEnemy)) {
                applyThreatPenalty(nextMap, primaryEnemy);
            }
            applyCoverFavor(nextMap, primaryEnemy);
        }

        this.activeThreatMap = nextMap;
        enableFavoring();
    }

    private void applyThreatPenalty(Long2DoubleOpenHashMap map, LivingEntity enemy) {
        if (mc.player == null) return;
        if (enemy.distanceToSqr(mc.player) > 22.0 * 22.0) return;

        Vec3 look = enemy.getLookAngle();
        double lx = look.x;
        double lz = look.z;
        double lLen = Math.hypot(lx, lz);
        if (lLen > 1e-4) {
            lx /= lLen;
            lz /= lLen;
        }

        BlockPos eBlock = enemy.blockPosition();
        int playerY = mc.player.blockPosition().getY();
        int minY = Math.min(eBlock.getY(), playerY) - 1;
        int maxY = Math.max(eBlock.getY(), playerY) + 2;

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double dSq = dx * dx + dz * dz;
                if (dSq > 36.0) continue;
                double dist = Math.sqrt(dSq);

                double coneMult = 1.0;
                if (dist > 0.25 && lLen > 1e-4) {
                    double dot = (dx / dist) * lx + (dz / dist) * lz;
                    if (dot > 0.45) {
                        double coneStrength = (dot - 0.45) / 0.55;
                        double distFactor = Math.max(0.2, 1.0 - dist / 6.5);
                        coneMult = 1.0 + 1.45 * coneStrength * distFactor;
                    }
                }

                double reachMult = 1.0;
                if (dist <= 4.0) {
                    reachMult = 1.0 + 0.75 * (1.0 - dist / 4.2);
                }

                double mult = Math.min(3.0, Math.max(coneMult, 1.0) * (reachMult > 1.0 ? 1.0 + (reachMult - 1.0) * 0.65 : 1.0));
                if (mult <= 1.02) continue;

                int bx = eBlock.getX() + dx;
                int bz = eBlock.getZ() + dz;
                for (int by = minY; by <= maxY; by++) {
                    long hash = BetterBlockPos.longHash(bx, by, bz);
                    double prev = map.get(hash);
                    if (mult > prev) {
                        map.put(hash, mult);
                    }
                }
            }
        }
    }

    private void applyCoverFavor(Long2DoubleOpenHashMap map, LivingEntity enemy) {
        if (mc.player == null || mc.level == null) return;
        BlockPos pBlock = mc.player.blockPosition();
        double ex = enemy.getX();
        double ez = enemy.getZ();

        BlockPos.MutableBlockPos shieldFoot = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos shieldHead = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                int bx = pBlock.getX() + dx;
                int bz = pBlock.getZ() + dz;
                double toEnemyX = ex - (bx + 0.5);
                double toEnemyZ = ez - (bz + 0.5);
                double len = Math.hypot(toEnemyX, toEnemyZ);
                if (len < 1.5) continue;

                int stepX = (int) Math.round(toEnemyX / len);
                int stepZ = (int) Math.round(toEnemyZ / len);
                if (stepX == 0 && stepZ == 0) continue;

                for (int dy = -1; dy <= 1; dy++) {
                    int by = pBlock.getY() + dy;
                    floor.set(bx, by - 1, bz);
                    if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) continue;

                    shieldFoot.set(bx + stepX, by, bz + stepZ);
                    shieldHead.set(bx + stepX, by + 1, bz + stepZ);
                    if (!mc.level.getBlockState(shieldFoot).getCollisionShape(mc.level, shieldFoot).isEmpty()
                        && !mc.level.getBlockState(shieldHead).getCollisionShape(mc.level, shieldHead).isEmpty()) {
                        long hash = BetterBlockPos.longHash(bx, by, bz);
                        double prev = map.get(hash);
                        if (prev <= 1.05) {
                            map.put(hash, Math.max(0.65, Math.min(prev, 0.78)));
                        }
                    }
                }
            }
        }
    }

    private double lookupThreatMultiplier(long hash) {
        Long2DoubleOpenHashMap map = this.activeThreatMap;
        return map == null ? 1.0D : map.get(hash);
    }

    private void enableFavoring() {
        BaritoneAPI.setCombatFavoringSupplier(this.favoringFn);
    }

    private void disableFavoring() {
        this.activeThreatMap = null;
        if (BaritoneAPI.getCombatFavoringSupplier() == this.favoringFn) {
            BaritoneAPI.setCombatFavoringSupplier(null);
        }
    }

    private class CombatProcess implements IBaritoneProcess {
        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public PathingCommand onTick(boolean b, boolean b1) {
            if (activeCommandType == PathingCommandType.REQUEST_PAUSE) {
                baritone.getInputOverrideHandler().clearAllKeys();
            }
            return new PathingCommand(activeGoal, activeCommandType);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
            active = false;
            activeGoal = null;
            activeCommandType = null;
            activeTarget = null;
            activeHole = null;
        }

        @Override
        public double priority() {
            return 100.0;
        }

        @Override
        public String displayName0() {
            return "Combat Pathing";
        }
    }
}
