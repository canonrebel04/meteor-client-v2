/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player.smooth;

/**
 * Rotation smoothing profile (user decision: Legit-Smooth by default, Fast opt-in). Mirrors Baritone's
 * {@code lookProfile} setting, which does the actual smoothing when Baritone is installed.
 */
public enum SmoothProfile {
    LegitSmooth("LEGIT_SMOOTH", 2.2),
    Balanced("BALANCED", 1.6),
    Fast("FAST", 1.0);

    /** Value of Baritone's {@code lookProfile} setting. */
    public final String baritoneName;
    /** Spring time constant (ticks) for the no-Baritone fallback. */
    public final double fallbackTau;

    SmoothProfile(String baritoneName, double fallbackTau) {
        this.baritoneName = baritoneName;
        this.fallbackTau = fallbackTau;
    }
}
