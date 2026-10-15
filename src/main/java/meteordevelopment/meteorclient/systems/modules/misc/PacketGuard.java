/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ServerboundMovePlayerPacketAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Bug protection: validates packets that Meteor's own modules emit and drops (or clamps) malformed ones
 * that would get you kicked for a protocol error. Runs at the lowest priority, after other modules
 * have modified outgoing packets. It does not hide or disguise anything.
 */
public class PacketGuard extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRate = settings.createGroup("Rate");

    private final Setting<Boolean> positions = sgGeneral.add(new BoolSetting.Builder()
        .name("positions").description("Drop movement / vehicle / use-on packets with NaN, infinite or out-of-world coordinates.").defaultValue(true).build());

    private final Setting<Boolean> rotations = sgGeneral.add(new BoolSetting.Builder()
        .name("rotations").description("Drop non-finite rotations and clamp pitch to [-90, 90].").defaultValue(true).build());

    private final Setting<Boolean> hotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("hotbar-slot").description("Drop selected-slot packets outside 0-8.").defaultValue(true).build());

    private final Setting<Boolean> lengths = sgGeneral.add(new BoolSetting.Builder()
        .name("text-lengths").description("Drop chat (>256), commands (>32767) and sign lines (>384) that would fail to encode.").defaultValue(true).build());

    private final Setting<Boolean> warn = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-warning").description("Print a (rate-limited) warning when something is dropped or clamped.").defaultValue(true).build());

    private final Setting<Boolean> chatThrottle = sgRate.add(new BoolSetting.Builder()
        .name("chat-spam-guard").description("Hold back chat/commands that would exceed vanilla's chat spam kick threshold.").defaultValue(true).build());

    private final Setting<Integer> chatMargin = sgRate.add(new IntSetting.Builder()
        .name("chat-margin").description("Safety margin below the vanilla threshold (200).").defaultValue(20).range(0, 180).sliderRange(0, 100).visible(chatThrottle::get).build());

    private final Setting<Boolean> rateLimit = sgRate.add(new BoolSetting.Builder()
        .name("packet-rate-limit").description("Drop non-essential packets above a per-second cap (movement, keep-alive, pong, teleport-confirm and tick-end are never dropped).").defaultValue(false).build());

    private final Setting<Integer> maxPerSecond = sgRate.add(new IntSetting.Builder()
        .name("max-per-second").description("Cap for non-essential packets per second.").defaultValue(300).range(20, 2000).sliderRange(50, 1000).visible(rateLimit::get).build());

    private final PacketSanity.RateLimiter limiter = new PacketSanity.RateLimiter();
    private final PacketSanity.ChatThrottle chat = new PacketSanity.ChatThrottle();
    private final Map<String, Long> lastWarn = new HashMap<>();
    private int dropped;

    public PacketGuard() {
        super(Categories.Misc, "packet-guard", "Prevents modules from sending malformed packets that cause protocol kicks.");
    }

    @Override
    public void onActivate() {
        dropped = 0;
        lastWarn.clear();
    }

    @Override
    public String getInfoString() {
        return dropped == 0 ? null : String.valueOf(dropped);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        chat.tick();
    }

    private static boolean essential(Packet<?> p) {
        return p instanceof ServerboundMovePlayerPacket || p instanceof ServerboundKeepAlivePacket
            || p instanceof ServerboundPongPacket || p instanceof ServerboundAcceptTeleportationPacket
            || p instanceof ServerboundClientTickEndPacket || p instanceof ServerboundChatAckPacket
            || p instanceof ServerboundMoveVehiclePacket;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onSend(PacketEvent.Send event) {
        if (event.isCancelled()) return;
        Packet<?> p = event.packet;

        String reason = check(p);
        if (reason != null) {
            drop(event, reason);
            return;
        }

        if (chatThrottle.get() && (p instanceof ServerboundChatPacket || p instanceof ServerboundChatCommandPacket || p instanceof ServerboundChatCommandSignedPacket)) {
            if (!chat.tryAcquire(chatMargin.get())) { drop(event, "chat sent too fast (spam kick guard)"); return; }
        }

        if (rateLimit.get() && !essential(p) && !limiter.tryAcquire(System.currentTimeMillis(), maxPerSecond.get())) {
            drop(event, "packet rate above " + maxPerSecond.get() + "/s");
        }
    }

    /** @return a drop reason, or null if the packet is fine (possibly after clamping). */
    private String check(Packet<?> p) {
        if (p instanceof ServerboundMovePlayerPacket m) {
            if (positions.get() && m.hasPosition() && !PacketSanity.validPosition(m.getX(0), m.getY(0), m.getZ(0)))
                return "invalid movement position";
            if (rotations.get() && m.hasRotation()) {
                float yaw = m.getYRot(0), pitch = m.getXRot(0);
                if (!PacketSanity.validRotationValues(yaw, pitch)) return "non-finite rotation";
                if (!PacketSanity.pitchInRange(pitch)) {
                    ((ServerboundMovePlayerPacketAccessor) m).meteor$setXRot(PacketSanity.clampPitch(pitch));
                    note("pitch clamped");
                }
            }
        } else if (p instanceof ServerboundMoveVehiclePacket v) {
            Vec3 pos = v.position();
            if (positions.get() && !PacketSanity.validPosition(pos.x, pos.y, pos.z)) return "invalid vehicle position";
            if (rotations.get() && !PacketSanity.validRotationValues(v.yRot(), v.xRot())) return "non-finite vehicle rotation";
        } else if (p instanceof ServerboundUseItemOnPacket u) {
            Vec3 l = u.getHitResult().getLocation();
            if (positions.get() && !PacketSanity.validPosition(l.x, l.y, l.z)) return "invalid block-hit location";
        } else if (p instanceof ServerboundSetCarriedItemPacket s) {
            if (hotbar.get() && !PacketSanity.validHotbarSlot(s.getSlot())) return "hotbar slot " + s.getSlot() + " out of range";
        } else if (lengths.get()) {
            if (p instanceof ServerboundChatPacket c && !PacketSanity.validLength(c.message(), PacketSanity.MAX_CHAT_LENGTH)) return "chat message too long";
            if (p instanceof ServerboundChatCommandPacket c && !PacketSanity.validLength(c.command(), PacketSanity.MAX_COMMAND_LENGTH)) return "command too long";
            if (p instanceof ServerboundSignUpdatePacket s) {
                for (String line : s.getLines()) if (!PacketSanity.validLength(line, PacketSanity.MAX_SIGN_LINE_LENGTH)) return "sign line too long";
            }
        }
        return null;
    }

    private void drop(PacketEvent.Send event, String reason) {
        event.cancel();
        dropped++;
        note("dropped: " + reason);
    }

    private void note(String msg) {
        if (!warn.get()) return;
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(msg);
        if (last != null && now - last < 2000) return;
        lastWarn.put(msg, now);
        warning("%s", msg);
    }
}
