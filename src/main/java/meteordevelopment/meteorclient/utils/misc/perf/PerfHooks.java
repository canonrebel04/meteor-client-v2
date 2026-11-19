/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.render.anim.Anim;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;

/**
 * Resets the shared {@link TickBudget} at the very start of every client tick, records frame times into
 * {@link FrameStats} (S93) and publishes the adaptive render quality and accessibility switches to {@link Anim}
 * (S85/S94).
 */
public final class PerfHooks {
    private static long lastFrame;

    private PerfHooks() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(PerfHooks.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 200)
    private static void onTickPre(TickEvent.Pre event) {
        TickBudget.game().beginTick();
        Config config = Config.get();
        if (config != null) {
            Anim.reduceMotion = config.reduceMotion.get();
            Anim.highContrast = config.highContrast.get();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST + 200)
    private static void onRender2D(Render2DEvent event) {
        long now = java.lang.System.nanoTime();
        if (lastFrame != 0) FrameStats.game().record(now - lastFrame);
        lastFrame = now;
        Config config = Config.get();
        Anim.quality = config == null || config.adaptiveQuality.get() ? FrameStats.game().quality() : 1;
    }
}
