/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.LookPriorityBridge;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.player.smooth.AimSpring;
import meteordevelopment.meteorclient.utils.player.smooth.CameraClaimResolver;
import meteordevelopment.meteorclient.utils.player.smooth.SmoothProfile;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.util.Mth;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Single owner of the client camera (plan S15). Modules no longer write {@code setYRot/setXRot} directly; they
 * {@link #claim} the camera with a priority and the highest claim of the tick wins (equal priority keeps last
 * tick's owner, so two modules never fight tick by tick).
 *
 * <p>With Baritone installed the winning claim is handed to Baritone's look hub, which turns the camera through the
 * active look profile (Legit-Smooth by default) after the previous rotation was latched, so vanilla interpolates
 * it per frame (S1-S4, S2). Without Baritone a critically-damped spring fallback is used. Unsmoothed (urgent)
 * claims are applied immediately so an attack sent the same tick uses the new rotation.
 */
public final class CameraArbiter {
    public static final int PRIORITY_ASSIST = 1;
    public static final int PRIORITY_STEER = 5;
    public static final int PRIORITY_COMBAT_AIM = 10;
    public static final int PRIORITY_DEFENSE = 20;
    public static final int PRIORITY_URGENT = 30;

    private static final double FALLBACK_MAX_TURN = 40.0;

    private static final CameraClaimResolver RESOLVER = new CameraClaimResolver();
    private static final AimSpring YAW = new AimSpring();
    private static final AimSpring PITCH = new AimSpring();
    private static boolean baritoneClaimActive;
    private static float lastYaw, lastPitch;

    private CameraArbiter() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(CameraArbiter.class);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 100)
    private static void onTickPre(TickEvent.Pre event) {
        RESOLVER.beginTick();
    }

    @EventHandler(priority = EventPriority.LOWEST - 100)
    private static void onTickPost(TickEvent.Post event) {
        // Claims are made between Pre and Post; nobody claimed this tick -> hand the camera back
        if (!RESOLVER.hasClaim()) {
            release();
        }
    }

    @EventHandler
    private static void onGameJoined(GameJoinedEvent event) {
        release();
        Config config = Config.get();
        if (config != null) config.syncSmoothProfile();
    }

    /**
     * Claims the camera for this tick.
     *
     * @param owner    the claiming module (identity is used for hysteresis)
     * @param priority one of the {@code PRIORITY_*} constants
     * @param smooth   true to turn smoothly through the look profile; false to apply right away (urgent)
     * @return true when this claim won (so far) this tick
     */
    public static boolean claim(Object owner, int priority, float yaw, float pitch, boolean smooth) {
        if (mc.player == null) return false;
        pitch = Mth.clamp(pitch, -90f, 90f);
        if (!RESOLVER.offer(owner, priority, yaw, pitch, smooth)) return false;

        if (!smooth) {
            // Urgent: apply now, on the sensitivity grid
            float y = Rotations.gcdQuantize(yaw, false);
            float p = Mth.clamp(Rotations.gcdQuantize(pitch, true), -90f, 90f);
            mc.player.setYRot(y);
            mc.player.setXRot(p);
            YAW.invalidate();
            PITCH.invalidate();
        }

        if (BaritoneUtils.IS_AVAILABLE) {
            LookPriorityBridge.syncCamera(owner.getClass().getSimpleName(), priority, yaw, pitch, smooth);
            baritoneClaimActive = true;
        } else if (smooth) {
            applyFallback(yaw, pitch);
        }
        return true;
    }

    /** Claims only the yaw; the pitch stays where it is. */
    public static boolean claimYaw(Object owner, int priority, float yaw) {
        if (mc.player == null) return false;
        return claim(owner, priority, yaw, mc.player.getXRot(), true);
    }

    /** Who holds the camera this tick, or null. */
    public static Object owner() {
        return RESOLVER.owner();
    }

    private static void applyFallback(float yaw, float pitch) {
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();
        // Restart from the real camera if it moved externally (mouse, teleport) since our last write
        if (!YAW.isInitialized() || Math.abs(curYaw - lastYaw) > 0.01f || Math.abs(curPitch - lastPitch) > 0.01f) {
            if (!YAW.isInitialized()) {
                YAW.reset(curYaw);
                PITCH.reset(curPitch);
            } else {
                YAW.sync(curYaw);
                PITCH.sync(curPitch);
            }
        }
        double tau = profile().fallbackTau;
        double goalYaw = curYaw + AimSpring.wrapDegrees(yaw - curYaw);
        float y = Rotations.gcdQuantize((float) YAW.step(goalYaw, tau, FALLBACK_MAX_TURN), false);
        float p = Mth.clamp(Rotations.gcdQuantize((float) PITCH.step(pitch, tau * 1.3, FALLBACK_MAX_TURN), true), -90f, 90f);
        mc.player.setYRot(y);
        mc.player.setXRot(p);
        lastYaw = y;
        lastPitch = p;
    }

    private static SmoothProfile profile() {
        Config config = Config.get();
        return config != null ? config.lookProfile.get() : SmoothProfile.LegitSmooth;
    }

    /** Drops the current camera claim immediately (death / respawn fade). */
    public static void forceRelease() {
        release();
    }

    private static void release() {
        YAW.invalidate();
        PITCH.invalidate();
        if (baritoneClaimActive) {
            baritoneClaimActive = false;
            if (BaritoneUtils.IS_AVAILABLE) LookPriorityBridge.releaseCamera();
        }
    }
}
