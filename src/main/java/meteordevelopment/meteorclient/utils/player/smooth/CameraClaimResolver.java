/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player.smooth;

/**
 * Per-tick camera ownership (plan S15). Every tick each module that wants to turn the camera offers a claim; the
 * highest priority claim wins. On equal priority the owner that held the camera last tick keeps it (hysteresis), so
 * two equal claimants never alternate the camera tick by tick.
 *
 * <p>Pure Java, no Minecraft dependencies.
 */
public final class CameraClaimResolver {
    private Object owner;
    private Object lastOwner;
    private int priority;
    private float yaw, pitch;
    private boolean smooth;
    private boolean claimed;

    /** Starts a new tick: forgets this tick's claims but remembers who owned the camera. */
    public void beginTick() {
        lastOwner = claimed ? owner : null;
        owner = null;
        claimed = false;
        priority = Integer.MIN_VALUE;
    }

    /**
     * Offers a claim for this tick.
     *
     * @return true when this claim is (currently) the winner, i.e. its rotation should be applied
     */
    public boolean offer(Object who, int prio, float yaw, float pitch, boolean smooth) {
        if (claimed) {
            if (prio < priority) return false;
            if (prio == priority && who != owner) {
                // Tie: only last tick's owner may take over (hysteresis)
                if (who != lastOwner || owner == lastOwner) return false;
            }
        }
        this.owner = who;
        this.priority = prio;
        this.yaw = yaw;
        this.pitch = pitch;
        this.smooth = smooth;
        this.claimed = true;
        return true;
    }

    public boolean hasClaim() {
        return claimed;
    }

    public Object owner() {
        return claimed ? owner : null;
    }

    public Object lastOwner() {
        return lastOwner;
    }

    public int priority() {
        return priority;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean smooth() {
        return smooth;
    }
}
