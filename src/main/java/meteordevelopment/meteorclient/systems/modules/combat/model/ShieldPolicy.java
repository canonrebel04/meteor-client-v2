/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Shield engine decisions (C51): when to raise and lower. Never hold the shield up against a held axe (it gets
 * disabled for 5 s), lower it to swing when our cooldown is ready, raise it predictively on an incoming swing,
 * arrow or crystal.
 */
public final class ShieldPolicy {
    public enum Action { RAISE, LOWER, KEEP }

    public record Input(boolean haveShield, boolean shieldDisabled, boolean raised, WeaponKind threatWeapon,
                        double threatDistance, double threatReach, boolean incomingProjectile, boolean crystalNearby,
                        float myCooldown, boolean targetInMyReach, int ticksUntilTheirSwing) {
    }

    private ShieldPolicy() {
    }

    public static Action decide(Input in) {
        if (!in.haveShield() || in.shieldDisabled()) return in.raised() ? Action.LOWER : Action.KEEP;
        // our hit is ready and they're in reach: drop guard and swing (blocking also stops our attacks)
        if (in.myCooldown() >= 0.9f && in.targetInMyReach() && !in.incomingProjectile()) return Action.LOWER;
        // an axe at melee range disables the shield: dodge/space instead of blocking
        if (in.threatWeapon() == WeaponKind.AXE && in.threatDistance() <= in.threatReach() + 0.5) {
            return in.raised() ? Action.LOWER : Action.KEEP;
        }
        if (in.incomingProjectile() || in.crystalNearby()) return Action.RAISE;
        // vanilla needs 5 ticks of raise before the shield blocks
        boolean swingSoon = in.ticksUntilTheirSwing() >= 0 && in.ticksUntilTheirSwing() <= 6;
        if (in.threatWeapon().melee() && in.threatDistance() <= in.threatReach() + 1.0 && swingSoon) return Action.RAISE;
        return Action.KEEP;
    }
}
