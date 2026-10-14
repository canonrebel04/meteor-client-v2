/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.debug;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;

/** Baritone state for debug dumps. Only loaded when Baritone is present. */
final class BaritoneDebugInfo {
    private BaritoneDebugInfo() {
    }

    static void append(StringBuilder sb) {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

        sb.append("process: ").append(baritone.getPathingControlManager().mostRecentInControl()
            .map(p -> p.displayName() + " (" + p.getClass().getSimpleName() + ")").orElse("none")).append('\n');
        sb.append("last command: ").append(baritone.getPathingControlManager().mostRecentCommand()
            .map(c -> c.commandType + " goal=" + c.goal).orElse("none")).append('\n');

        Goal goal = baritone.getPathingBehavior().getGoal();
        sb.append("goal: ").append(goal == null ? "none" : goal).append('\n');
        sb.append("pathing: ").append(baritone.getPathingBehavior().isPathing())
            .append(", calculating: ").append(baritone.getPathingBehavior().getInProgress().isPresent()).append('\n');

        baritone.getPathingBehavior().getPath().ifPresentOrElse(
            path -> sb.append("path: ").append(path.length()).append(" nodes, ")
                .append(path.getSrc()).append(" -> ").append(path.getDest()).append('\n'),
            () -> sb.append("path: none\n")
        );

        sb.append("elytra active: ").append(baritone.getElytraProcess().isActive()).append('\n');
    }
}
