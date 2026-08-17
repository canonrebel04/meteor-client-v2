/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * AimAssist: subtle, legit-feeling mouse aim assistance — gently moves your REAL view toward
 * a nearby target inside a FOV cone while attacking. No packet rotations (that is KillAura's
 * job); this only nudges your mouse for manual play.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public class AimAssist extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum target distance.")
        .defaultValue(6)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Maximum angle (degrees) between your view and the target for the assist to engage.")
        .defaultValue(45)
        .range(1, 180)
        .sliderRange(5, 90)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("How many degrees per tick the view is nudged toward the target.")
        .defaultValue(3)
        .min(0.1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> onAttack = sgGeneral.add(new BoolSetting.Builder()
        .name("on-attack")
        .description("Only assists while the attack button is held.")
        .defaultValue(true)
        .build()
    );

    public AimAssist() {
        super(Categories.Combat, "aim-assist", "Gently moves your mouse toward nearby targets while attacking (no packet rotations).");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive()) return;
        if (onAttack.get() && !mc.options.keyAttack.isDown()) return;

        Player target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestDistance);
        if (target == null || !Friends.get().shouldAttack(target)) return;

        double targetYaw = Rotations.getYaw(target);
        double targetPitch = Rotations.getPitch(target);

        double yawDiff = Math.abs(Mth.wrapDegrees(targetYaw - mc.player.getYRot()));
        double pitchDiff = Math.abs(Mth.wrapDegrees(targetPitch - mc.player.getXRot()));
        if (yawDiff > fov.get() || pitchDiff > fov.get()) return;

        float step = speed.get().floatValue();

        float newYaw = mc.player.getYRot() + (float) Mth.clamp(Mth.wrapDegrees(targetYaw - mc.player.getYRot()), -step, step);
        float newPitch = mc.player.getXRot() + (float) Mth.clamp(Mth.wrapDegrees(targetPitch - mc.player.getXRot()), -step, step);

        mc.player.setYRot(newYaw);
        mc.player.setXRot(Mth.clamp(newPitch, -90, 90));
    }
}
