package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatFollowController {

    private final IBaritone baritone;
    private final IFollowProcess followProcess;
    private final ICustomGoalProcess goalProcess;

    private Entity currentTarget;
    private FollowMode mode;
    private UUID followUuid;
    private double followDistance;

    private static final int MIN_RESTART_INTERVAL_TICKS = 10;
    private int ticksSinceLastRestart = 0;
    private boolean directPursuitActive = false;
    private boolean maintainingDistance = false;

    public enum FollowMode {
        FOLLOW,
        FLEE,
        NONE
    }

    public CombatFollowController() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.followProcess = baritone.getFollowProcess();
        this.goalProcess = baritone.getCustomGoalProcess();
        this.mode = FollowMode.NONE;
        this.followDistance = -1.0;
    }

    /**
     * Checks if there is a direct, walkable line of sight to the target
     * with no impassable walls, pits, or obstacles in between.
     */
    private boolean isDirectWalkable(Entity target) {
        if (mc.player == null || mc.level == null || target == null) return false;
        double dx = target.getX() - mc.player.getX();
        double dy = target.getY() - mc.player.getY();
        double dz = target.getZ() - mc.player.getZ();
        double distSq = dx * dx + dz * dz;

        // If height difference is too steep (> 1.8 blocks), require Baritone parkour/pillar/stair pathing
        if (Math.abs(dy) > 1.8) return false;

        // Direct pursuit range up to 24 blocks (beyond this, use Baritone macro navigation)
        if (distSq > 24.0 * 24.0) return false;

        double dist = Math.sqrt(distSq);
        if (dist < 0.5) return true;

        int steps = Math.max(1, (int) Math.ceil(dist / 0.6));
        double stepX = dx / steps;
        double stepZ = dz / steps;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int playerY = mc.player.getBlockY();

        for (int i = 1; i <= steps; i++) {
            double checkX = mc.player.getX() + stepX * i;
            double checkZ = mc.player.getZ() + stepZ * i;
            int bx = (int) Math.floor(checkX);
            int bz = (int) Math.floor(checkZ);

            // Check floor below (playerY - 1)
            pos.set(bx, playerY - 1, bz);
            var floorState = mc.level.getBlockState(pos);
            boolean hasFloor = !floorState.isAir() && !floorState.liquid();

            // Check 1 block lower for step down
            if (!hasFloor) {
                pos.set(bx, playerY - 2, bz);
                var lowerFloor = mc.level.getBlockState(pos);
                hasFloor = !lowerFloor.isAir() && !lowerFloor.liquid();
            }

            if (!hasFloor) return false; // Pit / void / lava hole! Use Baritone A*

            // Check headroom at feet (playerY) and head (playerY + 1)
            pos.set(bx, playerY, bz);
            var feetState = mc.level.getBlockState(pos);
            if (feetState.blocksMotion() && feetState.isCollisionShapeFullBlock(mc.level, pos)) {
                // Check if 1-block step up has clear headroom
                pos.set(bx, playerY + 1, bz);
                var stepUpHead = mc.level.getBlockState(pos);
                if (stepUpHead.blocksMotion()) return false; // Wall! Use Baritone A*
            }

            pos.set(bx, playerY + 1, bz);
            var headState = mc.level.getBlockState(pos);
            if (headState.blocksMotion() && headState.isCollisionShapeFullBlock(mc.level, pos)) {
                return false; // Solid wall/obstacle! Use Baritone A*
            }
        }

        return true;
    }

    /**
     * Called every tick.
     * Executes zero-latency Euclidean straight-line intercept pursuit when line-of-sight is clear,
     * or falls back to Baritone A* when navigating around complex obstacles/walls.
     */
    public void tick() {
        if (maintainingDistance) return; // maintainDistance() drives keys directly

        if (ticksSinceLastRestart < MIN_RESTART_INTERVAL_TICKS) {
            ticksSinceLastRestart++;
        }

        if (mode == FollowMode.FOLLOW && currentTarget != null && currentTarget.isAlive() && mc.player != null) {
            boolean directClear = isDirectWalkable(currentTarget);

            if (directClear) {
                // Cancel Baritone's discrete voxel FollowProcess so it doesn't fight inputs or zig-zag
                if (!directPursuitActive) {
                    followProcess.cancel();
                    directPursuitActive = true;
                }

                Vec3 targetVel = currentTarget.getDeltaMovement();
                double dist = currentTarget.distanceTo(mc.player);

                // Lead intercept vector: only lead at medium/long distance (> 6m) to prevent overshooting close targets
                double lead = dist > 6.0 ? Math.min(2.0, dist * 0.2) : 0.0;
                double targetX = currentTarget.getX() + targetVel.x * lead;
                double targetZ = currentTarget.getZ() + targetVel.z * lead;

                double dx = targetX - mc.player.getX();
                double dz = targetZ - mc.player.getZ();
                float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;

                // Smoothly steer player movement yaw straight towards the target
                float currentYaw = mc.player.getYRot();
                float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
                mc.player.setYRot(currentYaw + Mth.clamp(deltaYaw * 0.5f, -35.0f, 35.0f));

                // Optimal combat spacing: stop running forward at followDistance (2.2m) to prevent overshooting
                if (dist > followDistance) {
                    mc.options.keyUp.setDown(true);
                    mc.options.keyDown.setDown(false);
                    mc.player.setSprinting(true);

                    // Auto-jump over 1-block obstacles while sprinting
                    if (mc.player.horizontalCollision && mc.player.onGround()) {
                        mc.options.keyJump.setDown(true);
                    }
                } else if (dist < 1.3) {
                    // Too close (inside enemy hitbox): step back slightly to optimal strike spacing
                    mc.options.keyUp.setDown(false);
                    mc.options.keyDown.setDown(true);
                } else {
                    mc.options.keyUp.setDown(false);
                    mc.options.keyDown.setDown(false);
                }
            } else {
                // Obstructed line of sight: fall back to Baritone's A* pathfinder
                if (directPursuitActive) {
                    directPursuitActive = false;
                    mc.options.keyUp.setDown(false);
                    ticksSinceLastRestart = MIN_RESTART_INTERVAL_TICKS; // Force immediate Baritone dispatch
                    follow(currentTarget, followDistance);
                }
            }
        } else {
            if (directPursuitActive) {
                directPursuitActive = false;
                if (mc.options != null) {
                    mc.options.keyUp.setDown(false);
                }
            }
        }
    }

    /**
     * Keeps the player at a fixed safe distance band around the target WITHOUT
     * rushing in: backs away when the target gets too close, holds position in
     * the band, and approaches only when the target moves beyond the band.
     * Uses the same direct key input style as direct pursuit but with an
     * outbound bias so the bot never drifts into melee range.
     */
    public void maintainDistance(Entity target, double minDist, double maxDist) {
        boolean wasMaintaining = maintainingDistance;
        mode = FollowMode.FOLLOW;
        currentTarget = target;
        directPursuitActive = false;
        maintainingDistance = true;

        if (target == null || mc.player == null) {
            stop();
            return;
        }

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        // Far or obstructed: delegate the approach to Baritone A* (same as follow()).
        // Only dispatch once; don't restart the path every tick.
        if (dist > 24.0 || !isDirectWalkable(target)) {
            if (!wasMaintaining) {
                if (baritone.getPathingBehavior().isPathing()) {
                    baritone.getPathingBehavior().cancelEverything();
                }
                followProcess.cancel();
                BaritoneAPI.getSettings().followRadius.value = Math.max(1, (int) Math.ceil(maxDist));
                BaritoneAPI.getSettings().followOffsetDistance.value = 0.0;
                BaritoneAPI.getSettings().avoidance.value = false;
                UUID targetUuid = target.getUUID();
                followProcess.follow(e -> e != null && e.getUUID().equals(targetUuid));
            }
            return;
        }

        // Close and walkable: direct key input with an outbound bias.
        // Cancel Baritone ONLY on the transition into direct mode, not every tick —
        // cancelEverything() every tick kills the approach path and makes the bot
        // jitter / look like it is randomly picking and dropping targets.
        if (!wasMaintaining && baritone.getPathingBehavior().isPathing()) {
            baritone.getPathingBehavior().cancelEverything();
        }

        // Face the target so backward movement is directly away from it
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float currentYaw = mc.player.getYRot();
        float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        mc.player.setYRot(currentYaw + Mth.clamp(deltaYaw * 0.5f, -35.0f, 35.0f));

        if (dist < minDist - 0.2) {
            // Too close: back away (facing the target = moving directly away)
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(true);
            mc.player.setSprinting(false);
        } else if (dist > maxDist + 0.2) {
            // Too far: close back in
            mc.options.keyUp.setDown(true);
            mc.options.keyDown.setDown(false);
            mc.player.setSprinting(true);
            if (mc.player.horizontalCollision && mc.player.onGround()) {
                mc.options.keyJump.setDown(true);
            }
        } else {
            // In the band: hold still, keep facing the target
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
        }
    }

    public void follow(Entity target, double distance) {
        mode = FollowMode.FOLLOW;
        currentTarget = target;
        maintainingDistance = false;

        if (target == null) {
            stop();
            return;
        }

        UUID uuid = target.getUUID();
        boolean sameTarget = followUuid != null && followUuid.equals(uuid);
        boolean sameDistance = Math.abs(followDistance - distance) < 0.01;

        // If direct pursuit is active and path is clear, let direct Euclidean loop run without Baritone overhead
        if (directPursuitActive && isDirectWalkable(target)) {
            followUuid = uuid;
            followDistance = distance;
            return;
        }

        if (sameTarget && sameDistance && ticksSinceLastRestart < MIN_RESTART_INTERVAL_TICKS) {
            return; // Path is still running
        }

        if (sameTarget && sameDistance) {
            return;
        }

        followUuid = uuid;
        followDistance = distance;
        ticksSinceLastRestart = 0;

        followProcess.cancel();

        BaritoneAPI.getSettings().followRadius.value = distance <= 3.5
            ? Math.max(1, (int) Math.floor(distance))
            : Math.max(1, (int) Math.ceil(distance));
        BaritoneAPI.getSettings().followOffsetDistance.value = 0.0;
        BaritoneAPI.getSettings().avoidance.value = false;

        UUID targetUuid = target.getUUID();
        followProcess.follow(e -> e != null && e.getUUID().equals(targetUuid));
    }

    public void flee(Entity target, double distance) {
        mode = FollowMode.FLEE;
        currentTarget = target;
        directPursuitActive = false;
        maintainingDistance = false;

        if (target == null) {
            stop();
            return;
        }

        net.minecraft.world.phys.Vec3 targetVel = target.getDeltaMovement();
        double decay = (1.0 - Math.pow(0.91, 10)) / (1.0 - 0.91);
        double predX = target.getX() + targetVel.x * decay;
        double predZ = target.getZ() + targetVel.z * decay;
        net.minecraft.core.BlockPos predictedBlock = new net.minecraft.core.BlockPos(
            (int) Math.floor(predX),
            target.getBlockY(),
            (int) Math.floor(predZ)
        );

        GoalRunAway runAwayGoal = new GoalRunAway(distance, predictedBlock);
        goalProcess.setGoalAndPath(runAwayGoal);
        goalProcess.path();
    }

    public void stop() {
        mode = FollowMode.NONE;
        currentTarget = null;
        followUuid = null;
        followDistance = -1.0;
        ticksSinceLastRestart = 0;
        directPursuitActive = false;
        maintainingDistance = false;

        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyJump.setDown(false);
        }

        BaritoneAPI.getSettings().avoidance.value = false;
        followProcess.cancel();
        baritone.getPathingBehavior().cancelEverything();
    }

    public boolean isBusy() {
        return directPursuitActive || baritone.getPathingBehavior().isPathing();
    }

    public Entity getCurrentTarget() {
        return currentTarget;
    }

    public FollowMode getMode() {
        return mode;
    }
}