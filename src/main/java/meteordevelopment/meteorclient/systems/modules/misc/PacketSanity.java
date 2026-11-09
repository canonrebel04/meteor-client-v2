/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

/**
 * Pure validation rules for {@link PacketGuard}. Limits mirror vanilla's own client/server checks
 * (ServerGamePacketListenerImpl / packet codecs) so that buggy module output never trips a protocol kick.
 */
public final class PacketSanity {
    private PacketSanity() {}

    /** Vanilla server rejects |x|,|z| beyond this as invalid ("Invalid move player packet received"). */
    public static final double MAX_HORIZONTAL = 3.0E7;
    /** Vanilla vertical clamp. */
    public static final double MAX_VERTICAL = 2.0E7;
    public static final int MAX_CHAT_LENGTH = 256;
    public static final int MAX_COMMAND_LENGTH = 32767;
    public static final int MAX_SIGN_LINE_LENGTH = 384;

    public static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    public static boolean validPosition(double x, double y, double z) {
        return finite(x) && finite(y) && finite(z)
            && Math.abs(x) <= MAX_HORIZONTAL && Math.abs(z) <= MAX_HORIZONTAL && Math.abs(y) <= MAX_VERTICAL;
    }

    public static boolean validRotationValues(float yaw, float pitch) {
        return finite(yaw) && finite(pitch);
    }

    public static boolean pitchInRange(float pitch) {
        return pitch >= -90f && pitch <= 90f;
    }

    public static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }

    public static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }

    public static boolean validLength(String s, int max) {
        return s == null || s.length() <= max;
    }

    /** Fixed one-second window counter for the generic packet-rate cap. */
    public static final class RateLimiter {
        private long windowStart = Long.MIN_VALUE;
        private int count;

        /** @return true if this packet is within {@code maxPerSecond} for the current window. */
        public boolean tryAcquire(long nowMs, int maxPerSecond) {
            if (nowMs - windowStart >= 1000 || windowStart == Long.MIN_VALUE) {
                windowStart = nowMs;
                count = 0;
            }
            if (count >= maxPerSecond) return false;
            count++;
            return true;
        }
    }

    /**
     * Emulates vanilla's chat spam counter (+20 per chat/command, -1 per tick, kick above 200) so the
     * client never sends the message that would cross the threshold.
     */
    public static final class ChatThrottle {
        public static final int PER_MESSAGE = 20;
        public static final int KICK_THRESHOLD = 200;
        private int counter;

        public void tick() {
            if (counter > 0) counter--;
        }

        public boolean tryAcquire(int safetyMargin) {
            if (counter + PER_MESSAGE > KICK_THRESHOLD - Math.max(0, safetyMargin)) return false;
            counter += PER_MESSAGE;
            return true;
        }

        public int counter() {
            return counter;
        }
    }
}
