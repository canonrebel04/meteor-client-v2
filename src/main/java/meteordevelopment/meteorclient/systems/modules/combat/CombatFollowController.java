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

    // Minimum ticks between FollowProcess restarts. Re-issuing follow() every
    // 2-tick pass cancels Baritone's path before it stabilises, resetting the
    // sprint cadence. Wait at least this many ticks between restarts.
    private static final int MIN_RESTART_INTERVAL_TICKS = 10;
    private int ticksSinceLastRestart = 0;

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
     * Called every tick (before state machine) so restart-interval tracking works
     * even when follow()/flee() isn't called this tick.
     */
    public void tick() {
        if (ticksSinceLastRestart < MIN_RESTART_INTERVAL_TICKS) {
            ticksSinceLastRestart++;
        }
    }

    public void follow(Entity target, double distance) {
        mode = FollowMode.FOLLOW;
        currentTarget = target;

        if (target == null) {
            stop();
            return;
        }

        UUID uuid = target.getUUID();
        boolean sameTarget = followUuid != null && followUuid.equals(uuid);
        boolean sameDistance = Math.abs(followDistance - distance) < 0.01;

        // Only restart when the target or distance changed AND we've waited
        // long enough for Baritone to stabilise the current path.
        if (sameTarget && sameDistance && ticksSinceLastRestart < MIN_RESTART_INTERVAL_TICKS) {
            return; // Path is still running — don't disrupt it
        }

        // Only restart if target or distance actually changed, or forced restart
        if (sameTarget && sameDistance) {
            return; // Already following with same parameters, path is stable
        }

        followUuid = uuid;
        followDistance = distance;
        ticksSinceLastRestart = 0;

        // Cancel existing follow before mutating settings so the restarted
        // FollowProcess picks up the new radius. (H3 fix)
        followProcess.cancel();

        // followRadius: use floor for close (≤3.5 m) and ceil for further away
        BaritoneAPI.getSettings().followRadius.value = distance <= 3.5
            ? Math.max(1, (int) Math.floor(distance))
            : Math.max(1, (int) Math.ceil(distance));
        BaritoneAPI.getSettings().followOffsetDistance.value = 0.0;

        // Avoidance DISABLED: avoidance routes Baritone around every nearby mob
        // in a radius-8 cost sphere, causing massive arc detours that delay and
        // suppress sprinting. KillAura handles mobs in weapon range; we want a
        // direct beeline to the primary target.
        BaritoneAPI.getSettings().avoidance.value = false;

        // H2 fix: match by UUID instead of reference equality (`e == target`).
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
        double decay = (1.0 - Math.pow(0.91, 10)) / (1.0 - 0.91); // 10-tick horizon with ground/air friction
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

        // Restore avoidance default (false)
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