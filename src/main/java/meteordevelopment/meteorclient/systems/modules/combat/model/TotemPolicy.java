/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Totem manager decisions (C52): keep a totem in the offhand when the next hits can kill, swap one in before the
 * pop (pre-pop) and re-totem right after a pop.
 */
public final class TotemPolicy {
    public enum Action { NONE, EQUIP_OFFHAND, RETOTEM, ALLOW_OTHER_OFFHAND }

    private TotemPolicy() {
    }

    /**
     * @param hp            health + absorption
     * @param incomingBurst worst expected damage over the next ~1 s (melee DPS + crystal/fall threat)
     * @param totemsInInv   totems outside the offhand
     * @param offhandTotem  offhand holds a totem now
     * @param justPopped    a pop happened this tick or last
     */
    public static Action decide(double hp, double incomingBurst, int totemsInInv, boolean offhandTotem, boolean justPopped) {
        if (totemsInInv <= 0 && !offhandTotem) return Action.NONE;
        if (justPopped && !offhandTotem) return totemsInInv > 0 ? Action.RETOTEM : Action.NONE;
        if (offhandTotem) return Action.NONE;
        // pre-pop: equip if the burst could take us to <= 2 HP
        if (hp - incomingBurst <= 2.0 || hp <= 10.0) return Action.EQUIP_OFFHAND;
        return Action.ALLOW_OTHER_OFFHAND;
    }
}
