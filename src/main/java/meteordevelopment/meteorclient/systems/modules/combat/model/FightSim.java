/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.Random;

/**
 * Calibration harness (C95): a headless, seeded tick simulation of a 1v1 melee trade using the same damage model as
 * {@link MatchupAssessment} (armor, toughness, protection, resistance, attack speed, totems, gaps), plus imperfect
 * aim and timing jitter. Used by tests to check that verdict thresholds match simulated win rates, and to suggest a
 * WIN threshold for a target win rate. Pure.
 */
public final class FightSim {
    public record Outcome(double winRate, double avgTicks) {
    }

    /** Hit chance and swing jitter (ticks) of a fighter. */
    public record Skill(double hitChance, int jitterTicks) {
        public static final Skill AVERAGE = new Skill(0.8, 3);
    }

    public static final int MAX_TICKS = 20 * 120;

    private FightSim() {
    }

    private static final class Fighter {
        double hp, absorption;
        int totems;
        boolean gap;
        double hit;
        int period, cooldown;
        Skill skill;
        int regenTicks;
    }

    private static Fighter fighter(MatchupAssessment.KitSnapshot k, MatchupAssessment.KitSnapshot other, Skill skill, Random r) {
        Fighter f = new Fighter();
        f.hp = Math.max(1, k.health());
        f.absorption = k.absorption();
        f.totems = k.totems();
        f.gap = k.hasGaps();
        double raw = EffectiveDamage.rawAttackDamage(k.rawWeaponDamage(), 0, k.strengthLevel(), k.weaknessLevel(), false);
        f.hit = EffectiveDamage.effectiveHitDamage(raw, other.armor(), other.toughness(), other.protectionEpf(), other.resistanceLevel());
        if (other.blocking()) f.hit *= 0.5;
        f.period = Math.max(1, (int) Math.round(20.0 / Math.max(0.1, k.attackSpeed())));
        f.cooldown = r.nextInt(f.period + 1);
        f.skill = skill;
        return f;
    }

    /** Apply damage; returns true when the fighter died (no totem left). */
    private static boolean damage(Fighter f, double d) {
        double fromAbs = Math.min(f.absorption, d);
        f.absorption -= fromAbs;
        f.hp -= d - fromAbs;
        if (f.hp > 0) return false;
        if (f.totems > 0) {
            f.totems--;
            f.hp = 1;
            f.absorption = Math.max(f.absorption, 4);
            f.regenTicks = 40 * 2;
            return false;
        }
        return true;
    }

    private static void sustain(Fighter f) {
        if (f.gap && f.hp <= 10) {
            f.gap = false;
            f.absorption = Math.max(f.absorption, 4);
            f.regenTicks = 100;
        }
        if (f.regenTicks > 0) {
            f.regenTicks--;
            if (f.regenTicks % 10 == 0) f.hp = Math.min(20, f.hp + 1);
        }
    }

    /** One fight; true when {@code me} wins. Ticks used written to {@code ticksOut[0]}. */
    public static boolean fight(MatchupAssessment.KitSnapshot me, MatchupAssessment.KitSnapshot them, Skill mySkill, Skill theirSkill,
                                Random r, int[] ticksOut) {
        Fighter a = fighter(me, them, mySkill, r);
        Fighter b = fighter(them, me, theirSkill, r);
        for (int t = 0; t < MAX_TICKS; t++) {
            sustain(a);
            sustain(b);
            boolean aDead = false, bDead = false;
            if (--a.cooldown <= 0) {
                a.cooldown = a.period + r.nextInt(a.skill.jitterTicks() + 1);
                if (r.nextDouble() < a.skill.hitChance()) bDead = damage(b, a.hit);
            }
            if (--b.cooldown <= 0) {
                b.cooldown = b.period + r.nextInt(b.skill.jitterTicks() + 1);
                if (r.nextDouble() < b.skill.hitChance()) aDead = damage(a, b.hit);
            }
            if (aDead || bDead) {
                ticksOut[0] = t;
                // simultaneous: coin flip
                return bDead && (!aDead || r.nextBoolean());
            }
        }
        ticksOut[0] = MAX_TICKS;
        return a.hp + a.absorption >= b.hp + b.absorption;
    }

    public static Outcome run(MatchupAssessment.KitSnapshot me, MatchupAssessment.KitSnapshot them, Skill mySkill, Skill theirSkill,
                              int fights, long seed) {
        Random r = new Random(seed);
        int wins = 0;
        long ticks = 0;
        int[] t = new int[1];
        for (int i = 0; i < fights; i++) {
            if (fight(me, them, mySkill, theirSkill, r, t)) wins++;
            ticks += t[0];
        }
        return new Outcome(wins / (double) fights, ticks / (double) fights);
    }

    /**
     * Suggested kill-ratio threshold for WIN: the smallest model kill ratio whose simulated win rate (averaged over
     * the given opponent kits at equal skill) reaches {@code targetWinRate}. Scans by varying our weapon damage.
     */
    public static double suggestWinRatio(MatchupAssessment.KitSnapshot me, MatchupAssessment.KitSnapshot them, double targetWinRate,
                                         int fights, long seed) {
        double best = Double.NaN;
        for (double dmg = 1; dmg <= 20; dmg += 0.25) {
            MatchupAssessment.KitSnapshot k = withDamage(me, dmg);
            Outcome o = run(k, them, Skill.AVERAGE, Skill.AVERAGE, fights, seed);
            if (o.winRate() >= targetWinRate) {
                best = MatchupAssessment.evaluate(k, them, null, MatchupAssessment.AggressionProfile.BALANCED).killRatio();
                break;
            }
        }
        return best;
    }

    public static MatchupAssessment.KitSnapshot withDamage(MatchupAssessment.KitSnapshot k, double dmg) {
        return new MatchupAssessment.KitSnapshot(k.health(), k.absorption(), k.armor(), k.toughness(), k.protectionEpf(), dmg,
            k.attackSpeed(), k.hasWeapon(), k.hasShield(), k.blocking(), k.totems(), k.hasGaps(), k.resistanceLevel(),
            k.strengthLevel(), k.weaknessLevel(), k.isPlayer());
    }
}
