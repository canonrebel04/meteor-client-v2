package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.utils.input.Input;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

public class CombatFollowController {

    private final IBaritone baritone;
    private final IFollowProcess followProcess;
    private final ICustomGoalProcess goalProcess;

    private Entity currentTarget;
    private FollowMode mode;

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
    }

    public void follow(Entity target, double distance) {
        mode = FollowMode.FOLLOW;
        currentTarget = target;

        if (target == null) {
            stop();
            return;
        }

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

        // Force sprint and disable humanizeMovements (prevents 2%/tick random sprint drop)
        // so baritone sprints continuously toward the target rather than intermittently.
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        BaritoneAPI.getSettings().humanizeMovements.value = false;

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

        GoalRunAway runAwayGoal = new GoalRunAway(distance, target.blockPosition());
        goalProcess.setGoalAndPath(runAwayGoal);
        goalProcess.path();
    }

    public void stop() {
        mode = FollowMode.NONE;
        currentTarget = null;

        // Release forced sprint override and restore humanizeMovements to its default.
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        BaritoneAPI.getSettings().humanizeMovements.value = true;

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