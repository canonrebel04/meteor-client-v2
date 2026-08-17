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
        RANGED_KITING,     // Flying or elevated targets: hold 8-12 block distance with bow/crossbow
        CIRCLE_STRAFE,     // 1v1 melee engagement: orbit around target to dodge direct swing arc
        IDLE
    }

    private TacticalMode currentMode = TacticalMode.IDLE;
    private int circleStrafeDirection = 1; // 1 = clockwise, -1 = counter-clockwise
    private int circleStrafeTimer = 0;

    /**
     * Evaluates the group landscape and commands the follow controller with the optimal target and distance.
     */
    public TacticalMode tick(
        LocalPlayer player,
        CombatFollowController followController,
        CombatGroupAwareness.GroupSnapshot snap,
        boolean canTakeRisk,
        double baseStrikeDist,
        double baseFollowDist,
        CombatMode combatMode
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

        double currentDist = player.distanceTo(frontline);

        // --- Ranged Kiting Mode (Ghasts, Blazes, Breezes, Phantoms, Elevated Mobs) ---
        if (combatMode == CombatMode.RANGED_KITE) {
            currentMode = TacticalMode.RANGED_KITING;
            if (currentDist < 7.0) {
                // Too close: kite backward to open up 10-12 block shooting range
                followController.flee(frontline, 12.0);
            } else if (currentDist > 14.0) {
                // Too far: close in to effective shooting range
                followController.follow(frontline, 10.0);
            }
            return currentMode;
        }

        // --- 1v1 Circle Strafing ---
        if (snap.targets().size() == 1 && currentDist <= 3.8 && canTakeRisk) {
            currentMode = TacticalMode.CIRCLE_STRAFE;
            circleStrafeTimer++;
            // Switch circling direction periodically or upon obstacle collision
            if (circleStrafeTimer > 40 || player.horizontalCollision) {
                circleStrafeDirection *= -1;
                circleStrafeTimer = 0;
            }
            followController.follow(frontline, Math.min(2.4, baseStrikeDist));
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

    public int getCircleStrafeDirection() {
        return circleStrafeDirection;
    }

    public void stop() {
        currentMode = TacticalMode.IDLE;
        circleStrafeTimer = 0;
    }
}
