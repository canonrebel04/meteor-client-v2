/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/** Damage-type awareness (C56): dominant incoming type -> response (protection piece / shield / cover). */
public final class DamageTypeAdvisor {
    public enum Type { MELEE, PROJECTILE, BLAST, FIRE, MAGIC, FALL }

    public enum Response { SHIELD, COVER, BLAST_PROT, PROJ_PROT, FIRE_PROT, FIRE_RES, DISTANCE, FEATHER_OR_CLUTCH }

    private DamageTypeAdvisor() {
    }

    /** Highest weighted type over the recent window; index by Type ordinal. */
    public static Type dominant(double[] recentByType) {
        Type best = Type.MELEE;
        double bv = -1;
        for (Type t : Type.values()) {
            double v = t.ordinal() < recentByType.length ? recentByType[t.ordinal()] : 0;
            if (v > bv) {
                bv = v;
                best = t;
            }
        }
        return best;
    }

    public static Response respond(Type t, boolean haveShield) {
        return switch (t) {
            case MELEE -> haveShield ? Response.SHIELD : Response.DISTANCE;
            case PROJECTILE -> haveShield ? Response.SHIELD : Response.COVER;
            case BLAST -> Response.BLAST_PROT;
            case FIRE -> Response.FIRE_RES;
            case MAGIC -> Response.DISTANCE;
            case FALL -> Response.FEATHER_OR_CLUTCH;
        };
    }
}
