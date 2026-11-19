/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the escape method (C43-C47, C50) from what we carry, the situation and the server-safety flags.
 * Risky methods (pearl, wind charge, elytra/firework, block placement) are opt-in and OFF by default.
 */
public final class EscapeSelector {
    public enum Method { RUN, PEARL, WIND_CHARGE, ELYTRA, HOLE, BURROW, CLUTCH, LOG, FIGHT_CORNERED }

    public record Flags(boolean allowPearl, boolean allowWindCharge, boolean allowElytra, boolean allowBurrow, boolean allowLog) {
        /** Anticheat-safe defaults: only running and existing safe holes. */
        public static final Flags SAFE = new Flags(false, false, false, false, false);
    }

    public record Situation(
        boolean hasPearl, boolean pearlCooldown, boolean hasWindCharge, boolean wearingElytra, boolean hasFireworks,
        boolean hasBurrowBlock, boolean safeHoleNearby, boolean falling, boolean clutchNeeded, boolean hasWaterBucket,
        boolean cornered, double pursuerDistance, boolean pursuerClosing, double myHp, boolean openSky, boolean canSprint
    ) {
    }

    public record Choice(Method method, List<String> reasons) {
    }

    private EscapeSelector() {
    }

    public static Choice choose(MatchupAssessment.Verdict verdict, Situation s, Flags f) {
        List<String> why = new ArrayList<>();
        if (s.falling() && s.clutchNeeded() && s.hasWaterBucket()) {
            why.add("lethal fall -> water clutch");
            return new Choice(Method.CLUTCH, why);
        }
        boolean urgent = verdict == MatchupAssessment.Verdict.HOPELESS || s.myHp() <= 8 || (s.pursuerClosing() && s.pursuerDistance() < 4);
        if (f.allowLog() && verdict == MatchupAssessment.Verdict.HOPELESS && s.myHp() <= 4 && s.cornered()) {
            why.add("hopeless + cornered + critical HP -> log (opt-in)");
            return new Choice(Method.LOG, why);
        }
        if (urgent && f.allowPearl() && s.hasPearl() && !s.pearlCooldown()) {
            why.add("urgent and pearl available");
            return new Choice(Method.PEARL, why);
        }
        if (urgent && f.allowElytra() && s.wearingElytra() && s.hasFireworks() && s.openSky()) {
            why.add("elytra + fireworks with open sky");
            return new Choice(Method.ELYTRA, why);
        }
        if (urgent && f.allowWindCharge() && s.hasWindCharge()) {
            why.add("wind-charge jump");
            return new Choice(Method.WIND_CHARGE, why);
        }
        if (s.cornered()) {
            if (s.safeHoleNearby()) {
                why.add("cornered, safe hole in reach");
                return new Choice(Method.HOLE, why);
            }
            if (f.allowBurrow() && s.hasBurrowBlock()) {
                why.add("cornered, burrow");
                return new Choice(Method.BURROW, why);
            }
            why.add("cornered with no escape -> fight with best tactics");
            return new Choice(Method.FIGHT_CORNERED, why);
        }
        if (!s.canSprint() && s.safeHoleNearby()) {
            why.add("can't sprint (hunger) -> nearest safe hole");
            return new Choice(Method.HOLE, why);
        }
        why.add(s.canSprint() ? "sprint away" : "walk away (no sprint)");
        return new Choice(Method.RUN, why);
    }
}
