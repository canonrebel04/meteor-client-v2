/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.ServerboundMovePlayerPacketAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

public class RotationFilter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> sessionLock = sgGeneral.add(new BoolSetting.Builder()
        .name("session-lock")
        .description("Locks the mouse sensitivity at world join to prevent sensitivity tracker drift.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> yawJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-jitter")
        .description("Probability of yaw step jitter.")
        .defaultValue(0.15)
        .min(0.0)
        .max(1.0)
        .build()
    );

    private final Setting<Double> pitchJitter = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch-jitter")
        .description("Probability of pitch step jitter.")
        .defaultValue(0.15)
        .min(0.0)
        .max(1.0)
        .build()
    );

    private static double SESSION_SENSITIVITY = -1.0;
    private float lastSentYaw = -1000f;
    private float lastSentPitch = -1000f;

    public RotationFilter() {
        super(Categories.Player, "rotation-filter", "Quantizes outgoing rotation packets to match mouse sensitivity GCD.");
    }

    @Override
    public void onActivate() {
        if (mc.options != null && mc.options.sensitivity() != null) {
            SESSION_SENSITIVITY = mc.options.sensitivity().get();
        }
        lastSentYaw = -1000f;
        lastSentPitch = -1000f;
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        if (mc.options != null && mc.options.sensitivity() != null) {
            if (sessionLock.get() || SESSION_SENSITIVITY < 0) {
                SESSION_SENSITIVITY = mc.options.sensitivity().get();
            }
        }
        lastSentYaw = -1000f;
        lastSentPitch = -1000f;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundPlayerPositionPacket) {
            lastSentYaw = -1000f;
            lastSentPitch = -1000f;
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (event.packet instanceof ServerboundMovePlayerPacket packet) {
            if (packet.hasRotation()) {
                float currentYaw = packet.getYRot(mc.player.getYRot());
                float currentPitch = packet.getXRot(mc.player.getXRot());

                float lastYaw = lastSentYaw >= -999 ? lastSentYaw : mc.player.yRotO;
                float lastPitch = lastSentPitch >= -999 ? lastSentPitch : mc.player.xRotO;

                float deltaYaw = currentYaw - lastYaw;
                float deltaPitch = currentPitch - lastPitch;

                deltaYaw = Mth.wrapDegrees(deltaYaw);

                double sensitivity = sessionLock.get() ? SESSION_SENSITIVITY : mc.options.sensitivity().get();
                if (sensitivity < 0) sensitivity = mc.options.sensitivity().get();

                double f = sensitivity * 0.6 + 0.2;
                double gcdValue = f * f * f * 1.2;
                double yawGCD = gcdValue;
                double pitchGCD = gcdValue * 0.1;

                // Quantize Yaw
                float quantizedDeltaYaw;
                if (yawJitter.get() > 0 && Math.random() < yawJitter.get()) {
                    int steps = (int) Math.round(deltaYaw / yawGCD);
                    steps += (Math.random() < 0.5) ? 1 : -1;
                    quantizedDeltaYaw = (float) (steps * yawGCD);
                } else {
                    quantizedDeltaYaw = (float) (Math.round(deltaYaw / yawGCD) * yawGCD);
                }

                // Quantize Pitch
                float quantizedDeltaPitch;
                if (pitchJitter.get() > 0 && Math.random() < pitchJitter.get()) {
                    int steps = (int) Math.round(deltaPitch / pitchGCD);
                    steps += (Math.random() < 0.5) ? 1 : -1;
                    quantizedDeltaPitch = (float) (steps * pitchGCD);
                } else {
                    quantizedDeltaPitch = (float) (Math.round(deltaPitch / pitchGCD) * pitchGCD);
                }

                float newYaw = lastYaw + quantizedDeltaYaw;
                float newPitch = lastPitch + quantizedDeltaPitch;

                newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);

                ((ServerboundMovePlayerPacketAccessor) packet).meteor$setYRot(newYaw);
                ((ServerboundMovePlayerPacketAccessor) packet).meteor$setXRot(newPitch);

                lastSentYaw = newYaw;
                lastSentPitch = newPitch;
            }
        }
    }
}
