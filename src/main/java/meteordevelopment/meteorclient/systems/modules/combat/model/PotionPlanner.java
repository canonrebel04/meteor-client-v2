/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;

/** Potion tactics (C54): which buff to use in which fight phase; splash heals thrown at our feet (pitch 90). */
public final class PotionPlanner {
    public enum Phase { PRE_FIGHT, FIGHTING, FLEEING, BURNING }

    public enum Buff { STRENGTH, SPEED, RESISTANCE, FIRE_RESISTANCE, REGENERATION, INSTANT_HEALTH }

    public record Have(boolean strength, boolean speed, boolean resistance, boolean fireRes, boolean regen, boolean heal) {
    }

    public record Active(boolean strength, boolean speed, boolean resistance, boolean fireRes, boolean regen) {
    }

    public static final float SPLASH_PITCH = 90f;

    private PotionPlanner() {
    }

    public static List<Buff> plan(Phase phase, Have have, Active active, double hp) {
        List<Buff> out = new ArrayList<>();
        switch (phase) {
            case PRE_FIGHT -> {
                if (have.strength() && !active.strength()) out.add(Buff.STRENGTH);
                if (have.speed() && !active.speed()) out.add(Buff.SPEED);
                if (have.resistance() && !active.resistance()) out.add(Buff.RESISTANCE);
            }
            case FIGHTING -> {
                if (hp <= 8 && have.heal()) out.add(Buff.INSTANT_HEALTH);
                if (hp <= 12 && have.regen() && !active.regen()) out.add(Buff.REGENERATION);
                if (have.strength() && !active.strength()) out.add(Buff.STRENGTH);
            }
            case FLEEING -> {
                if (have.speed() && !active.speed()) out.add(Buff.SPEED);
                if (hp <= 8 && have.heal()) out.add(Buff.INSTANT_HEALTH);
            }
            case BURNING -> {
                if (have.fireRes() && !active.fireRes()) out.add(Buff.FIRE_RESISTANCE);
            }
        }
        return out;
    }
}
