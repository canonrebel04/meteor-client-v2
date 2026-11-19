/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.Map;
import java.util.Set;

/**
 * Allies / trust list and territory defence (C89, C90), pure.
 */
public final class Allegiance {
    public enum Standing { FRIEND, TEAMMATE, NEUTRAL, HOSTILE }

    private Allegiance() {
    }

    /**
     * Friends list first, then the same scoreboard team (or matching dyed-leather colour, opt-in), then the revenge
     * flag (they hit us in the last {@code revengeTicks}).
     */
    public static Standing standing(boolean friend, String myTeam, String theirTeam, boolean colourMatch, boolean useColour,
                                    long lastHitUsTick, long now, int revengeTicks) {
        if (friend) return Standing.FRIEND;
        if (myTeam != null && !myTeam.isEmpty() && myTeam.equals(theirTeam)) return Standing.TEAMMATE;
        if (useColour && colourMatch) return Standing.TEAMMATE;
        if (lastHitUsTick >= 0 && now - lastHitUsTick <= revengeTicks) return Standing.HOSTILE;
        return Standing.NEUTRAL;
    }

    public static boolean mayAttack(Standing s, boolean attackNeutralPlayers) {
        return s == Standing.HOSTILE || (s == Standing.NEUTRAL && attackNeutralPlayers);
    }

    public enum Defend { IDLE, PATROL, WARN, ENGAGE, LURE }

    /**
     * C90 territory mode: a non-allied player inside the base radius is an intruder; warn first (if set), engage
     * once they stay or are hostile; lure to the trap point when one is set (opt-in). Otherwise patrol.
     */
    public static Defend territory(boolean enabled, double intruderDistanceFromBase, double radius, Standing s, int ticksInside,
                                   int warnTicks, boolean lureTrapSet) {
        if (!enabled) return Defend.IDLE;
        if (s == Standing.FRIEND || s == Standing.TEAMMATE || intruderDistanceFromBase > radius) return Defend.PATROL;
        if (s != Standing.HOSTILE && ticksInside < warnTicks) return Defend.WARN;
        return lureTrapSet ? Defend.LURE : Defend.ENGAGE;
    }

    /** Next patrol waypoint index (round robin), skipping ones within 3 blocks of us. */
    public static int nextWaypoint(double[][] points, int current, double x, double z) {
        if (points.length == 0) return -1;
        for (int i = 1; i <= points.length; i++) {
            int k = (current + i) % points.length;
            if (Math.hypot(points[k][0] - x, points[k][1] - z) > 3) return k;
        }
        return current;
    }

    /** Count of recorded hits per player name, for revenge flags (kept small). */
    public static void recordHit(Map<String, Long> table, String name, long tick, Set<String> friends) {
        if (name == null || friends.contains(name)) return;
        table.put(name, tick);
        if (table.size() > 64) table.entrySet().removeIf(e -> tick - e.getValue() > 20 * 60 * 5);
    }
}
