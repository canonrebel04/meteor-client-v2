/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;

import java.util.Random;

/** Cosmetic: makes your head spin or twitch for other players. Your own camera is unaffected. */
public class Derp extends Module {
    public enum Mode { Spin, Random, Nod }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Head motion style.").defaultValue(Mode.Spin).build());

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Degrees per tick for Spin / Nod.").defaultValue(25).range(1, 90).sliderRange(1, 90).visible(() -> mode.get() != Mode.Random).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-interact").description("Stop while attacking, using or placing so real rotations take over.").defaultValue(true).build());

    private final Random random = new Random();
    private int tick;

    public Derp() {
        super(Categories.Misc, "derp", "Cosmetic head spinning / twitching visible to other players.");
    }

    /** Pure: server-side {yaw, pitch} for a tick. Random mode takes two uniform samples in [0,1). */
    public static float[] rotation(Mode mode, int tick, double speed, double r1, double r2) {
        return switch (mode) {
            case Spin -> new float[]{(float) ((tick * speed) % 360.0), 0};
            case Nod -> new float[]{Float.NaN, (float) (Math.sin(Math.toRadians(tick * speed)) * 90.0)};
            case Random -> new float[]{(float) (r1 * 360.0 - 180.0), (float) (r2 * 180.0 - 90.0)};
        };
    }

    @Override
    public void onActivate() {
        tick = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (pauseOnUse.get() && (mc.options.keyAttack.isDown() || mc.options.keyUse.isDown())) return;
        float[] r = rotation(mode.get(), tick++, speed.get(), random.nextDouble(), random.nextDouble());
        float yaw = Float.isNaN(r[0]) ? mc.player.getYRot() : r[0];
        // Lowest priority so any real module rotation wins.
        Rotations.rotate(yaw, r[1], -1000);
    }
}
