/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Verdict-driven soft-log ladder (C50): DISENGAGE -> SHELTER -> HEAL -> LOG. Each rung is only climbed after the
 * previous one had time to work, so we never insta-log (anticheat-safe timing) and logging is opt-in.
 */
public final class DisengageLadder {
    public enum Rung { NONE, DISENGAGE, SHELTER, HEAL, LOG }

    private Rung rung = Rung.NONE;
    private long rungSince;
    public int minTicksPerRung = 40;

    public Rung update(long now, MatchupAssessment.Verdict verdict, boolean safe, boolean sheltered, double hp, boolean logAllowed) {
        if (verdict == null || !verdict.shouldFlee() || safe) {
            set(Rung.NONE, now);
            return rung;
        }
        boolean dwellOk = now - rungSince >= minTicksPerRung;
        switch (rung) {
            case NONE -> set(Rung.DISENGAGE, now);
            case DISENGAGE -> {
                if (dwellOk) set(Rung.SHELTER, now);
            }
            case SHELTER -> {
                if (sheltered || dwellOk) set(Rung.HEAL, now);
            }
            case HEAL -> {
                if (dwellOk && logAllowed && verdict == MatchupAssessment.Verdict.HOPELESS && hp <= 6) set(Rung.LOG, now);
            }
            case LOG -> {
            }
        }
        return rung;
    }

    private void set(Rung r, long now) {
        if (r != rung) {
            rung = r;
            rungSince = now;
        }
    }

    public Rung rung() {
        return rung;
    }
}
