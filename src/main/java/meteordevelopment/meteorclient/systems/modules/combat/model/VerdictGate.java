/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Sticky verdict (C9 hysteresis, needed by the P1 flee logic): a worse verdict is adopted after
 * {@code worsenPasses} agreeing evaluations, a better one only after {@code improvePasses}, so the FSM never
 * flaps between fleeing and engaging on a single noisy evaluation.
 */
public final class VerdictGate {
    private final int worsenPasses, improvePasses;
    private MatchupAssessment.Verdict current;
    private MatchupAssessment.Verdict pending;
    private int pendingCount;

    public VerdictGate(int worsenPasses, int improvePasses) {
        this.worsenPasses = Math.max(1, worsenPasses);
        this.improvePasses = Math.max(1, improvePasses);
    }

    public MatchupAssessment.Verdict offer(MatchupAssessment.Verdict v) {
        if (v == null) return current;
        if (current == null) {
            current = v;
            return current;
        }
        if (v == current) {
            pending = null;
            pendingCount = 0;
            return current;
        }
        if (v != pending) {
            pending = v;
            pendingCount = 0;
        }
        pendingCount++;
        boolean worse = v.ordinal() > current.ordinal();
        // jumping straight to HOPELESS (e.g. weapon broke, totem gone) is adopted at once
        if (v == MatchupAssessment.Verdict.HOPELESS || pendingCount >= (worse ? worsenPasses : improvePasses)) {
            current = v;
            pending = null;
            pendingCount = 0;
        }
        return current;
    }

    public MatchupAssessment.Verdict current() {
        return current;
    }

    public void reset() {
        current = null;
        pending = null;
        pendingCount = 0;
    }
}
