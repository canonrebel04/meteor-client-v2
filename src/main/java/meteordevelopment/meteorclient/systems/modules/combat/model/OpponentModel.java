/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything learned about one opponent (C11-C19 aggregate), plus the matchup corrections derived from it.
 */
public final class OpponentModel {
    public final String id;
    public final boolean player;
    public final SkillEstimator skill = new SkillEstimator();
    public final MovementPredictor motion = new MovementPredictor();
    public final HitLedger ledger = new HitLedger();
    public final ConsumableTracker consumables = new ConsumableTracker();
    public final WeaponSwapDetector swaps = new WeaponSwapDetector();
    public final PingModel ping = new PingModel();
    public OpponentGear gear;
    public Intent intent = Intent.UNKNOWN;
    public MobProfile mob = MobProfile.GENERIC;
    public long lastSeenTick;

    public OpponentModel(String id, boolean player) {
        this.id = id;
        this.player = player;
    }

    /** Matchup corrections from the model (multipliers and explanation). */
    public record Corrections(double theirDpsMult, double myDpsMult, double theirArmorMult, double ratioMult, List<String> notes) {
        public static final Corrections NONE = new Corrections(1, 1, 1, 1, List.of());
    }

    public Corrections corrections(long now) {
        return corrections(now, 1.0);
    }

    /** @param learnedBias kill-ratio bias from {@link OpponentMemory#ratioBias} (1 = none). */
    public Corrections corrections(long now, double learnedBias) {
        List<String> notes = new ArrayList<>();
        double their = 1.0, mine = 1.0, armor = 1.0, ratio = 1.0;
        if (player) {
            double s = skill.dpsMultiplier();
            their *= s;
            if (Math.abs(s - 1.05) > 0.05) notes.add(String.format(Locale.ROOT, "skill %.2f", skill.skill()));
        }
        double im = intent.threatMultiplier();
        if (im != 1.0) {
            their *= im;
            notes.add("intent " + intent);
        }
        WeaponSwapDetector.Expectation e = swaps.expectation(now, 40);
        if (e == WeaponSwapDetector.Expectation.SHIELD_BREAK) notes.add("axe out: shield unreliable");
        if (e == WeaponSwapDetector.Expectation.MACE_SMASH) {
            their *= 1.25;
            notes.add("mace out");
        }
        if (gear != null) {
            double rem = ArmorDurability.armorRemainingAfter(gear.armor(), 20, 0.01);
            if (rem < 0.999) {
                armor *= rem;
                notes.add(String.format(Locale.ROOT, "armor wearing out (%.0f%% left soon)", rem * 100));
            }
        }
        if (consumables.inRetotemWindow(now)) {
            mine *= 1.3;
            notes.add("re-totem window");
        }
        double corr = ledger.observedCorrection();
        if (corr != 1.0) {
            ratio *= corr;
            notes.add(String.format(Locale.ROOT, "observed trades x%.2f", corr));
        }
        if (Math.abs(learnedBias - 1.0) > 0.01) {
            ratio *= learnedBias;
            notes.add(String.format(Locale.ROOT, "history x%.2f", learnedBias));
        }
        return new Corrections(their, mine, armor, ratio, notes);
    }
}
