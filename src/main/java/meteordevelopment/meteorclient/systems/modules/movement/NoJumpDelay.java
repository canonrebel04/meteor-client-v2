/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LivingEntityAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

/** Shortens or removes the vanilla 10-tick jump cooldown while holding jump. */
public class NoJumpDelay extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Max jump cooldown in ticks (vanilla is 10, 0 = none).")
        .defaultValue(0)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only apply while standing on the ground.")
        .defaultValue(true)
        .build()
    );

    public NoJumpDelay() {
        super(Categories.Movement, "no-jump-delay", "Removes the delay between jumps when holding jump.");
    }

    /** Pure: new cooldown value for the current tick. */
    public static int clampCooldown(int current, int maxDelay, boolean onlyOnGround, boolean onGround) {
        if (onlyOnGround && !onGround) return current;
        return Math.min(current, Math.max(0, maxDelay));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        LivingEntityAccessor acc = (LivingEntityAccessor) mc.player;
        int cur = acc.meteor$getJumpCooldown();
        int next = clampCooldown(cur, delay.get(), onlyOnGround.get(), mc.player.onGround());
        if (next != cur) acc.meteor$setJumpCooldown(next);
    }
}
