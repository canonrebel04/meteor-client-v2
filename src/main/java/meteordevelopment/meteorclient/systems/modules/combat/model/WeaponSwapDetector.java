/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/** Detects held-weapon switches (C17) and what they announce. */
public final class WeaponSwapDetector {
    public enum Expectation { NONE, SHIELD_BREAK, RANGED_ATTACK, MACE_SMASH, SPEAR_CHARGE, CRYSTAL_ATTACK, EATING, PEARL_ESCAPE }

    public record Swap(WeaponKind from, WeaponKind to, Expectation expectation, long tick) {
    }

    private WeaponKind current = WeaponKind.NONE;
    private Swap last;

    /** Observe the held kind this tick; returns the swap if it changed. */
    public Swap observe(WeaponKind held, long tick) {
        WeaponKind h = held == null ? WeaponKind.NONE : held;
        if (h == current) return null;
        WeaponKind from = current;
        current = h;
        last = new Swap(from, h, expect(h), tick);
        return last;
    }

    static Expectation expect(WeaponKind to) {
        return switch (to) {
            case AXE -> Expectation.SHIELD_BREAK;
            case BOW, CROSSBOW, TRIDENT -> Expectation.RANGED_ATTACK;
            case MACE -> Expectation.MACE_SMASH;
            case SPEAR -> Expectation.SPEAR_CHARGE;
            case CRYSTAL -> Expectation.CRYSTAL_ATTACK;
            case FOOD, POTION -> Expectation.EATING;
            case PEARL -> Expectation.PEARL_ESCAPE;
            default -> Expectation.NONE;
        };
    }

    public WeaponKind current() {
        return current;
    }

    /** The last swap's expectation while it's recent ({@code window} ticks), else NONE. */
    public Expectation expectation(long now, int window) {
        return last != null && now - last.tick() <= window ? last.expectation() : Expectation.NONE;
    }
}
