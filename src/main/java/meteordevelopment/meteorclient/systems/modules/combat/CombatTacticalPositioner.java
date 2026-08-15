package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Computes and issues Baritone movement goals based on the current
 * {@link CombatGroupAwareness.GroupSnapshot} rather than following a single entity.
 *
 * <h3>Positioning Modes</h3>
 * <ol>
 *   <li><b>ENGAGE</b> – All threats are within the front arc (±60°). Hold at
 *       {@code strikeDistance} directly in front of the horde centroid. Classic
 *       wall-style fighting.</li>
 *   <li><b>SIDE_STEP</b> – Threats are wrapping one side (&gt;120° arc but not
 *       fully surrounded). Step laterally toward the open quadrant to restore
 *       a wall-facing angle.</li>
 *   <li><b>BACK_OFF</b> – Threats exceed {@code backOffArcDeg} (&gt;160°). Retreat
 *       backward along the largest open gap while keeping face toward the centroid.</li>
 *   <li><b>FLEE</b> – Player is surrounded (&gt;220° arc or health critical). Hard
 *       flee toward the largest gap at {@code fleeDistance}.</li>
 * </ol>
 */
public class CombatTacticalPositioner {

    /** Tactical mode — what the positioner is currently doing. */
    public enum TacticalMode { ENGAGE, SIDE_STEP, BACK_OFF, FLEE, IDLE }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final double BACK_OFF_ARC_DEG  = 160.0;
    private static final double SIDE_STEP_ARC_DEG = 120.0;
    /** How many ticks to wait between Baritone goal re-issues to prevent storm re-pathing. */
    private static final int GOAL_REISSUE_INTERVAL = 8;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private TacticalMode currentMode = TacticalMode.IDLE;
    private int ticksSinceReissue = GOAL_REISSUE_INTERVAL;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Called every tick from CombatBrainModule. */
    public void tick(
        LocalPlayer player,
        CombatGroupAwareness.GroupSnapshot snap,
        double strikeDistance,
        double fleeDistance
    ) {
        ticksSinceReissue++;

        if (snap == null || snap.targets().isEmpty()) {
            currentMode = TacticalMode.IDLE;
            return;
        }

        TacticalMode desired = classifyMode(snap, player, strikeDistance);
        boolean modeChanged = desired != currentMode;
        currentMode = desired;

        // Re-issue only when mode changed OR after the cooldown interval.
        if (!modeChanged && ticksSinceReissue < GOAL_REISSUE_INTERVAL) return;

        issueGoal(player, snap, desired, strikeDistance, fleeDistance);
        ticksSinceReissue = 0;
    }

    public TacticalMode getCurrentMode() { return currentMode; }

    public void stop() {
        currentMode = TacticalMode.IDLE;
        ticksSinceReissue = GOAL_REISSUE_INTERVAL;
        try {
            ICustomGoalProcess gp = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
            gp.onLostControl();
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private TacticalMode classifyMode(
        CombatGroupAwareness.GroupSnapshot snap,
        LocalPlayer player,
        double strikeDistance
    ) {
        if (snap.isSurrounded()) return TacticalMode.FLEE;

        double arc = snap.encirclementDeg();
        if (arc >= BACK_OFF_ARC_DEG)  return TacticalMode.BACK_OFF;
        if (arc >= SIDE_STEP_ARC_DEG) return TacticalMode.SIDE_STEP;

        // If any target is already within strike range, just engage in place.
        Vec3 playerPos = player.position();
        for (LivingEntity e : snap.targets()) {
            if (e.distanceTo(player) <= strikeDistance) return TacticalMode.ENGAGE;
        }
        return TacticalMode.ENGAGE;
    }

    private void issueGoal(
        LocalPlayer player,
        CombatGroupAwareness.GroupSnapshot snap,
        TacticalMode mode,
        double strikeDistance,
        double fleeDistance
    ) {
        ICustomGoalProcess gp = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
        Vec3 playerPos = player.position();
        Vec3 centroid = snap.centroid();

        switch (mode) {
            case ENGAGE -> {
                // Stand at strikeDistance from the centroid, on the far side from the player.
                Vec3 toPlayer = playerPos.subtract(centroid).normalize();
                Vec3 target = centroid.add(toPlayer.scale(strikeDistance));
                gp.setGoalAndPath(new GoalBlock(blockPos(target)));
            }
            case SIDE_STEP -> {
                // Move perpendicular to the centroid direction, toward the largest gap.
                double gapYaw = snap.largestGapBearing(); // Minecraft yaw degrees
                Vec3 gapDir = yawToVec(gapYaw);
                // Offset: half strikeDistance toward gap + strikeDistance from centroid.
                Vec3 toPlayer = playerPos.subtract(centroid).normalize();
                Vec3 target = centroid
                    .add(toPlayer.scale(strikeDistance))
                    .add(gapDir.scale(strikeDistance * 0.6));
                gp.setGoalAndPath(new GoalBlock(blockPos(target)));
            }
            case BACK_OFF -> {
                // Retreat toward the largest gap by 1.5x strikeDistance.
                double gapYaw = snap.largestGapBearing();
                Vec3 gapDir = yawToVec(gapYaw);
                Vec3 target = playerPos.add(gapDir.scale(strikeDistance * 1.5));
                gp.setGoalAndPath(new GoalBlock(blockPos(target)));
            }
            case FLEE -> {
                // Hard flee: use GoalRunAway from the horde centroid.
                BlockPos centroidPos = blockPos(centroid);
                gp.setGoalAndPath(new GoalRunAway((int) fleeDistance, centroidPos));
            }
            case IDLE -> {}
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static BlockPos blockPos(Vec3 v) {
        return new BlockPos((int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z));
    }

    /**
     * Converts a Minecraft yaw angle (degrees; 0=south, 90=west, ±180=north, −90=east)
     * to a horizontal unit direction vector (X=east, Z=south).
     */
    private static Vec3 yawToVec(double yawDeg) {
        double rad = Math.toRadians(yawDeg);
        return new Vec3(-Math.sin(rad), 0, Math.cos(rad));
    }
}
