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

import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
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
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
        final int entityId;
        final long expireMs;

        HeldPacket(Object packet, int entityId, long expireMs) {
            this.packet = packet;
            this.entityId = entityId;
            this.expireMs = expireMs;
        }
    }

    private record PositionSample(Vec3 pos, long timeMs) {}

    private static class EntityTrack {
        final VecDeltaCodec serverCodec = new VecDeltaCodec();
        volatile Vec3 serverPos;
        volatile Vec3 heldPos;
        final Deque<PositionSample> samples = new ConcurrentLinkedDeque<>();

        EntityTrack(Vec3 codecBase, Vec3 visualPos) {
            this.serverCodec.setBase(codecBase);
            this.serverPos = codecBase;
            this.heldPos = visualPos;
            this.samples.addLast(new PositionSample(codecBase, System.currentTimeMillis()));
        }
    }

    private final Deque<HeldPacket> held = new ConcurrentLinkedDeque<>();
    private final Map<Integer, EntityTrack> tracks = new ConcurrentHashMap<>();

    public Backtrack() {
        super(Categories.Combat, "backtrack", "Delays incoming entity movement packets so you can hit players at their previous positions. Grim 2.0 has no backtrack checks.");
    }

    @Override
    public void onActivate() {
        held.clear();
        tracks.clear();
    }

    @Override
    public void onDeactivate() {
        flushAll();
        tracks.clear();
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity != null) {
            tracks.remove(event.entity.getId());
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.level == null) return;

        if (event.packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(mc.level);
            if (entity == null || entity == mc.player) return;

            EntityTrack track = getOrCreateTrack(entity);
            if (move.hasPosition()) {
                PositionPath path = move.getPositionDelta().decode(track.serverCodec);
                Vec3 nextPos = path.endPosition();
                track.serverCodec.setBase(nextPos);
                track.serverPos = nextPos;
                recordSample(track, nextPos);
            }

            if (shouldDelay(entity)) {
                event.cancel();
                held.addLast(new HeldPacket(move, entity.getId(), System.currentTimeMillis() + delay.get()));
            } else {
                track.heldPos = entity.position();
            }
        } else if (event.packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity entity = mc.level.getEntity(teleport.id());
            if (entity == null || entity == mc.player) return;

            EntityTrack track = getOrCreateTrack(entity);
            PositionMoveRotation abs = PositionMoveRotation.calculateAbsolute(
                new PositionMoveRotation(track.serverPos, Vec3.ZERO, entity.getYRot(), entity.getXRot()),
                teleport.change(),
                teleport.relatives()
            );
            Vec3 nextPos = abs.position();
            track.serverCodec.setBase(nextPos);
            track.serverPos = nextPos;
            recordSample(track, nextPos);

            if (teleports.get() && shouldDelay(entity)) {
                event.cancel();
                held.addLast(new HeldPacket(teleport, entity.getId(), System.currentTimeMillis() + delay.get()));
            } else {
                dropHeldMovePackets(entity.getId());
                track.heldPos = nextPos;
            }
        } else if (event.packet instanceof ClientboundEntityPositionSyncPacket sync) {
            Entity entity = mc.level.getEntity(sync.id());
            if (entity == null || entity == mc.player) return;

            dropHeldMovePackets(entity.getId());
            EntityTrack track = getOrCreateTrack(entity);
            Vec3 nextPos = sync.position().endPosition();
            track.serverCodec.setBase(nextPos);
            track.serverPos = nextPos;
            track.heldPos = nextPos;
            recordSample(track, nextPos);
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

        long cutoff = now - Math.max(50L, delay.get()) - 100L;
        Iterator<Map.Entry<Integer, EntityTrack>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, EntityTrack> entry = it.next();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            EntityTrack track = entry.getValue();
            while (track.samples.size() > 1 && track.samples.peekFirst().timeMs() < cutoff) {
                track.samples.pollFirst();
            }
        }
    }

    public Vec3 getServerPos(Entity entity) {
        if (entity == null) return Vec3.ZERO;
        EntityTrack track = tracks.get(entity.getId());
        return track != null ? track.serverPos : entity.position();
    }

    public Vec3 getHeldPos(Entity entity) {
        if (entity == null) return Vec3.ZERO;
        EntityTrack track = tracks.get(entity.getId());
        return track != null ? track.heldPos : entity.position();
    }

    public Vec3 getBacktrackedPos(Entity entity) {
        if (entity == null) return Vec3.ZERO;
        Vec3 currentPos = entity.position();
        if (!isActive() || mc.player == null) return currentPos;

        EntityTrack track = tracks.get(entity.getId());
        if (track == null) return currentPos;

        Vec3 playerPos = mc.player.position();
        Vec3 bestPos = currentPos;
        double bestDistSq = currentPos.distanceToSqr(playerPos);

        double heldDistSq = track.heldPos.distanceToSqr(playerPos);
        if (heldDistSq < bestDistSq) {
            bestDistSq = heldDistSq;
            bestPos = track.heldPos;
        }

        double serverDistSq = track.serverPos.distanceToSqr(playerPos);
        if (serverDistSq < bestDistSq) {
            bestDistSq = serverDistSq;
            bestPos = track.serverPos;
        }

        long cutoff = System.currentTimeMillis() - Math.max(50L, delay.get()) - 50L;
        for (PositionSample sample : track.samples) {
            if (sample.timeMs() < cutoff) continue;
            double dSq = sample.pos().distanceToSqr(playerPos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestPos = sample.pos();
            }
        }

        return bestPos;
    }

    public AABB getBacktrackedBox(Entity entity) {
        if (entity == null) return new AABB(0, 0, 0, 0, 0, 0);
        if (!isActive()) return entity.getBoundingBox();
        Vec3 pos = getBacktrackedPos(entity);
        return entity.getDimensions(entity.getPose()).makeBoundingBox(pos);
    }

    private EntityTrack getOrCreateTrack(Entity entity) {
        return tracks.computeIfAbsent(entity.getId(), id -> {
            Vec3 base = entity.getPositionCodec().getBase();
            Vec3 visual = entity.position();
            if (base.lengthSqr() < 1.0E-8 && visual.lengthSqr() > 1.0E-4) {
                base = visual;
            }
            return new EntityTrack(base, visual);
        });
    }

    private void dropHeldMovePackets(int entityId) {
        held.removeIf(hp -> hp.entityId == entityId);
    }

    private void recordSample(EntityTrack track, Vec3 pos) {
        long now = System.currentTimeMillis();
        track.samples.addLast(new PositionSample(pos, now));
        while (track.samples.size() > 64) {
            track.samples.pollFirst();
        }
    }

    private boolean shouldDelay(Entity entity) {
        if (mc.player == null || mc.level == null || entity == null) return false;
        if (entity == mc.player) return false;
        if (onlyPlayers.get() && !(entity instanceof Player)) return false;
        return mc.player.distanceTo(entity) <= range.get();
    }

    private void dispatch(HeldPacket heldPacket) {
        if (heldPacket == null || mc.getConnection() == null) return;
        if (heldPacket.packet instanceof ClientboundMoveEntityPacket move) {
            mc.getConnection().handleMoveEntity(move);
        } else if (heldPacket.packet instanceof ClientboundTeleportEntityPacket teleport) {
            mc.getConnection().handleTeleportEntity(teleport);
        }
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(heldPacket.entityId);
            EntityTrack track = tracks.get(heldPacket.entityId);
            if (entity != null && track != null) {
                track.heldPos = entity.position();
            }
        }
    }

    private void flushAll() {
        while (!held.isEmpty()) dispatch(held.pollFirst());
    }
}
