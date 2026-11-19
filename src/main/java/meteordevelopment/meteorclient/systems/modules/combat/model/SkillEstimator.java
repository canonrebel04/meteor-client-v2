/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Opponent skill estimate (C12) from observed behaviour: how close to full charge they swing, crit rate,
 * strafing, reaction time to our approach and pearl use. Exponential moving averages, so the estimate settles
 * within a few exchanges and adapts if the opponent changes. Output in [0, 1], 0.5 = unknown/average.
 */
public final class SkillEstimator {
    private static final double A = 0.2;
    private double chargeDiscipline = 0.5; // swings at >=0.9 charge
    private double critRate = 0.3;
    private double strafe = 0.3;
    private double reaction = 0.5;      // 1 = instant reaction
    private double pearlSkill = 0.0;
    private int samples;

    /** A swing seen with the attacker's estimated cooldown progress [0..1]. */
    public void onSwing(double cooldownProgress) {
        chargeDiscipline = ema(chargeDiscipline, cooldownProgress >= 0.9 ? 1.0 : 0.0);
        samples++;
    }

    public void onHitLanded(boolean critical) {
        critRate = ema(critRate, critical ? 1.0 : 0.0);
        samples++;
    }

    /** Lateral speed (blocks/tick, perpendicular to the line to us) while within 6 blocks. */
    public void onStrafeSample(double lateralSpeed) {
        strafe = ema(strafe, Math.min(1.0, Math.abs(lateralSpeed) / 0.25));
    }

    /** Ticks from our approach/attack until they turned to face us (lower = better). */
    public void onReaction(int ticks) {
        reaction = ema(reaction, Math.max(0.0, 1.0 - ticks / 10.0));
        samples++;
    }

    public void onPearlUsed(boolean towardsUs) {
        pearlSkill = ema(pearlSkill, towardsUs ? 1.0 : 0.6);
    }

    private static double ema(double prev, double x) {
        return prev + A * (x - prev);
    }

    public int samples() {
        return samples;
    }

    /** 0..1; stays near 0.5 until there are some observations. */
    public double skill() {
        double raw = 0.30 * chargeDiscipline + 0.25 * critRate + 0.20 * strafe + 0.15 * reaction + 0.10 * pearlSkill;
        double confidence = Math.min(1.0, samples / 12.0);
        return 0.5 + (raw - 0.5) * confidence;
    }

    /** Multiplier on the opponent's modelled DPS: 0.8 (poor) .. 1.3 (skilled). */
    public double dpsMultiplier() {
        return 0.8 + 0.5 * skill();
    }

    /** Seed from persisted learning (C94-lite): a prior skill pulls the estimate before samples arrive. */
    public void seed(double priorSkill) {
        double p = Math.max(0.0, Math.min(1.0, priorSkill));
        chargeDiscipline = critRate = strafe = reaction = p;
        samples = Math.max(samples, 6);
    }
}
