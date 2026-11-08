/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement.elytrafly.modes;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class Packet extends ElytraFlightMode {

    private final Vec3 vec3d = new Vec3(0, 0, 0);

    public Packet() {
        super(ElytraFlightModes.Packet);
    }

    @Override
    public void onDeactivate() {
        mc.player.getAbilities().flying = false;
        mc.player.getAbilities().mayfly = false;
    }

    @Override
    public void onTick() {
        super.onTick();

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA || mc.player.fallDistance <= 0.2)
            return;

        double forward = 0;
        double strafe = 0;
        if (mc.options.keyUp.isDown()) forward += elytraFly.horizontalSpeed.get();
        if (mc.options.keyDown.isDown()) forward -= elytraFly.horizontalSpeed.get();
        if (mc.options.keyLeft.isDown()) strafe += elytraFly.horizontalSpeed.get();
        if (mc.options.keyRight.isDown()) strafe -= elytraFly.horizontalSpeed.get();

        double vertical = -elytraFly.fallMultiplier.get();
        if (mc.options.keyJump.isDown()) {
            vertical = elytraFly.verticalSpeed.get();
        } else if (mc.options.keyShift.isDown()) {
            vertical = -elytraFly.verticalSpeed.get();
        }

        Vec3 velocity = new Vec3(strafe, vertical, forward).yRot(-(float) Math.toRadians(mc.player.getYRot()));
        ((meteordevelopment.meteorclient.mixininterface.IVec3) vec3d).meteor$set(velocity.x, velocity.y, velocity.z);

        mc.player.setDeltaMovement(vec3d);
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
    }

    //Walalalalalalalalalalalala
    @Override
    public void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundMovePlayerPacket) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    @Override
    public void onPlayerMove() {
        mc.player.getAbilities().flying = true;
        mc.player.getAbilities().setFlyingSpeed(elytraFly.horizontalSpeed.get().floatValue() / 20);
    }
}
