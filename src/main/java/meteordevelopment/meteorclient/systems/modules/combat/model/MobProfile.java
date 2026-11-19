/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-mob combat profiles (C20): reach, attack windup, ranged band, explosion/special behaviour and the distance
 * to keep when fleeing. Unknown mobs get a generic melee profile.
 */
public record MobProfile(
    String id,
    double reach,
    int windupTicks,
    boolean ranged,
    double rangedRange,
    boolean explosive,
    double explosionRadius,
    double safeDistance,
    boolean boss,
    String note
) {
    private static final Map<String, MobProfile> PROFILES = new HashMap<>();
    public static final MobProfile GENERIC = new MobProfile("generic", 2.0, 0, false, 0, false, 0, 8, false, "melee");

    private static void add(String id, double reach, int windup, boolean ranged, double rangedRange, boolean explosive,
                            double radius, double safe, boolean boss, String note) {
        PROFILES.put("minecraft:" + id, new MobProfile("minecraft:" + id, reach, windup, ranged, rangedRange, explosive, radius, safe, boss, note));
    }

    static {
        add("zombie", 2.0, 0, false, 0, false, 0, 6, false, "melee");
        add("husk", 2.0, 0, false, 0, false, 0, 6, false, "melee, hunger");
        add("drowned", 2.0, 0, true, 15, false, 0, 10, false, "trident when holding one");
        add("skeleton", 2.0, 20, true, 15, false, 0, 18, false, "bow; break LOS");
        add("stray", 2.0, 20, true, 15, false, 0, 18, false, "slowness arrows");
        add("bogged", 2.0, 20, true, 15, false, 0, 18, false, "poison arrows");
        add("creeper", 0.0, 30, false, 0, true, 3.0, 7, false, "fuse 30 ticks; back off or shield");
        add("spider", 2.0, 0, false, 0, false, 0, 8, false, "climbs walls");
        add("cave_spider", 2.0, 0, false, 0, false, 0, 8, false, "poison");
        add("enderman", 2.0, 0, false, 0, false, 0, 10, false, "teleports; water/roof");
        add("blaze", 0.0, 30, true, 16, false, 0, 20, false, "fireball volleys");
        add("ghast", 0.0, 20, true, 64, true, 1.0, 32, false, "fireball; reflect or cover");
        add("piglin", 2.0, 0, true, 8, false, 0, 10, false, "crossbow; gold armor pacifies");
        add("piglin_brute", 2.0, 0, false, 0, false, 0, 14, false, "axe, very high damage");
        add("hoglin", 2.0, 0, false, 0, false, 0, 12, false, "knock-up");
        add("zoglin", 2.0, 0, false, 0, false, 0, 12, false, "knock-up");
        add("vindicator", 2.0, 0, false, 0, false, 0, 12, false, "axe");
        add("evoker", 0.0, 40, true, 12, false, 0, 16, false, "fangs + vexes; kill fast or leave");
        add("pillager", 2.0, 20, true, 16, false, 0, 18, false, "crossbow");
        add("ravager", 3.0, 10, false, 0, false, 0, 14, false, "roar knockback");
        add("witch", 0.0, 0, true, 8, false, 0, 12, false, "potions");
        add("guardian", 0.0, 60, true, 15, false, 0, 16, false, "laser charge");
        add("elder_guardian", 0.0, 60, true, 15, false, 0, 20, true, "mining fatigue");
        add("warden", 3.0, 0, true, 15, false, 0, 32, true, "sonic boom; never fight, stealth away");
        add("wither", 0.0, 0, true, 32, true, 1.0, 40, true, "skulls; wither effect");
        add("ender_dragon", 4.0, 0, true, 64, false, 0, 30, true, "breath + charge");
        add("breeze", 0.0, 20, true, 16, false, 0, 16, false, "wind charges knock up");
        add("slime", 1.5, 0, false, 0, false, 0, 6, false, "splits");
        add("magma_cube", 1.5, 0, false, 0, false, 0, 6, false, "splits; fire");
        add("phantom", 1.5, 0, false, 0, false, 0, 8, false, "dives; get under a roof");
        add("silverfish", 1.5, 0, false, 0, false, 0, 6, false, "swarm");
        add("vex", 1.5, 0, false, 0, false, 0, 10, false, "flies through walls");
        add("shulker", 0.0, 20, true, 16, false, 0, 16, false, "levitation");
        add("wither_skeleton", 2.4, 0, false, 0, false, 0, 10, false, "wither effect; taller reach");
        add("iron_golem", 2.5, 0, false, 0, false, 0, 12, false, "launches upward");
    }

    public static MobProfile of(String entityId) {
        return PROFILES.getOrDefault(entityId, GENERIC);
    }

    public static boolean known(String entityId) {
        return PROFILES.containsKey(entityId);
    }

    /** Distance at which this mob can hurt us right now. */
    public double threatRange() {
        return explosive && !ranged ? Math.max(reach, explosionRadius + 1.0) : Math.max(reach, ranged ? rangedRange : 0);
    }
}
