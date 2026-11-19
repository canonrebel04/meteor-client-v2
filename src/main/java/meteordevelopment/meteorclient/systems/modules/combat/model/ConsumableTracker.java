/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Totem / gapple / pearl tracking for one opponent (C16): pops, inferred golden apple use from absorption jumps,
 * pearl cooldown from thrown pearls (vanilla 20 ticks).
 */
public final class ConsumableTracker {
    public static final int PEARL_COOLDOWN_TICKS = 20;
    private int pops;
    private long lastPopTick = Long.MIN_VALUE / 2;
    private int gapsEaten, enchantedGaps;
    private long lastGapTick = Long.MIN_VALUE / 2;
    private long lastPearlTick = Long.MIN_VALUE / 2;
    private double lastAbsorption;

    public void onTotemPop(long tick) {
        pops++;
        lastPopTick = tick;
    }

    /** Feed the current absorption each tick; a jump of >= 4 means a golden apple (>= 16 enchanted). */
    public void onAbsorption(double absorption, long tick) {
        double jump = absorption - lastAbsorption;
        if (jump >= 15.5) {
            enchantedGaps++;
            gapsEaten++;
            lastGapTick = tick;
        } else if (jump >= 3.5) {
            gapsEaten++;
            lastGapTick = tick;
        }
        lastAbsorption = absorption;
    }

    public void onPearlThrown(long tick) {
        lastPearlTick = tick;
    }

    public int pops() {
        return pops;
    }

    public int gapsEaten() {
        return gapsEaten;
    }

    public int enchantedGaps() {
        return enchantedGaps;
    }

    public boolean pearlOnCooldown(long now) {
        return now - lastPearlTick < PEARL_COOLDOWN_TICKS;
    }

    public long ticksSincePop(long now) {
        return now - lastPopTick;
    }

    /** Right after a pop the offhand is empty until they re-totem: a short window where a kill sticks. */
    public boolean inRetotemWindow(long now) {
        return now - lastPopTick <= 4;
    }

    public long ticksSinceGap(long now) {
        return now - lastGapTick;
    }
}
