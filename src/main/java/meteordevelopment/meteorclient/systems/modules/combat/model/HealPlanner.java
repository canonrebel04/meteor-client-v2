/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Healing planner (C53): picks gap / potion / regen / wait by incoming DPS. Never starts eating (32 ticks for a
 * golden apple, we're slowed and can't hit) inside the enemy's melee range without cover.
 */
public final class HealPlanner {
    public enum Action { NONE, EAT_GAP, EAT_EGAP, DRINK_HEAL, SPLASH_HEAL, DISENGAGE_FIRST, REGEN_WAIT }

    public record Input(double hp, double maxHp, double incomingDps, double enemyDistance, double enemyReach,
                        boolean cover, boolean hasGap, boolean hasEgap, boolean hasHealPotion, boolean hasSplashHeal,
                        boolean regenerating, int food) {
    }

    public static final int GAP_EAT_TICKS = 32;

    private HealPlanner() {
    }

    public static Action decide(Input in) {
        double missing = in.maxHp() - in.hp();
        if (missing < 4) return Action.NONE;
        boolean inMelee = in.enemyDistance() <= in.enemyReach() + 1.5;
        // instant options are fine at any range
        if (in.hp() <= 8 && in.hasSplashHeal()) return Action.SPLASH_HEAL;
        boolean canEatSafely = !inMelee || in.cover();
        double dmgWhileEating = in.incomingDps() * GAP_EAT_TICKS / 20.0;
        if (in.hp() <= 8 && in.hasEgap() && (canEatSafely || dmgWhileEating < in.hp() - 2)) return Action.EAT_EGAP;
        if (!canEatSafely) return (in.hasGap() || in.hasEgap() || in.hasHealPotion()) ? Action.DISENGAGE_FIRST : Action.NONE;
        if (in.hasGap() && (in.cover() || dmgWhileEating < in.hp() - 2)) return Action.EAT_GAP;
        if (in.hasHealPotion()) return Action.DRINK_HEAL;
        if (in.regenerating() || in.food() >= 18) return Action.REGEN_WAIT;
        return Action.NONE;
    }
}
