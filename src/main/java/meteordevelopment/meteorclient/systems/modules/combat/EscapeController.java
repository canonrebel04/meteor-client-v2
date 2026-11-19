/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.modules.combat.model.*;
import meteordevelopment.meteorclient.utils.player.CameraArbiter;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * In-world side of the P1 escape stack (C41-C50): samples flee candidates around us and scores them with
 * {@link FleePlanner} (distance gain, LOS break, cover, hazards, dead-end flood fill), tracks pursuit for flee
 * completion, and executes the opt-in escapes (pearl, wind charge, elytra, water clutch). Every camera write goes
 * through {@link CameraArbiter}.
 */
public final class EscapeController {
    private final FleePlanner planner = new FleePlanner();
    private final PursuitTracker pursuit = new PursuitTracker();
    private final EvasiveJitter jitter = new EvasiveJitter(System.nanoTime());
    private @Nullable BlockPos fleeDest;
    private long nextReplanTick;
    private boolean lastCornered;
    private @Nullable String lastWhy;
    private int escapeCooldown;
    private int elytraFireworkCooldown;
    private int windChargeStage;

    public void reset() {
        fleeDest = null;
        nextReplanTick = 0;
        lastCornered = false;
        lastWhy = null;
        pursuit.reset();
        windChargeStage = 0;
    }

    public PursuitTracker pursuit() {
        return pursuit;
    }

    public @Nullable BlockPos fleeDestination() {
        return fleeDest;
    }

    public boolean cornered() {
        return lastCornered;
    }

    public @Nullable String lastWhy() {
        return lastWhy;
    }

    /** Pursuit sample (C42), once per brain pass while a target exists. */
    public void samplePursuit(LivingEntity pursuer, long tick) {
        if (mc.player == null) return;
        pursuit.sample(tick, mc.player.distanceTo(pursuer), mc.player.hasLineOfSight(pursuer));
    }

    /**
     * Re-plans the flee destination on an irregular interval (C49 pursuer shaking) or when we arrived. Returns the
     * destination, or null when no candidate gains ground (cornered).
     */
    public @Nullable BlockPos plan(LivingEntity pursuer, List<LivingEntity> threats, long tick, boolean shake, boolean avoidDeadEnds) {
        if (mc.player == null || mc.level == null) return null;
        boolean arrived = fleeDest != null && mc.player.blockPosition().distSqr(fleeDest) < 9;
        if (fleeDest != null && tick < nextReplanTick && !arrived) return fleeDest;
        nextReplanTick = tick + (shake ? jitter.replanTicks() : 30);

        double meX = mc.player.getX(), meZ = mc.player.getZ();
        double away = Math.atan2(meZ - pursuer.getZ(), meX - pursuer.getX());
        double offset = shake ? Math.toRadians(jitter.next(25)) : 0;
        List<FleePlanner.Candidate> cands = new ArrayList<>();
        int spokes = 10;
        for (double r : new double[]{12, 20}) {
            for (int i = 0; i < spokes; i++) {
                double a = away + offset + 2 * Math.PI * i / spokes;
                FleePlanner.Candidate c = sample(meX + Math.cos(a) * r, meZ + Math.sin(a) * r, pursuer, avoidDeadEnds);
                if (c != null) cands.add(c);
            }
        }
        List<FleePlanner.Pursuer> ps = new ArrayList<>();
        ps.add(new FleePlanner.Pursuer(pursuer.getX(), pursuer.getZ(), Math.max(0.2, pursuer.getDeltaMovement().horizontalDistance())));
        for (LivingEntity t : threats) {
            if (t != pursuer && t.distanceTo(mc.player) < 24) ps.add(new FleePlanner.Pursuer(t.getX(), t.getZ(), 0.25));
        }
        double mySpeed = mc.player.isSprinting() || HungerPlanner.canSprint(mc.player.getFoodData().getFoodLevel()) ? 0.28 : 0.215;
        FleePlanner.Scored best = planner.best(meX, meZ, cands, ps, mySpeed);
        lastCornered = FleePlanner.cornered(best);
        if (best == null) {
            fleeDest = null;
            lastWhy = "no usable flee point";
            return null;
        }
        lastWhy = best.why();
        fleeDest = BlockPos.containing(best.candidate().x(), best.candidate().y(), best.candidate().z());
        return fleeDest;
    }

    private FleePlanner.@Nullable Candidate sample(double x, double z, LivingEntity pursuer, boolean flood) {
        if (mc.player == null || mc.level == null) return null;
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int py = mc.player.blockPosition().getY();
        if (!mc.level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) return null;
        for (int dy = 4; dy >= -6; dy--) {
            int y = py + dy;
            if (!standable(bx, y, bz)) continue;
            boolean hazard = hazard(bx, y, bz);
            Vec3 eye = new Vec3(bx + 0.5, y + 1.62, bz + 0.5);
            HitResult hit = mc.level.clip(new ClipContext(pursuer.getEyePosition(), eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pursuer));
            boolean los = hit.getType() == HitResult.Type.BLOCK;
            int cover = 0;
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                if ((ox != 0 || oz != 0) && solid(bx + ox, y + 1, bz + oz)) cover++;
            }
            int space = flood ? EscapeSpace.reachable(this::standable, bx, y, bz, 28) : 100;
            return new FleePlanner.Candidate(bx + 0.5, y, bz + 0.5, true, hazard, los, cover, space, y - py);
        }
        return null;
    }

    private boolean solid(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockPos p = new BlockPos(x, y, z);
        return !mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    private boolean standable(int x, int y, int z) {
        if (mc.level == null) return false;
        return solid(x, y - 1, z) && !solid(x, y, z) && !solid(x, y + 1, z)
            && !mc.level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.LAVA);
    }

    private boolean hazard(int x, int y, int z) {
        if (mc.level == null) return true;
        BlockState below = mc.level.getBlockState(new BlockPos(x, y - 1, z));
        BlockState feet = mc.level.getBlockState(new BlockPos(x, y, z));
        return below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS) || feet.is(Blocks.FIRE) || feet.is(Blocks.SOUL_FIRE)
            || feet.is(Blocks.SWEET_BERRY_BUSH) || feet.is(Blocks.COBWEB) || feet.is(Blocks.POWDER_SNOW)
            || mc.level.getFluidState(new BlockPos(x, y - 1, z)).is(FluidTags.LAVA)
            || mc.level.getFluidState(new BlockPos(x, y, z)).is(FluidTags.LAVA);
    }

    /** Blocks between our feet and the ground (up to 64), for the clutch. */
    public double blocksToGround() {
        if (mc.player == null || mc.level == null) return 0;
        BlockPos p = mc.player.blockPosition();
        for (int d = 0; d < 64; d++) {
            if (solid(p.getX(), p.getY() - 1 - d, p.getZ()) || !mc.level.getFluidState(p.below(1 + d)).isEmpty()) {
                return mc.player.getY() - (p.getY() - d);
            }
        }
        return 64;
    }

    public EscapeSelector.Situation situation(@Nullable LivingEntity pursuer, boolean safeHoleNearby, boolean allowClutch) {
        if (mc.player == null) throw new IllegalStateException("no player");
        boolean falling = !mc.player.onGround() && mc.player.getDeltaMovement().y < -0.3 && !mc.player.isFallFlying();
        double toGround = falling ? blocksToGround() : 0;
        boolean clutch = allowClutch && falling && FallClutch.needed(mc.player.fallDistance, toGround, 0,
            mc.player.getHealth() + mc.player.getAbsorptionAmount(), 2);
        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        boolean pearlCd = !pearl.found() || mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(pearl.slot()));
        double dist = pursuer == null ? 99 : mc.player.distanceTo(pursuer);
        boolean openSky = mc.level != null && mc.level.canSeeSky(mc.player.blockPosition().above());
        return new EscapeSelector.Situation(
            pearl.found(), pearlCd || escapeCooldown > 0, InvUtils.findInHotbar(Items.WIND_CHARGE).found(),
            mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA),
            InvUtils.findInHotbar(Items.FIREWORK_ROCKET).found(), InvUtils.findInHotbar(Items.OBSIDIAN).found(),
            safeHoleNearby, falling, clutch, InvUtils.findInHotbar(Items.WATER_BUCKET).found(), lastCornered, dist,
            pursuit.closingIn(), mc.player.getHealth() + mc.player.getAbsorptionAmount(), openSky,
            HungerPlanner.canSprint(mc.player.getFoodData().getFoodLevel()));
    }

    /** Per-tick upkeep (cooldowns, multi-tick escapes). */
    public void tick() {
        if (escapeCooldown > 0) escapeCooldown--;
        if (elytraFireworkCooldown > 0) elytraFireworkCooldown--;
        if (windChargeStage > 0 && mc.player != null) {
            // stage 2: we jumped last tick -> fire the charge straight down
            windChargeStage--;
            if (windChargeStage == 0) useHotbarItem(Items.WIND_CHARGE, mc.player.getYRot(), 90f);
        }
    }

    /** Pearl escape (C44): solve a landing away from the pursuer and throw. */
    public boolean pearl(LivingEntity pursuer) {
        if (mc.player == null || mc.level == null) return false;
        float yaw = (float) Math.toDegrees(Math.atan2(-(mc.player.getX() - pursuer.getX()), mc.player.getZ() - pursuer.getZ()));
        if (fleeDest != null) {
            yaw = (float) Math.toDegrees(Math.atan2(-(fleeDest.getX() + 0.5 - mc.player.getX()), fleeDest.getZ() + 0.5 - mc.player.getZ()));
        }
        Vec3 eye = mc.player.getEyePosition();
        PearlSolver.Solution s = PearlSolver.bestAlongYaw(eye.x, eye.y, eye.z, yaw, this::solid, pursuer.getX(), pursuer.getZ(), l -> {
            int x = Mth.floor(l.x()), z = Mth.floor(l.z());
            for (int y = Mth.floor(l.y()) + 1; y >= Mth.floor(l.y()) - 1; y--) {
                if (standable(x, y, z) && !hazard(x, y, z)) return Math.hypot(l.x() - pursuer.getX(), l.z() - pursuer.getZ()) > 10;
            }
            return false;
        });
        if (s == null) return false;
        if (!useHotbarItem(Items.ENDER_PEARL, s.yaw(), s.pitch())) return false;
        escapeCooldown = 40;
        return true;
    }

    /** Wind-charge jump (C45): jump, then fire a charge at our feet next tick. */
    public boolean windCharge() {
        if (mc.player == null || !mc.player.onGround() || escapeCooldown > 0) return false;
        if (!InvUtils.findInHotbar(Items.WIND_CHARGE).found()) return false;
        mc.player.jumpFromGround();
        windChargeStage = 1;
        escapeCooldown = 30;
        return true;
    }

    /** Elytra + firework escape (C45): take off, aim away and up, boost. */
    public boolean elytra(LivingEntity pursuer) {
        if (mc.player == null || mc.getConnection() == null) return false;
        if (!mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA)) return false;
        float yaw = (float) Math.toDegrees(Math.atan2(-(mc.player.getX() - pursuer.getX()), mc.player.getZ() - pursuer.getZ()));
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            return true;
        }
        if (!mc.player.isFallFlying()) {
            if (mc.player.tryToStartFallFlying()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            return true;
        }
        CameraArbiter.claim(this, CameraArbiter.PRIORITY_URGENT, yaw, -30f, true);
        if (elytraFireworkCooldown == 0 && useHotbarItem(Items.FIREWORK_ROCKET, yaw, -30f)) elytraFireworkCooldown = 30;
        return true;
    }

    /** Water-bucket clutch (C47): look straight down and place the bucket in the timing window. */
    public boolean clutch() {
        if (mc.player == null) return false;
        double toGround = blocksToGround();
        if (!FallClutch.placeNow(toGround, mc.player.getDeltaMovement().y)) {
            // pre-aim while falling so the placement tick only needs the click
            CameraArbiter.claim(this, CameraArbiter.PRIORITY_URGENT, mc.player.getYRot(), 90f, false);
            return false;
        }
        return useHotbarItem(Items.WATER_BUCKET, mc.player.getYRot(), 90f);
    }

    /** Urgent (unsmoothed) camera claim, swap to the item, use it, swap back. */
    private boolean useHotbarItem(net.minecraft.world.item.Item item, float yaw, float pitch) {
        if (mc.player == null || mc.gameMode == null) return false;
        FindItemResult r = InvUtils.findInHotbar(item);
        if (!r.found()) return false;
        if (!CameraArbiter.claim(this, CameraArbiter.PRIORITY_URGENT, yaw, pitch, false)) return false;
        if (r.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            return true;
        }
        boolean swapped = !r.isMainHand() && InvUtils.swap(r.slot(), true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        if (swapped) InvUtils.swapBack();
        return true;
    }
}
