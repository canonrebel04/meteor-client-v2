/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import java.util.Locale;

/** Block hazards for positioning, combos and anti-trap awareness (C29, C35, C37, C38, C88), pure. */
public enum Hazard {
    NONE(0, false), WALL(0, false), DROP(10, true), VOID(100, true), LAVA(60, true), FIRE(25, true), COBWEB(18, true),
    POWDER_SNOW(15, true), SOUL_SAND(4, false), HONEY(5, false), SWEET_BERRY(6, true), MAGMA(8, true), CACTUS(10, true),
    WATER(0, false), TRAPDOOR(3, false), WITHER_ROSE(10, true);

    /** Cost for us standing in / next to it. */
    public final double cost;
    /** Worth knocking an enemy into (combo extension). */
    public final boolean comboTarget;

    Hazard(double cost, boolean comboTarget) {
        this.cost = cost;
        this.comboTarget = comboTarget;
    }

    /** Classify a block id ("minecraft:cobweb"). */
    public static Hazard ofBlock(String id) {
        if (id == null) return NONE;
        String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return switch (p) {
            case "lava" -> LAVA;
            case "fire", "soul_fire", "campfire", "soul_campfire" -> FIRE;
            case "cobweb" -> COBWEB;
            case "powder_snow" -> POWDER_SNOW;
            case "soul_sand" -> SOUL_SAND;
            case "honey_block" -> HONEY;
            case "sweet_berry_bush" -> SWEET_BERRY;
            case "magma_block" -> MAGMA;
            case "cactus" -> CACTUS;
            case "water", "bubble_column" -> WATER;
            case "wither_rose" -> WITHER_ROSE;
            default -> p.endsWith("_trapdoor") ? TRAPDOOR : NONE;
        };
    }

    /** True for a trap that roots or slows (anti-trap, C38). */
    public boolean trap() {
        return this == COBWEB || this == POWDER_SNOW || this == SOUL_SAND || this == HONEY || this == SWEET_BERRY || this == TRAPDOOR;
    }
}
