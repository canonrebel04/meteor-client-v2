/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Status-effect handling (C57). */
public final class StatusResponse {
    public enum Effect { POISON, WITHER, LEVITATION, SLOWNESS, BLINDNESS, DARKNESS, WEAKNESS, MINING_FATIGUE, HUNGER, FIRE }

    public enum Response { DRINK_MILK, BREAK_LOS_AND_HEAL, SHIELD_UP_GROUND, DONT_ENGAGE, FIGHT_CLOSE, EXTINGUISH, EAT, NONE }

    private StatusResponse() {
    }

    /**
     * Milk clears everything (including our buffs), so only when a harmful effect is serious: wither, levitation
     * while high, or poison at low HP. Otherwise targeted responses.
     */
    public static List<Response> respond(Set<Effect> effects, double hp, boolean haveMilk, boolean haveGoodBuffs, double heightAboveGround) {
        List<Response> out = new ArrayList<>();
        boolean serious = effects.contains(Effect.WITHER)
            || (effects.contains(Effect.LEVITATION) && heightAboveGround > 6)
            || (effects.contains(Effect.POISON) && hp <= 8);
        if (haveMilk && serious && (!haveGoodBuffs || hp <= 8)) out.add(Response.DRINK_MILK);
        if (effects.contains(Effect.FIRE)) out.add(Response.EXTINGUISH);
        if (effects.contains(Effect.BLINDNESS) || effects.contains(Effect.DARKNESS)) out.add(Response.FIGHT_CLOSE);
        if (effects.contains(Effect.WEAKNESS)) out.add(Response.DONT_ENGAGE);
        if (effects.contains(Effect.SLOWNESS)) out.add(Response.SHIELD_UP_GROUND);
        if ((effects.contains(Effect.POISON) || effects.contains(Effect.WITHER)) && !out.contains(Response.DRINK_MILK)) out.add(Response.BREAK_LOS_AND_HEAL);
        if (effects.contains(Effect.HUNGER)) out.add(Response.EAT);
        if (out.isEmpty()) out.add(Response.NONE);
        return out;
    }
}
