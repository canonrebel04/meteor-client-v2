package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

public class CombatFollowController {

    private final IBaritone baritone;
    private final IFollowProcess followProcess;
    private final ICustomGoalProcess goalProcess;

    private Entity currentTarget;
    private FollowMode mode;
    private UUID followUuid;
    private double followDistance;

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

    public void follow(Entity target, double distance) {
        mode = FollowMode.FOLLOW;
        currentTarget = target;

        if (target == null) {
            stop();
            return;
        }

        // Only restart the follow process when the target or the desired
        // distance actually changed. doEngageTick re-invokes follow() every
        // 2 ticks while chasing; cancelling + re-following each time restarts
        // baritone's path from scratch, which resets its sprint cadence
        // (sprint only engages once the path stabilizes) and causes the
        // "barely sprints" behavior.
        UUID uuid = target.getUUID();
        if (followUuid != null && followUuid.equals(uuid) && Math.abs(followDistance - distance) < 0.01) {
            return;
        }
        followUuid = uuid;
        followDistance = distance;

        // H3 fix: cancel the existing follow process BEFORE mutating settings so
        // the restarted FollowProcess picks up the new radius/offset. Mutating
        // settings first (old order) let a stale REQUEST_PAUSE / running process
        // swallow the new values.
        followProcess.cancel();

        // Configure follow distance via baritone settings
        // Combat follow strategy: use a GoalNear RADIUS around the target, not a
        // fixed-direction offset. The offset mode (followOffsetDistance +
        // followOffsetDirection, default 0 = north) anchored the goal at a fixed
        // compass offset from the mob — as the mob moved, baritone re-pathed and
        // mined through terrain toward a position that was often behind walls or
        // inside hills, producing the wander/dig behavior. GoalNear(radius)
        // accepts ANY position within `distance` blocks, so the bot approaches
        // naturally and stops just outside the target's effective range.
        BaritoneAPI.getSettings().followRadius.value = Math.max(1, (int) Math.ceil(distance));
        BaritoneAPI.getSettings().followOffsetDistance.value = 0.0;

        // Sprinting: reverted to baritone's DEFAULT behavior (no forced SPRINT
        // input, humanizeMovements left at its default). Forcing the sprint
        // input made baritone's PathExecutor see a permanent SPRINT request it
        // kept clearing/restarting, which suppressed sprinting rather than
        // enabling it. Baritone sprints on its own when the path allows.

        // Mob avoidance: route the approach AROUND clusters instead of through
        // them. Avoidance.create() adds a spherical path-cost bump (coefficient
        // 1.5, radius 8) around every mob; the follow goal still wins at the
        // destination, but baritone won't path straight through a 20-zombie
        // swarm to get there.
        BaritoneAPI.getSettings().avoidance.value = true;

        // H2 fix: match by UUID instead of reference equality (`e == target`).
        // Entity instances are replaced on respawn / dimension switch / ID
        // reallocation, which silently killed the follow. UUID matching survives
        // those cases.
        UUID targetUuid = target.getUUID();
        followProcess.follow(e -> e != null && e.getUUID().equals(targetUuid));
    }

    public void flee(Entity target, double distance) {
        mode = FollowMode.FLEE;
        currentTarget = target;

        if (target == null) {
            stop();
            return;
        }

        net.minecraft.world.phys.Vec3 targetVel = target.getDeltaMovement();
        net.minecraft.world.phys.Vec3 predictedPos = target.position().add(targetVel.scale(20.0));
        net.minecraft.core.BlockPos predictedBlock = net.minecraft.core.BlockPos.containing(predictedPos);

        GoalRunAway runAwayGoal = new GoalRunAway(distance, predictedBlock);
        goalProcess.setGoalAndPath(runAwayGoal);
        goalProcess.path();
    }

    public void stop() {
        mode = FollowMode.NONE;
        currentTarget = null;
        followUuid = null;
        followDistance = -1.0;

        // No sprint override to release (reverted to default baritone sprint);
        // only disable the mob avoidance toggle we own.
        BaritoneAPI.getSettings().avoidance.value = false;

        followProcess.cancel();
        baritone.getPathingBehavior().cancelEverything();
    }

    public boolean isBusy() {
        return baritone.getPathingBehavior().isPathing();
    }

    public Entity getCurrentTarget() {
        return currentTarget;
    }

    public FollowMode getMode() {
        return mode;
    }
}