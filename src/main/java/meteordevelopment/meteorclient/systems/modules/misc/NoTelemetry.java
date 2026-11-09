/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Replaces Mojang's telemetry event sender with the vanilla {@code DISABLED} sender for every
 * world session started while this module is on.
 */
public class NoTelemetry extends Module {
    public NoTelemetry() {
        super(Categories.Misc, "no-telemetry", "Stops sending Mojang telemetry events for new world sessions.");
        runInMainMenu = true;
    }

    public static boolean shouldBlock() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        NoTelemetry m = modules.get(NoTelemetry.class);
        return m != null && m.isActive();
    }
}
