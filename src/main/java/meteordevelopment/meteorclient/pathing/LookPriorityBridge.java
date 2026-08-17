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

    private LookPriorityBridge() {
    }

    /**
     * Submits (or updates) the rotation Meteor is applying this tick at combat priority.
     * A single persistent request is reused and re-targeted every tick; it is only released
     * via {@link #release()} once Meteor stops rotating.
     */
    public static void sync(double yaw, double pitch) {
        if (request == null) {
            request = BaritoneAPI.getLookPriorityHub().requestRotation(
                    ILookPriorityHub.COMBAT_PRIORITY,
                    new Rotation((float) yaw, (float) pitch),
                    -1);
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
        if (request != null) {
            request.release();
            request = null;
        }
    }
}
