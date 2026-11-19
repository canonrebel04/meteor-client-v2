/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalNear;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.simulator.ProjectileEntitySimulator;
import meteordevelopment.meteorclient.utils.entity.simulator.SimulationStep;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PearlPredict extends Module {
    public enum TrapMode {
        Cobweb,
        Obsidian,
        Both
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> includeSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("include-self")
        .description("Also predicts and renders ender pearls thrown by you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxSimulationSteps = sgGeneral.add(new IntSetting.Builder()
        .name("max-steps")
        .description("Maximum simulation ticks per ender pearl.")
        .defaultValue(200)
        .range(20, 400)
        .sliderRange(50, 300)
        .build()
    );

    private final Setting<Boolean> autoTrapLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-trap-landing")
        .description("Places cobweb or obsidian at predicted hostile pearl landing positions within reach.")
        .defaultValue(false)
        .build()
    );

    private final Setting<TrapMode> trapMode = sgGeneral.add(new EnumSetting.Builder<TrapMode>()
        .name("trap-mode")
        .description("Block used to trap hostile pearl landing spots.")
        .defaultValue(TrapMode.Cobweb)
        .visible(autoTrapLanding::get)
        .build()
    );

    private final Setting<Double> trapRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("trap-range")
        .description("Maximum reach for placing landing traps.")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(6.0)
        .visible(autoTrapLanding::get)
        .build()
    );

    private final Setting<Boolean> baritoneIntercept = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone-intercept")
        .description("Directs Baritone toward distant hostile ender pearl landing spots.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> interceptMaxDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("intercept-max-distance")
        .description("Maximum distance for Baritone pearl intercept goals.")
        .defaultValue(64.0)
        .min(8.0)
        .sliderMax(128.0)
        .visible(baritoneIntercept::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates when placing trap blocks.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> renderEta = sgRender.add(new BoolSetting.Builder()
        .name("render-eta")
        .description("Draws impact ETA and thrower name at the landing spot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> etaScale = sgRender.add(new DoubleSetting.Builder()
        .name("eta-scale")
        .description("Scale of the 2D ETA label.")
        .defaultValue(1.1)
        .min(0.5)
        .sliderMax(2.5)
        .visible(renderEta::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the landing marker box is drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the trajectory lines and box outlines.")
        .defaultValue(new SettingColor(190, 80, 255, 220))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Fill color of the landing marker box.")
        .defaultValue(new SettingColor(190, 80, 255, 45))
        .build()
    );

    private final ProjectileEntitySimulator simulator = new ProjectileEntitySimulator();
    private final List<PredictedPearl> predictions = new ArrayList<>();
    private final Vector3d textPos = new Vector3d();
    private BlockPos lastInterceptGoal;

    public PearlPredict() {
        super(Categories.Combat, "pearl-predict", "Simulates ender pearl trajectories to show landing ETA and optionally trap or intercept hostile pearls.");
    }

    @Override
    public void onDeactivate() {
        predictions.clear();
        lastInterceptGoal = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null) {
            predictions.clear();
            return;
        }

        predictions.clear();
        PredictedPearl interceptCandidate = null;
        double interceptBestDistSq = interceptMaxDistance.get() * interceptMaxDistance.get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !pearl.isAlive()) continue;

            Entity owner = pearl.getOwner();
            if (!includeSelf.get() && owner == mc.player) continue;

            PredictedPearl pred = simulatePearl(pearl, owner);
            if (pred == null) continue;
            predictions.add(pred);

            if (!pred.hostile) continue;

            if (autoTrapLanding.get()) {
                tryTrapLanding(pred.landingBlock);
            }

            if (baritoneIntercept.get()) {
                double distSq = PlayerUtils.squaredDistanceTo(pred.landingPos.x, pred.landingPos.y, pred.landingPos.z);
                if (distSq > 25.0 && distSq <= interceptBestDistSq) {
                    interceptBestDistSq = distSq;
                    interceptCandidate = pred;
                }
            }
        }

        if (baritoneIntercept.get() && interceptCandidate != null) {
            BlockPos targetBlock = interceptCandidate.landingBlock;
            if (lastInterceptGoal == null || lastInterceptGoal.distSqr(targetBlock) > 4.0) {
                lastInterceptGoal = targetBlock;
                try {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalNear(targetBlock, 2));
                } catch (Exception ignored) {}
            }
        } else if (interceptCandidate == null) {
            lastInterceptGoal = null;
        }
    }

    private PredictedPearl simulatePearl(ThrownEnderpearl pearl, Entity owner) {
        if (pearl.getDeltaMovement().lengthSqr() < 1.0E-8) return null;
        if (!simulator.set(pearl)) return null;

        List<Vec3> path = new ArrayList<>();
        path.add(new Vec3(simulator.pos.x, simulator.pos.y, simulator.pos.z));

        Vec3 landingPos = null;
        BlockPos landingBlock = null;
        int impactTicks = 0;

        int maxSteps = maxSimulationSteps.get();
        for (int i = 1; i <= maxSteps; i++) {
            SimulationStep step = simulator.tick();
            HitResult hit = step.hitResults.length > 0 ? step.hitResults[0] : null;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                landingPos = hit.getLocation();
                if (hit instanceof BlockHitResult bhr) {
                    BlockPos offsetPos = bhr.getBlockPos().relative(bhr.getDirection());
                    landingBlock = mc.level.getBlockState(offsetPos).canBeReplaced() ? offsetPos : bhr.getBlockPos();
                } else {
                    landingBlock = BlockPos.containing(landingPos);
                }
                path.add(landingPos);
                impactTicks = i;
                break;
            }
            path.add(new Vec3(simulator.pos.x, simulator.pos.y, simulator.pos.z));
            if (step.shouldStop) {
                landingPos = path.getLast();
                landingBlock = BlockPos.containing(landingPos);
                impactTicks = i;
                break;
            }
        }

        if (landingPos == null) {
            landingPos = path.getLast();
            landingBlock = BlockPos.containing(landingPos);
            impactTicks = maxSteps;
        }

        boolean hostile = owner == null || (owner != mc.player && (!(owner instanceof Player p) || Friends.get().shouldAttack(p)));
        String ownerName = owner != null ? owner.getName().getString() : "Pearl";
        return new PredictedPearl(path, landingPos, landingBlock, impactTicks, hostile, ownerName);
    }

    private void tryTrapLanding(BlockPos landingBlock) {
        if (landingBlock == null || mc.level == null) return;
        if (PlayerUtils.squaredDistanceTo(landingBlock) > trapRange.get() * trapRange.get()) return;

        FindItemResult cobweb = InvUtils.findInHotbar(Items.COBWEB);
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);

        switch (trapMode.get()) {
            case Cobweb -> {
                if (cobweb.found()) BlockUtils.place(landingBlock, cobweb, rotate.get(), 70, true, false);
            }
            case Obsidian -> {
                if (obsidian.found()) BlockUtils.place(landingBlock, obsidian, rotate.get(), 70, true, false);
            }
            case Both -> {
                if (cobweb.found()) BlockUtils.place(landingBlock, cobweb, rotate.get(), 70, true, false);
                if (obsidian.found()) BlockUtils.place(landingBlock.above(), obsidian, rotate.get(), 70, true, false);
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (predictions.isEmpty()) return;

        for (PredictedPearl pred : predictions) {
            List<Vec3> pts = pred.path;
            for (int i = 1; i < pts.size(); i++) {
                Vec3 a = pts.get(i - 1);
                Vec3 b = pts.get(i);
                event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, lineColor.get());
            }
            if (pred.landingBlock != null) {
                event.renderer.box(pred.landingBlock, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!renderEta.get() || predictions.isEmpty()) return;

        TextRenderer text = TextRenderer.get();
        Color labelColor = new Color(255, 255, 255, 255);

        for (PredictedPearl pred : predictions) {
            if (pred.landingPos == null) continue;
            textPos.set(pred.landingPos.x, pred.landingPos.y + 0.65, pred.landingPos.z);
            if (!NametagUtils.to2D(textPos, etaScale.get())) continue;

            String label = String.format(Locale.ROOT, "%s (%.2fs)", pred.ownerName, pred.ticksToImpact * 0.05);
            NametagUtils.begin(textPos, event.graphics);
            text.beginBig(event.graphics);
            text.render(label, -text.getWidth(label, true) / 2.0, -text.getHeight(true), labelColor, true);
            text.end();
            NametagUtils.end(event.graphics);
        }
    }

    private record PredictedPearl(
        List<Vec3> path,
        Vec3 landingPos,
        BlockPos landingBlock,
        int ticksToImpact,
        boolean hostile,
        String ownerName
    ) {}
}
