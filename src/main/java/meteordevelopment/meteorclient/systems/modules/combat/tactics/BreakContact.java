/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

/**
 * Break-contact tactics while fleeing (C48), pure. Close a door/trapdoor/fence gate we just went through when the
 * pursuer is behind us; drop a cobweb (players) or a solid block (obsidian, else cobble) into the gap behind us.
 * Placement is opt-in (block-trap play is anticheat-visible) and paced.
 */
public final class BreakContact {
    public enum Action { NONE, CLOSE_DOOR, PLACE_WEB, PLACE_BLOCK }

    /**
     * @param doorBehind       an open door/gate within 2 blocks behind us on our path
     * @param gapBehind        the block 1-2 behind us is air with a floor (a block fits there)
     * @param pursuerDistance  nearest pursuer distance
     * @param pursuerBehind    the pursuer is roughly behind us (dot of our run dir and dir-to-pursuer < -0.3)
     * @param pursuerIsPlayer  players open doors; mobs (except door-breaking zombies on hard) don't
     * @param ticksSincePlace  pacing
     */
    public record Input(boolean doorBehind, boolean doorBreakerMob, boolean gapBehind, double pursuerDistance,
                        boolean pursuerBehind, boolean pursuerIsPlayer, boolean haveWeb, boolean haveObsidian,
                        boolean haveBlock, boolean placeAllowed, int ticksSincePlace, int placeGap) {
    }

    private BreakContact() {
    }

    public static Action decide(Input in) {
        if (!in.pursuerBehind() || in.pursuerDistance() > 10) return Action.NONE;
        // a door stops mobs completely (players need a moment to open it)
        if (in.doorBehind() && !in.doorBreakerMob() && in.pursuerDistance() >= 2.5) return Action.CLOSE_DOOR; // time to turn
        if (!in.placeAllowed() || !in.gapBehind() || in.ticksSincePlace() < in.placeGap()) return Action.NONE;
        if (in.pursuerDistance() < 1.2 || in.pursuerDistance() > 5) return Action.NONE; // too late / not needed yet
        if (in.haveWeb() && in.pursuerIsPlayer()) return Action.PLACE_WEB;
        if (in.haveObsidian() || in.haveBlock()) return Action.PLACE_BLOCK;
        if (in.haveWeb()) return Action.PLACE_WEB;
        return Action.NONE;
    }
}
