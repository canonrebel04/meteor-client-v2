/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import meteordevelopment.meteorclient.systems.modules.combat.model.WeaponKind;

import java.util.Set;

/**
 * Weapon selection per situation (C25, C26, C30, C70), pure. Axe against a raised shield, then back to the sword a
 * couple of ticks after the shield is disabled; mace while falling onto a target below; spear for a sprint charge;
 * trident in water/rain at mid range; bow/crossbow at range. Swaps are budgeted (no two swaps within
 * {@code minSwapGap} ticks) so FastSwitch-style checks never see flicker.
 */
public final class WeaponArbiter {
    public record Situation(Set<WeaponKind> have, double distance, double reach, boolean targetBlocking,
                            boolean shieldJustDisabled, int ticksSinceShieldDisabled, double fallDistance,
                            boolean targetBelow, boolean sprinting, boolean wetOrRain, boolean targetVisible,
                            int ticksSinceSwap, int minSwapGap, WeaponKind holding) {
    }

    public record Choice(WeaponKind kind, boolean swapNow, String why) {
    }

    private WeaponArbiter() {
    }

    public static WeaponKind best(Situation s) {
        Set<WeaponKind> h = s.have();
        boolean melee = s.distance() <= s.reach() + 0.5;
        // C64 mace smash: falling ≥ 1.5 blocks onto a target below us in reach
        if (h.contains(WeaponKind.MACE) && s.fallDistance() >= 1.5 && s.targetBelow() && melee) return WeaponKind.MACE;
        // C26 axe -> sword follow-up after the shield is disabled
        if (s.shieldJustDisabled() && s.ticksSinceShieldDisabled() >= 2) {
            if (h.contains(WeaponKind.SWORD)) return WeaponKind.SWORD;
            if (h.contains(WeaponKind.AXE)) return WeaponKind.AXE;
        }
        // C25/C30 axe against a raised shield
        if (s.targetBlocking() && melee && h.contains(WeaponKind.AXE)) return WeaponKind.AXE;
        if (!melee) {
            // C65 spear charge: sprinting, 3-6 blocks out
            if (h.contains(WeaponKind.SPEAR) && s.sprinting() && s.distance() <= 6.0) return WeaponKind.SPEAR;
            if (s.targetVisible() && s.distance() >= 8) {
                if (h.contains(WeaponKind.BOW)) return WeaponKind.BOW;
                if (h.contains(WeaponKind.CROSSBOW)) return WeaponKind.CROSSBOW;
            }
            // C63 trident: wet (riptide/loyalty work best), mid range
            if (h.contains(WeaponKind.TRIDENT) && s.wetOrRain() && s.targetVisible() && s.distance() >= 5) return WeaponKind.TRIDENT;
        }
        for (WeaponKind k : new WeaponKind[]{WeaponKind.SWORD, WeaponKind.AXE, WeaponKind.SPEAR, WeaponKind.MACE, WeaponKind.TRIDENT}) {
            if (h.contains(k)) return k;
        }
        return WeaponKind.NONE;
    }

    public static Choice choose(Situation s) {
        WeaponKind k = best(s);
        if (k == WeaponKind.NONE || k == s.holding()) return new Choice(k, false, "keep");
        boolean urgent = k == WeaponKind.MACE || (k == WeaponKind.AXE && s.targetBlocking());
        boolean allowed = s.ticksSinceSwap() >= (urgent ? 1 : s.minSwapGap());
        return new Choice(k, allowed, allowed ? "swap" : "swap budget");
    }
}
