/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/** Resets the shared {@link TickBudget} at the very start of every client tick. */
public final class PerfHooks {
    private PerfHooks() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(PerfHooks.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 200)
    private static void onTickPre(TickEvent.Pre event) {
        TickBudget.game().beginTick();
    }
}
