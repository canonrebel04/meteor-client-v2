package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Dynamically gauges combat distance and aggression based on group positioning and health/risk profile.
 * Coordinates with CombatFollowController for smooth, uninterrupted Baritone entity tracking.
 */
public class CombatTacticalPositioner {

    public enum TacticalMode {
        AGGRESSIVE_STRIKE, // High HP / Creative / Totem: drive straight into the frontline at point-blank reach
        WALL_ENGAGE,       // Frontal horde: hold wall-line at weapon reach
        FLANK_REPOSITION,  // Enemies wrapping sides: step back to funnel horde into front cone
        SURROUND_EVADE,    // Surrounded from behind with low HP: evade to open gap
        IDLE
    }

    private TacticalMode currentMode = TacticalMode.IDLE;

    /**
     * Evaluates the group landscape and commands the follow controller with the optimal target and distance.
     */
    public TacticalMode tick(
        LocalPlayer player,
        CombatFollowController followController,
        CombatGroupAwareness.GroupSnapshot snap,
        boolean canTakeRisk,
        double baseStrikeDist,
        double baseFollowDist
    ) {
        if (snap == null || snap.targets().isEmpty() || followController == null) {
            currentMode = TacticalMode.IDLE;
            return currentMode;
        }

        LivingEntity frontline = snap.frontlineTarget();
        if (frontline == null) frontline = snap.primaryTarget();
        if (frontline == null) {
            currentMode = TacticalMode.IDLE;
            return currentMode;
        }

        // --- Determine Tactical Mode & Dynamic Distance ---
        double desiredDistance;

        if (canTakeRisk) {
            // Aggressive risk taker: dive directly into melee reach of the frontline!
            currentMode = TacticalMode.AGGRESSIVE_STRIKE;
            desiredDistance = Math.min(2.4, baseStrikeDist);
        } else if (snap.isSurrounded()) {
            // Surrounded from behind and unable to take risk: back off to open space
            currentMode = TacticalMode.SURROUND_EVADE;
            desiredDistance = Math.max(3.8, baseFollowDist + 1.0);
        } else if (snap.hasRearThreats() || snap.encirclementDeg() > 140.0) {
            // Enemies closing from sides/rear: keep safe buffer to herd them back into front cone
            currentMode = TacticalMode.FLANK_REPOSITION;
            desiredDistance = Math.max(3.2, baseFollowDist);
        } else {
            // Standard wall engagement: enemies in front arc, close in to strike!
            currentMode = TacticalMode.WALL_ENGAGE;
            desiredDistance = Math.min(2.7, baseStrikeDist);
        }

        // Issue smooth entity follow to the frontline target with the dynamically gauged distance
        followController.follow(frontline, desiredDistance);

        return currentMode;
    }

    public TacticalMode getCurrentMode() {
        return currentMode;
    }

    public void stop() {
        currentMode = TacticalMode.IDLE;
    }
}
