/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Backtrack: delays incoming entity-movement packets for nearby players, holding them at
 * their previous positions for N ms (attack the old position = reach-like effect).
 * Grim 2.0 explicitly ships NO backtrack checks (GrimAnticheat #545, #1080) — the maintainer
 * classified inbound-packet freezing as "legitimate behaviour" for Grim 2.0.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;

public class Backtrack extends Module {
    private static final int MAX_HELD = 300;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("How long to hold movement packets (ms) before releasing them.")
        .defaultValue(300)
        .range(50, 1000)
        .sliderRange(50, 500)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Only backtrack entities within this range.")
        .defaultValue(8)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> onlyPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-players")
        .description("Only backtrack other players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> teleports = sgGeneral.add(new BoolSetting.Builder()
        .name("teleports")
        .description("Also delay full teleport packets (riskier on anticheats).")
        .defaultValue(false)
        .build()
    );

    private static class HeldPacket {
        final Object packet; // ClientboundMoveEntityPacket | ClientboundTeleportEntityPacket
        final long expireMs;

        HeldPacket(Object packet, long expireMs) {
            this.packet = packet;
            this.expireMs = expireMs;
        }
    }

    private final Deque<HeldPacket> held = new ArrayDeque<>();

    public Backtrack() {
        super(Categories.Combat, "backtrack", "Delays incoming entity movement packets so you can hit players at their previous positions. Grim 2.0 has no backtrack checks.");
    }

    @Override
    public void onActivate() {
        held.clear();
    }

    @Override
    public void onDeactivate() {
        flushAll();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundMoveEntityPacket move) {
            if (shouldDelay(move.getEntity(mc.level))) {
                event.cancel();
                held.addLast(new HeldPacket(move, System.currentTimeMillis() + delay.get()));
            }
        } else if (teleports.get() && event.packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity entity = mc.level.getEntity(teleport.id());
            if (shouldDelay(entity)) {
                event.cancel();
                held.addLast(new HeldPacket(teleport, System.currentTimeMillis() + delay.get()));
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        long now = System.currentTimeMillis();

        // Release expired packets in arrival order
        while (!held.isEmpty() && held.peekFirst().expireMs <= now) {
            dispatch(held.pollFirst());
        }

        // Safety cap: flush oldest if the queue ever explodes
        while (held.size() > MAX_HELD) {
            dispatch(held.pollFirst());
        }
    }

    private boolean shouldDelay(Entity entity) {
        if (mc.player == null || mc.level == null || entity == null) return false;
        if (entity == mc.player) return false;
        if (onlyPlayers.get() && !(entity instanceof Player)) return false;
        return mc.player.distanceTo(entity) <= range.get();
    }

    private void dispatch(HeldPacket heldPacket) {
        if (mc.getConnection() == null) return;
        if (heldPacket.packet instanceof ClientboundMoveEntityPacket move) {
            mc.getConnection().handleMoveEntity(move);
        } else if (heldPacket.packet instanceof ClientboundTeleportEntityPacket teleport) {
            mc.getConnection().handleTeleportEntity(teleport);
        }
    }

    private void flushAll() {
        while (!held.isEmpty()) dispatch(held.pollFirst());
    }
}
