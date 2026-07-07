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

    public AntiDetectionModule() {
        super(Categories.Combat, "anti-detection", "Randomizes pitch/yaw and sneaks periodically to prevent server-side bot detection during AFK combat.");
    }

    @Override
    public void onDeactivate() {
        if (isSneaking) {
            mc.options.keyShift.setDown(false);
            isSneaking = false;
        }
        sneakTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        // Random rotation
        double range = rotationRandomness.get();
        if (range > 0) {
            float yawOffset = (float) (random.nextDouble() * 2.0 * range - range);
            float pitchOffset = (float) (random.nextDouble() * 2.0 * range - range);
            mc.player.setYRot(mc.player.getYRot() + yawOffset);
            mc.player.setXRot(mc.player.getXRot() + pitchOffset);
        }

        // Sneak cycle
        sneakTimer++;

        if (isSneaking) {
            if (sneakTimer >= sneakInterval.get() + sneakDuration.get()) {
                mc.options.keyShift.setDown(false);
                isSneaking = false;
                sneakTimer = 0;
            }
        } else {
            if (sneakTimer >= sneakInterval.get()) {
                mc.options.keyShift.setDown(true);
                isSneaking = true;
            }
        }
    }
}
