/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Spawn-protection & respawn behaviour (C60): after death or when the kit is short, don't take revenge fights;
 * run the regear route (stash) first, with a short grace period after respawning.
 */
public final class RespawnPolicy {
    public enum Mode { NORMAL, GRACE, REGEAR, UNGEARED_AVOID }

    /** What a kit has right now. */
    public record Kit(int armorPieces, boolean weapon, int totems, int gaps, int food) {
    }

    /** Minimum kit before fighting players again. */
    public record Requirement(int armorPieces, boolean weapon, int totems, int gaps, int food) {
        public static final Requirement DEFAULT = new Requirement(4, true, 1, 0, 8);
    }

    public static final int GRACE_TICKS = 100;

    private RespawnPolicy() {
    }

    public static boolean kitOk(Kit kit, Requirement req) {
        return kit.armorPieces() >= req.armorPieces() && (kit.weapon() || !req.weapon())
            && kit.totems() >= req.totems() && kit.gaps() >= req.gaps() && kit.food() >= req.food();
    }

    public static Mode mode(long ticksSinceRespawn, Kit kit, Requirement req, boolean stashConfigured, boolean autoRegear) {
        if (ticksSinceRespawn >= 0 && ticksSinceRespawn < GRACE_TICKS) return Mode.GRACE;
        if (kitOk(kit, req)) return Mode.NORMAL;
        return stashConfigured && autoRegear ? Mode.REGEAR : Mode.UNGEARED_AVOID;
    }

    /** May the brain start a fight with a player in this mode (mobs stay allowed in self-defense). */
    public static boolean mayEngagePlayer(Mode m, boolean cornered) {
        return m == Mode.NORMAL || cornered;
    }
}
