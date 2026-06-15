package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public class CombatIntegrationBridge {

    private final IBaritone baritone;
    private final ICustomGoalProcess goalProcess;
    private final IFollowProcess followProcess;

    public CombatIntegrationBridge() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.goalProcess = baritone.getCustomGoalProcess();
        this.followProcess = baritone.getFollowProcess();
    }

    /**
     * Path to a specific block position using GoalBlock.
     */
    public void pathTo(BlockPos pos) {
        goalProcess.setGoalAndPath(new GoalBlock(pos));
        goalProcess.path();
    }

    /**
     * Path near a block position within a specified range using GoalNear.
     */
    public void pathNear(BlockPos pos, int range) {
        goalProcess.setGoalAndPath(new GoalNear(pos, range));
        goalProcess.path();
    }

    /**
     * Path to an XZ coordinate using GoalXZ.
     */
    public void pathToXZ(int x, int z) {
        goalProcess.setGoalAndPath(new GoalXZ(x, z));
        goalProcess.path();
    }

    /**
     * Follow a specific entity. Cancels any existing follow first.
     * If entity is null, stops all pathing instead.
     */
    public void follow(Entity entity) {
        if (entity == null) {
            stop();
            return;
        }
        followProcess.cancel();
        followProcess.follow(e -> e == entity);
    }

    /**
     * Stops all pathing and follow behavior immediately.
     */
    public void stop() {
        baritone.getPathingBehavior().cancelEverything();
    }

    /**
     * Returns whether baritone is currently pathing.
     */
    public boolean isPathing() {
        return baritone.getPathingBehavior().isPathing();
    }

    /**
     * Returns the current pathing goal, or null if none is set.
     */
    public Goal getCurrentGoal() {
        return baritone.getPathingBehavior().getGoal();
    }
}
