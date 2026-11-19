/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Opponent intent classifier (C14). Rules over a few cheap features; deterministic and explainable.
 */
public enum Intent {
    CHASING, KITING, BRIDGING, CRYSTALLING, BURROWED, FLEEING, IDLE, UNKNOWN;

    /** Features describing one opponent, sampled over the last ~second. */
    public record Features(
        double distance,
        double closingSpeed,      // blocks/tick, + approaching
        double facingDot,         // their look vector . direction to us, -1..1
        double verticalSpeed,
        boolean placingBlocksUnder,
        int crystalsNearUs,       // crystals within 6 blocks of us
        boolean insideBlock,      // standing inside a solid block / obsidian hole
        int idleTicks,
        boolean holdingRanged
    ) {
    }

    public static Intent classify(Features f) {
        if (f == null) return UNKNOWN;
        if (f.insideBlock()) return BURROWED;
        if (f.crystalsNearUs() > 0 && f.distance() < 10) return CRYSTALLING;
        if (f.placingBlocksUnder() && f.verticalSpeed() > 0.05) return BRIDGING;
        if (f.idleTicks() > 100 && Math.abs(f.closingSpeed()) < 0.01) return IDLE;
        if (f.closingSpeed() > 0.08 && f.facingDot() > 0.6) return CHASING;
        if (f.closingSpeed() < -0.12 && f.facingDot() < 0) return FLEEING;
        if (f.holdingRanged() && f.distance() > 6 && f.closingSpeed() <= 0.05) return KITING;
        if (f.closingSpeed() < -0.05 && f.facingDot() > 0.5) return KITING; // backing off while watching us
        return UNKNOWN;
    }

    /** How dangerous the intent makes them relative to the plain matchup (multiplier on their DPS). */
    public double threatMultiplier() {
        return switch (this) {
            case CRYSTALLING -> 1.6;
            case CHASING -> 1.1;
            case KITING -> 1.05;
            case FLEEING, IDLE -> 0.8;
            case BURROWED -> 0.9;
            default -> 1.0;
        };
    }
}
