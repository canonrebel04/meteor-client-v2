/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.Locale;

/** Held-item classes that matter for combat decisions (C11, C17). */
public enum WeaponKind {
    NONE, SWORD, AXE, MACE, SPEAR, TRIDENT, BOW, CROSSBOW, SHIELD, TOTEM, PEARL, WIND_CHARGE, FOOD, POTION, BLOCK, CRYSTAL, OTHER;

    public boolean melee() {
        return this == SWORD || this == AXE || this == MACE || this == SPEAR || this == TRIDENT;
    }

    public boolean ranged() {
        return this == BOW || this == CROSSBOW || this == TRIDENT;
    }

    /** Classify a registry id ("minecraft:netherite_sword"). */
    public static WeaponKind fromItemId(String id) {
        if (id == null || id.isEmpty() || id.endsWith(":air")) return NONE;
        String p = id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (p.endsWith("_sword")) return SWORD;
        if (p.endsWith("_axe") && !p.endsWith("pickaxe")) return AXE;
        if (p.equals("mace")) return MACE;
        if (p.endsWith("_spear") || p.equals("spear")) return SPEAR;
        if (p.equals("trident")) return TRIDENT;
        if (p.equals("bow")) return BOW;
        if (p.equals("crossbow")) return CROSSBOW;
        if (p.equals("shield")) return SHIELD;
        if (p.equals("totem_of_undying")) return TOTEM;
        if (p.equals("ender_pearl")) return PEARL;
        if (p.equals("wind_charge")) return WIND_CHARGE;
        if (p.equals("end_crystal")) return CRYSTAL;
        if (p.contains("golden_apple") || p.endsWith("_stew") || p.equals("cooked_beef") || p.equals("bread")) return FOOD;
        if (p.contains("potion")) return POTION;
        if (p.endsWith("obsidian") || p.endsWith("_block") || p.endsWith("planks") || p.equals("cobblestone")) return BLOCK;
        return OTHER;
    }
}
