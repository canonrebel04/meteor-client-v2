/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class TrailFollower extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> lookaheadDistance = sgGeneral.add(new IntSetting.Builder()
        .name("lookahead-distance")
        .description("Distance in blocks ahead of the player to project the rolling GoalXZ.")
        .defaultValue(256)
        .range(64, 1024)
        .sliderRange(64, 512)
        .build()
    );

    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder()
        .name("update-interval")
        .description("Ticks between updating the projected Baritone goal.")
        .defaultValue(40)
        .range(10, 200)
        .sliderRange(10, 100)
        .build()
    );

    private final Setting<Integer> maxGapTicks = sgGeneral.add(new IntSetting.Builder()
        .name("max-gap-ticks")
        .description("Ticks without seeing new trail chunks before sweeping +/-45 degrees.")
        .defaultValue(200)
        .range(40, 600)
        .sliderRange(40, 400)
        .build()
    );

    private final Setting<Boolean> useElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("use-elytra")
        .description("Use Baritone #elytra when gliding instead of walking pathing.")
        .defaultValue(true)
        .build()
    );

    private final Set<ChunkPos> seenChunks = new HashSet<>();
    private final Deque<ChunkPos> trailHistory = new ArrayDeque<>();
    private double headingX;
    private double headingZ;
    private int ticksSinceUpdate;
    private int ticksSinceNewTrail;
    private int sweepStep;

    public TrailFollower() {
        super(Categories.World, "trail-follower", "Follows NewChunks trails automatically by projecting rolling Baritone goals.");
    }

    @Override
    public void onActivate() {
        seenChunks.clear();
        trailHistory.clear();
        ticksSinceUpdate = 0;
        ticksSinceNewTrail = 0;
        sweepStep = 0;

        if (mc.player != null) {
            double yawRad = Math.toRadians(mc.player.getYRot());
            headingX = -Math.sin(yawRad);
            headingZ = Math.cos(yawRad);
        } else {
            headingX = 0;
            headingZ = 1;
        }

        NewChunks nc = Modules.get().get(NewChunks.class);
        if (nc != null && !nc.isActive()) {
            nc.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        PathManagers.get().stop();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        NewChunks nc = Modules.get().get(NewChunks.class);
        if (nc == null) return;

        boolean foundNew = false;
        for (ChunkPos cp : nc.getNewChunks()) {
            if (seenChunks.add(cp)) {
                trailHistory.addLast(cp);
                if (trailHistory.size() > 32) {
                    trailHistory.removeFirst();
                }
                foundNew = true;
            }
        }

        if (foundNew) {
            ticksSinceNewTrail = 0;
            sweepStep = 0;
            recomputeHeading();
        } else {
            ticksSinceNewTrail++;
            if (ticksSinceNewTrail >= maxGapTicks.get()) {
                ticksSinceNewTrail = 0;
                double angle = (sweepStep % 2 == 0 ? 1 : -1) * Math.toRadians(45.0);
                sweepStep++;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double nx = headingX * cos - headingZ * sin;
                double nz = headingX * sin + headingZ * cos;
                headingX = nx;
                headingZ = nz;
            }
        }

        ticksSinceUpdate++;
        if (ticksSinceUpdate < updateInterval.get()) return;
        ticksSinceUpdate = 0;

        int targetX = (int) Math.round(mc.player.getX() + headingX * lookaheadDistance.get());
        int targetZ = (int) Math.round(mc.player.getZ() + headingZ * lookaheadDistance.get());

        if (useElytra.get() && mc.player.isFallFlying()) {
            var elytra = BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess();
            if (elytra.isLoaded()) {
                elytra.pathTo(new GoalXZ(targetX, targetZ));
                return;
            }
        }
        PathManagers.get().moveTo(new BlockPos(targetX, (int) mc.player.getY(), targetZ), true);
    }

    private void recomputeHeading() {
        if (trailHistory.size() < 2) return;
        ChunkPos[] arr = trailHistory.toArray(new ChunkPos[0]);
        double vx = 0;
        double vz = 0;
        for (int i = 1; i < arr.length; i++) {
            double weight = i;
            vx += (arr[i].x() - arr[i - 1].x()) * weight;
            vz += (arr[i].z() - arr[i - 1].z()) * weight;
        }
        double len = Math.hypot(vx, vz);
        if (len > 1e-4) {
            headingX = vx / len;
            headingZ = vz / len;
        }
    }
}
