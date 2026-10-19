/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement.elytrafly;

/** Pure decision logic for ElytraFly's hold-jump recast. */
public final class ElytraRecastLogic {
    private ElytraRecastLogic() {}

    public record State(boolean wearingGlider, boolean fallFlying, boolean onGround, boolean inFluid,
                        boolean passenger, boolean jumpHeld, double fallDistance, int ticksSinceLastCast) {}

    public static boolean shouldRecast(State s, double minFallDistance, int delayTicks) {
        if (!s.wearingGlider() || s.fallFlying() || s.onGround() || s.inFluid() || s.passenger()) return false;
        if (!s.jumpHeld()) return false;
        if (s.fallDistance() < minFallDistance) return false;
        return s.ticksSinceLastCast() >= delayTicks;
    }
}
