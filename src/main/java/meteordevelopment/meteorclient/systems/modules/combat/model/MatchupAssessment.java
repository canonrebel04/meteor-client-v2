/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Pure-Java headless Fight-or-Flight Gate & Matchup Assessment (Enhancements C1, C3, C4, C5, C6, C7, C10).
 * Fixes the unarmed-vs-netherite fight bug by computing real effective-damage TTK in both directions,
 * enforcing a hard unarmed-vs-armored flight verdict, and producing an explainable 4-level verdict.
 */
public final class MatchupAssessment {

    public enum Verdict {
        WIN,
        EVEN,
        LOSE,
        HOPELESS;

        public boolean shouldFlee() {
            return this == LOSE || this == HOPELESS;
        }
    }

    public enum AggressionProfile {
        COWARD(1.35, 0.95),
        BALANCED(1.10, 0.78),
        BERSERK(0.85, 0.55),
        HUNTER(0.95, 0.65);

        /** Minimum killRatio (myEffectiveTTK / theirEffectiveTTK) for WIN. */
        public final double winRatio;
        /** Minimum killRatio for EVEN (below this -> LOSE / HOPELESS). */
        public final double evenRatio;

        AggressionProfile(double winRatio, double evenRatio) {
            this.winRatio = winRatio;
            this.evenRatio = evenRatio;
        }
    }

    public record KitSnapshot(
        double health,
        double absorption,
        double armor,
        double toughness,
        int protectionEpf,
        double rawWeaponDamage,
        double attackSpeed,
        boolean hasWeapon,
        boolean hasShield,
        boolean blocking,
        int totems,
        boolean hasGaps,
        int resistanceLevel,
        int strengthLevel,
        int weaknessLevel,
        boolean isPlayer
    ) {
        public double effectiveHp() {
            double base = Math.max(0.0, health) + Math.max(0.0, absorption);
            double withTotems = base + Math.max(0, totems) * 8.0;
            return hasGaps ? withTotems * 1.25 : withTotems;
        }
    }

    public record EscapeProfile(
        boolean hasPearl,
        boolean hasWindCharge,
        boolean hasElytra,
        boolean hasSpeed,
        boolean cornered,
        double distance,
        boolean hasLineOfSight
    ) {
        public double escapeScore() {
            double score = 0.2;
            if (hasPearl) score += 0.30;
            if (hasElytra) score += 0.30;
            if (hasWindCharge) score += 0.15;
            if (hasSpeed) score += 0.15;
            if (distance > 8.0) score += 0.10;
            if (!hasLineOfSight) score += 0.10;
            if (cornered) score = 0.0;
            return Math.min(1.0, score);
        }
    }

    public record Result(
        Verdict verdict,
        double confidence,
        double myDps,
        double theirDps,
        double myTtk,
        double theirTtk,
        double killRatio,
        double matchupThreat,
        boolean mustFightBecauseCornered,
        String reason
    ) {
        public boolean shouldEngage() {
            return !verdict.shouldFlee() || mustFightBecauseCornered;
        }

        public String explain() {
            return String.format(
                "Verdict=%s (conf=%.2f, ratio=%.2f, myDPS=%.2f, theirDPS=%.2f, myTTK=%.1fs, theirTTK=%.1fs, threat=%.2f%s) -> %s",
                verdict, confidence, killRatio, myDps, theirDps, myTtk, theirTtk, matchupThreat,
                mustFightBecauseCornered ? ", CORNERED" : "", reason
            );
        }
    }

    private MatchupAssessment() {
    }

    /**
     * Evaluates the fight-or-flight matchup between {@code me} and {@code target}.
     */
    public static Result evaluate(KitSnapshot me, KitSnapshot target, EscapeProfile escape, AggressionProfile profile) {
        return evaluate(me, target, escape, profile, null);
    }

    /**
     * As {@link #evaluate(KitSnapshot, KitSnapshot, EscapeProfile, AggressionProfile)} with opponent-model
     * corrections (P1: skill C12, intent C14, swaps C17, durability C18, observed trades C15).
     */
    public static Result evaluate(KitSnapshot me, KitSnapshot target, EscapeProfile escape, AggressionProfile profile,
                                  OpponentModel.Corrections corrections) {
        OpponentModel.Corrections corr = corrections != null ? corrections : OpponentModel.Corrections.NONE;
        if (me == null || target == null) {
            return new Result(Verdict.EVEN, 0.5, 1.0, 1.0, 20.0, 20.0, 1.0, 0.3, false, "missing snapshot");
        }
        AggressionProfile prof = profile != null ? profile : AggressionProfile.BALANCED;
        EscapeProfile esc = escape != null ? escape : new EscapeProfile(false, false, false, false, false, 6.0, true);

        double myRaw = EffectiveDamage.rawAttackDamage(me.rawWeaponDamage(), 0, me.strengthLevel(), me.weaknessLevel(), false);
        double theirRaw = EffectiveDamage.rawAttackDamage(target.rawWeaponDamage(), 0, target.strengthLevel(), target.weaknessLevel(), false);

        double myHit = EffectiveDamage.effectiveHitDamage(myRaw, target.armor() * corr.theirArmorMult(), target.toughness(), target.protectionEpf(), target.resistanceLevel());
        double theirHit = EffectiveDamage.effectiveHitDamage(theirRaw, me.armor(), me.toughness(), me.protectionEpf(), me.resistanceLevel());

        double myDps = EffectiveDamage.effectiveDps(myHit, me.attackSpeed(), target.blocking()) * corr.myDpsMult();
        double theirDps = EffectiveDamage.effectiveDps(theirHit, target.attackSpeed(), me.blocking()) * corr.theirDpsMult();

        double myEhp = Math.max(1.0, me.effectiveHp());
        double theirEhp = Math.max(1.0, target.effectiveHp());

        // Time for me to kill them vs time for them to kill me
        double timeToKillThem = theirEhp / myDps;
        double timeToKillMe = myEhp / theirDps;
        double killRatio = timeToKillMe / Math.max(0.05, timeToKillThem) * corr.ratioMult();

        // Hard unarmed-vs-armored / severe gear-gap check (fixes unarmed vs full netherite bug)
        if (!me.hasWeapon() && target.isPlayer() && target.armor() >= 10.0) {
            double threat = Math.min(1.0, 0.85 + (target.armor() / 20.0) * 0.15);
            return new Result(
                Verdict.HOPELESS, 0.98, myDps, theirDps, timeToKillThem, timeToKillMe, killRatio,
                threat, esc.cornered(),
                "unarmed vs heavily armored player (armor=" + (int) target.armor() + ")"
            );
        }
        if (!me.hasWeapon() && me.armor() <= 2.0 && (target.armor() >= 8.0 || target.hasWeapon())) {
            double threat = 0.88;
            return new Result(
                Verdict.HOPELESS, 0.95, myDps, theirDps, timeToKillThem, timeToKillMe, killRatio,
                threat, esc.cornered(),
                "unarmed & unarmored vs geared opponent"
            );
        }

        // Unarmed vs unarmed & unarmored -> fair/winnable
        Verdict verdict;
        String reason;
        if (killRatio >= prof.winRatio) {
            verdict = Verdict.WIN;
            reason = "favorable TTK ratio";
        } else if (killRatio >= prof.evenRatio) {
            verdict = Verdict.EVEN;
            reason = "even TTK ratio";
        } else if (killRatio >= prof.evenRatio * 0.55) {
            verdict = Verdict.LOSE;
            reason = "unfavorable TTK ratio";
        } else {
            verdict = Verdict.HOPELESS;
            reason = "severely outmatched TTK ratio";
        }

        // Critical health without totems downgrades verdict
        if (me.health() + me.absorption() <= 6.0 && me.totems() == 0 && verdict != Verdict.HOPELESS) {
            verdict = Verdict.HOPELESS;
            reason = "critically low HP with no totems";
        }

        double confidence = Math.min(0.99, 0.55 + Math.min(0.44, Math.abs(Math.log(Math.max(0.05, killRatio))) * 0.35));
        // Matchup threat in [0, 1] so computeThreatLevel() never reads "safe" next to a stronger opponent
        double matchupThreat = switch (verdict) {
            case WIN -> 0.15;
            case EVEN -> 0.45;
            case LOSE -> 0.78;
            case HOPELESS -> 0.95;
        };

        if (!corr.notes().isEmpty()) reason = reason + " [" + String.join(", ", corr.notes()) + "]";
        return new Result(
            verdict, confidence, myDps, theirDps, timeToKillThem, timeToKillMe,
            killRatio, matchupThreat, esc.cornered() && verdict.shouldFlee(), reason
        );
    }

    /**
     * Flee completion check (Enhancement C42): retreat/flee only ends when either the matchup
     * is no longer LOSE/HOPELESS, or the player reached safe distance with broken LOS and the
     * pursuer is not closing in.
     */
    public static boolean isFleeComplete(Verdict currentVerdict, double distance, double safeDistance,
                                         int ticksWithoutLos, boolean pursuerClosingIn) {
        if (currentVerdict != null && !currentVerdict.shouldFlee()) {
            return true;
        }
        return distance >= safeDistance && ticksWithoutLos >= 20 && !pursuerClosingIn;
    }
}
