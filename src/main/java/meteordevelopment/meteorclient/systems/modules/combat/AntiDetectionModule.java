/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;

import java.util.Random;

public class AntiDetectionModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // General

    private final Setting<Double> rotationRandomness = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-randomness")
        .description("How much random rotation to add each tick.")
        .defaultValue(2.0)
        .min(0)
        .max(10)
        .build()
    );

    private final Setting<Integer> sneakInterval = sgGeneral.add(new IntSetting.Builder()
        .name("sneak-interval")
        .description("Ticks between sneaks.")
        .defaultValue(40)
        .min(10)
        .max(200)
        .build()
    );

    private final Setting<Integer> sneakDuration = sgGeneral.add(new IntSetting.Builder()
        .name("sneak-duration")
        .description("How many ticks to hold sneak.")
        .defaultValue(5)
        .min(1)
        .max(20)
        .build()
    );

    private final Random random = new Random();
    private int sneakTimer = 0;
    private boolean isSneaking = false;
    private int currentSneakDuration = 5;

    private double ouYaw = 0.0;
    private double ouPitch = 0.0;
    private static final double OU_THETA = 0.15;
    private static final double OU_SIGMA = 0.3;
    private static final double OU_DT = 0.05;

    public AntiDetectionModule() {
        super(Categories.Combat, "anti-detection", "Randomizes pitch/yaw using Ornstein-Uhlenbeck stochastic process and sneaks with Poisson-distributed intervals to evade anti-cheat detection during AFK combat.");
    }

    @Override
    public void onDeactivate() {
        if (isSneaking) {
            mc.options.keyShift.setDown(false);
            isSneaking = false;
        }
        sneakTimer = 0;
        ouYaw = 0.0;
        ouPitch = 0.0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        double range = rotationRandomness.get();
        if (range > 0) {
            double sqrtDt = Math.sqrt(OU_DT);
            // OU process: mean-reverting jitter. Stationary std = OU_SIGMA/sqrt(2*OU_THETA)
            // ≈ 0.55 in OU units. Normalize to unit variance and scale by the user's
            // degree setting so `range` behaves as DEGREES of jitter, not radians.
            // (Previous code multiplied raw OU units by range → up to ~1.1 rad ≈ 63°
            // of camera shake with the default 2.0 setting.)
            double ouNorm = OU_SIGMA / Math.sqrt(2.0 * OU_THETA);
            ouYaw += OU_THETA * (0.0 - ouYaw) * OU_DT + OU_SIGMA * random.nextGaussian() * sqrtDt;
            ouPitch += OU_THETA * (0.0 - ouPitch) * OU_DT + OU_SIGMA * random.nextGaussian() * sqrtDt;

            double yawDeg = (ouYaw / ouNorm) * range;
            double pitchDeg = (ouPitch / ouNorm) * range;

            mc.player.setYRot(mc.player.getYRot() + (float) Math.toRadians(yawDeg));
            mc.player.setXRot(net.minecraft.util.Mth.clamp(
                mc.player.getXRot() + (float) Math.toRadians(pitchDeg), -90.0f, 90.0f));
        }

        sneakTimer++;
        if (isSneaking) {
            if (sneakTimer >= currentSneakDuration) {
                mc.options.keyShift.setDown(false);
                isSneaking = false;
                sneakTimer = 0;
            }
        } else {
            if (random.nextDouble() < 1.0 / sneakInterval.get()) {
                mc.options.keyShift.setDown(true);
                isSneaking = true;
                sneakTimer = 0;
                currentSneakDuration = Math.max(1,
                    sneakDuration.get() + (int) (random.nextGaussian() * 2.0));
            }
        }
    }
}
