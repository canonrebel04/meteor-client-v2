package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.world.entity.Entity;

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

        // Configure follow distance via baritone settings
        BaritoneAPI.getSettings().followRadius.value = 0;
        BaritoneAPI.getSettings().followOffsetDistance.value = distance;

        followProcess.cancel();
        followProcess.follow(e -> e == target);
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