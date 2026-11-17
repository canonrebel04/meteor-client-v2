/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public class BoatFly extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Horizontal flight speed in blocks per second.")
        .defaultValue(10.0)
        .min(0.1)
        .sliderRange(1.0, 50.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical climb and descend speed in blocks per second.")
        .defaultValue(6.0)
        .min(0.1)
        .sliderRange(1.0, 25.0)
        .build()
    );

    private final Setting<Double> fallSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("fall-speed")
        .description("Passive downward glide speed in blocks per second.")
        .defaultValue(0.1)
        .min(0.0)
        .sliderRange(0.0, 2.0)
        .build()
    );

    private final Setting<Boolean> syncYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-yaw")
        .description("Aligns the vehicle yaw with your camera yaw.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> cancelServerPackets = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-server-packets")
        .description("Cancels incoming vehicle position packets to reduce rubberbanding.")
        .defaultValue(false)
        .build()
    );

    public BoatFly() {
        super(Categories.Movement, "boat-fly", "Allows you to fly while riding boats and other vehicles.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) return;

        if (syncYaw.get()) {
            vehicle.setYRot(mc.player.getYRot());
        }

        Vec3 vel = PlayerUtils.getHorizontalVelocity(speed.get());
        double velX = vel.x();
        double velZ = vel.z();
        double velY = 0;

        if (mc.options.keyJump.isDown()) {
            velY += verticalSpeed.get() / 20.0;
        }
        if (mc.options.keySprint.isDown()) {
            velY -= verticalSpeed.get() / 20.0;
        } else if (velY == 0) {
            velY -= fallSpeed.get() / 20.0;
        }

        vehicle.setDeltaMovement(velX, velY, velZ);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (cancelServerPackets.get() && event.packet instanceof ClientboundMoveVehiclePacket) {
            event.cancel();
        }
    }
}
