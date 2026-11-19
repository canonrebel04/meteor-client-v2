/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.pathing;

import baritone.api.BaritoneAPI;
import baritone.api.behavior.look.ILookPriorityHub;
import baritone.api.behavior.look.ILookRequest;
import baritone.api.utils.Rotation;

/**
 * Bridges Meteor's rotation system to Baritone's {@link ILookPriorityHub} so the two mods stop
 * fighting over {@code player.setYRot()/setXRot()} every tick.
 * <p>
 * When Meteor applies a rotation (KillAura / BowAimbot / CrystalAura / any {@code Rotations.rotate}
 * caller), the winning rotation is submitted to the hub at {@link ILookPriorityHub#COMBAT_PRIORITY}.
 * Baritone's LookBehavior then applies that same rotation instead of its own pathing look, which
 * eliminates the camera desync and conflicting look packets that anti-cheats flag. Meteor keeps
 * sending its own rotation packets and writing the client camera as before; the hub request only
 * makes Baritone agree with Meteor while the rotation is active.
 */
public class LookPriorityBridge {

    private static ILookRequest request;
    private static ILookRequest cameraRequest;
    private static volatile boolean silentMovementCorrectionActive;
    private static volatile float activeCombatYaw;

    private LookPriorityBridge() {
    }

    /**
     * Submits (or updates) the rotation Meteor is applying this tick at combat priority.
     * A single persistent request is reused and re-targeted every tick; it is only released
     * via {@link #release()} once Meteor stops rotating.
     */
    public static void sync(double yaw, double pitch) {
        activeCombatYaw = (float) yaw;
        silentMovementCorrectionActive = true;
        if (request == null) {
            request = BaritoneAPI.getLookPriorityHub().requestRotation(
                    ILookPriorityHub.COMBAT_PRIORITY,
                    new Rotation((float) yaw, (float) pitch),
                    -1);
            // Meteor already applies (and packet-sends) this rotation itself; Baritone must agree, not re-smooth it
            request.setSmooth(false);
            request.setOwner("Meteor/Rotations");
        } else {
            request.setRotation((float) yaw, (float) pitch);
            request.setPriority(ILookPriorityHub.COMBAT_PRIORITY);
        }
    }

    /**
     * Releases the hub request so Baritone reverts to its own look behavior.
     * Safe to call when no request is active.
     */
    public static void release() {
        silentMovementCorrectionActive = false;
        if (request != null) {
            request.release();
            request = null;
        }
    }

    /**
     * Plan S15: hands this tick's winning camera claim (from {@code CameraArbiter}) to Baritone, which turns the
     * camera through its active look profile (Legit-Smooth by default) at its PRE player update, i.e. after the
     * entity's previous rotation was latched, so the turn is interpolated per frame (S2).
     *
     * @param priority arbiter priority, used to order claims just above combat priority
     * @param smooth   false for urgent claims that were already applied directly
     */
    public static void syncCamera(String owner, int priority, float yaw, float pitch, boolean smooth) {
        double prio = ILookPriorityHub.COMBAT_PRIORITY + 1 + priority * 0.01;
        if (cameraRequest == null || cameraRequest.isReleased()) {
            cameraRequest = BaritoneAPI.getLookPriorityHub().requestRotation(prio, new Rotation(yaw, pitch), -1);
        } else {
            cameraRequest.setRotation(yaw, pitch);
            cameraRequest.setPriority(prio);
        }
        cameraRequest.setCamera(true);
        cameraRequest.setSmooth(smooth ? Boolean.TRUE : Boolean.FALSE);
        cameraRequest.setOwner(owner);
    }

    /** Releases the camera claim so Baritone returns to its own look behaviour. */
    public static void releaseCamera() {
        if (cameraRequest != null) {
            cameraRequest.release();
            cameraRequest = null;
        }
    }

    /** Pushes Meteor's smoothness profile to Baritone's {@code lookProfile} setting. */
    public static void applyLookProfile(String baritoneName) {
        var setting = BaritoneAPI.getSettings().lookProfile;
        if (!baritoneName.equals(setting.value)) setting.value = baritoneName;
    }

    /**
     * Enhancement #44: Returns true when Meteor holds combat rotation priority so movement
     * inputs can be strafe-corrected relative to {@link #getActiveCombatYaw()}.
     */
    public static boolean isSilentMovementCorrectionActive() {
        return silentMovementCorrectionActive;
    }

    /**
     * Returns the combat yaw currently submitted to Baritone's look priority hub.
     */
    public static float getActiveCombatYaw() {
        return activeCombatYaw;
    }

    /**
     * Computes strafe-corrected (forward, strafe) unit inputs in [-1, 1] so moving along
     * {@code desiredMoveYaw} remains accurate while the player's server yaw is {@code viewYaw}.
     */
    public static float[] computeStrafeCorrectedInputs(float viewYaw, float desiredMoveYaw) {
        double diffRad = Math.toRadians(desiredMoveYaw - viewYaw);
        float forward = (float) Math.cos(diffRad);
        float strafe = (float) -Math.sin(diffRad);
        return new float[]{forward, strafe};
    }
}
