/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.bot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Reusable Baritone navigation wrapper providing deduplicated goal/follow dispatch,
 * displacement-based stuck detection, and TTL target blacklisting.
 */
public class BotNavigator {
    private static final double MIN_PROGRESS_SQ = 0.25;

    private final Map<BlockPos, Long> blacklistedBlocks = new HashMap<>();
    private final Map<Integer, Long> blacklistedEntities = new HashMap<>();

    private BlockPos lastPathTarget;
    private int lastFollowedId = -1;
    private int pathDispatchCooldown;

    private Vec3 progressAnchor;
    private int ticksWithoutProgress;
    private long tickCounter;

    public void tick() {
        tickCounter++;
        if (pathDispatchCooldown > 0) pathDispatchCooldown--;

        if (mc.player == null) return;
        Vec3 pos = mc.player.position();
        if (progressAnchor == null) {
            progressAnchor = pos;
            ticksWithoutProgress = 0;
            return;
        }

        if (pos.distanceToSqr(progressAnchor) >= MIN_PROGRESS_SQ) {
            progressAnchor = pos;
            ticksWithoutProgress = 0;
        } else if (isPathing()) {
            ticksWithoutProgress++;
        } else {
            ticksWithoutProgress = 0;
        }
    }

    public boolean isStuck(int maxStuckTicks) {
        return ticksWithoutProgress >= maxStuckTicks;
    }

    public void resetProgress() {
        progressAnchor = mc.player != null ? mc.player.position() : null;
        ticksWithoutProgress = 0;
    }

    public boolean isPathing() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone.getPathingBehavior().isPathing();
    }

    public void pathToBlock(BlockPos keyPos, Goal goal) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean pathing = baritone.getPathingBehavior().isPathing() || baritone.getCustomGoalProcess().isActive();
        if (keyPos.equals(lastPathTarget) && pathing) return;
        if (keyPos.equals(lastPathTarget) && pathDispatchCooldown > 0) return;

        lastPathTarget = keyPos.immutable();
        lastFollowedId = -1;
        pathDispatchCooldown = 20;
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    public void followEntity(Entity target) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        int targetId = target.getId();
        boolean following = baritone.getFollowProcess().isActive() || baritone.getPathingBehavior().isPathing();
        if (targetId == lastFollowedId && following) return;
        if (targetId == lastFollowedId && pathDispatchCooldown > 0) return;

        lastFollowedId = targetId;
        lastPathTarget = null;
        pathDispatchCooldown = 20;
        baritone.getFollowProcess().follow(entity -> entity.getId() == targetId);
    }

    public void stop() {
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone != null) {
                baritone.getFollowProcess().cancel();
                baritone.getCustomGoalProcess().onLostControl();
                baritone.getPathingBehavior().cancelEverything();
            }
        } catch (Exception ignored) {}
        lastPathTarget = null;
        lastFollowedId = -1;
        pathDispatchCooldown = 0;
        ticksWithoutProgress = 0;
    }

    public void blacklistBlock(BlockPos pos, long ttlTicks) {
        blacklistedBlocks.put(pos.immutable(), tickCounter + ttlTicks);
    }

    public boolean isBlockBlacklisted(BlockPos pos) {
        Long until = blacklistedBlocks.get(pos);
        if (until == null) return false;
        if (tickCounter >= until) {
            blacklistedBlocks.remove(pos);
            return false;
        }
        return true;
    }

    public void blacklistEntity(int entityId, long ttlTicks) {
        blacklistedEntities.put(entityId, tickCounter + ttlTicks);
    }

    public boolean isEntityBlacklisted(int entityId) {
        Long until = blacklistedEntities.get(entityId);
        if (until == null) return false;
        if (tickCounter >= until) {
            blacklistedEntities.remove(entityId);
            return false;
        }
        return true;
    }

    public void clearAll() {
        blacklistedBlocks.clear();
        blacklistedEntities.clear();
        lastPathTarget = null;
        lastFollowedId = -1;
        pathDispatchCooldown = 0;
        progressAnchor = null;
        ticksWithoutProgress = 0;
    }
}
