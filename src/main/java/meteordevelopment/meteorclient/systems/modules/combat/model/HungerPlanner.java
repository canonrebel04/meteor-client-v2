/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Hunger/saturation planner (C58): sprinting needs food > 6, natural regen needs >= 18. Eat early when not in
 * melee so sprint stays available for an escape.
 */
public final class HungerPlanner {
    public enum Action { NONE, EAT_NOW, EAT_WHEN_SAFE }

    public static final int SPRINT_MIN = 7, REGEN_MIN = 18;

    private HungerPlanner() {
    }

    public static boolean canSprint(int food) {
        return food >= SPRINT_MIN;
    }

    public static Action decide(int food, float saturation, boolean inMelee, boolean fleeing) {
        if (food <= SPRINT_MIN + 1) return inMelee && !fleeing ? Action.EAT_WHEN_SAFE : Action.EAT_NOW;
        if (food < REGEN_MIN && saturation <= 0.5f) return inMelee ? Action.NONE : Action.EAT_WHEN_SAFE;
        return Action.NONE;
    }
}
