/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Regear plan (C60): which stash slots to take so the kit meets {@link RespawnPolicy.Requirement} again: best
 * armor for each empty slot, a weapon, totems / golden apples / food up to targets, plus user-listed extras.
 */
public final class RegearPlan {
    /** One container slot: item id and count. */
    public record Stack(int slot, String itemId, int count) {
    }

    /** What we have now. */
    public record Have(boolean[] armorSlots, boolean weapon, int totems, int gaps, int food, Set<String> carriedIds) {
    }

    public record Targets(int totems, int gaps, int food) {
        public static final Targets DEFAULT = new Targets(4, 8, 32);
    }

    private RegearPlan() {
    }

    /** Armor slot index (feet 0, legs 1, chest 2, head 3) or -1. */
    public static int armorSlot(String id) {
        String p = path(id);
        if (p.endsWith("_boots")) return 0;
        if (p.endsWith("_leggings")) return 1;
        if (p.endsWith("_chestplate")) return 2;
        if (p.endsWith("_helmet")) return 3;
        return -1;
    }

    public static boolean food(String id) {
        String p = path(id);
        return p.equals("golden_carrot") || p.startsWith("cooked_") || p.equals("bread") || p.equals("baked_potato")
            || p.equals("pumpkin_pie") || p.endsWith("_stew");
    }

    static String path(String id) {
        return id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    public static int weaponRank(String id) {
        WeaponKind k = WeaponKind.fromItemId(id);
        if (k != WeaponKind.SWORD && k != WeaponKind.AXE && k != WeaponKind.MACE) return -1;
        return OpponentGear.Tier.fromItemId(id).rank * 2 + (k == WeaponKind.SWORD ? 1 : 0) + (k == WeaponKind.MACE ? 3 : 0);
    }

    public static List<Integer> plan(List<Stack> container, Have have, Targets t, List<String> extras) {
        List<Integer> take = new ArrayList<>();
        // armor: best tier per empty slot
        for (int slot = 0; slot < 4; slot++) {
            if (have.armorSlots()[slot]) continue;
            int best = -1, bestRank = -1;
            for (Stack s : container) {
                if (armorSlot(s.itemId()) != slot) continue;
                int r = OpponentGear.Tier.fromItemId(s.itemId()).rank;
                if (r > bestRank) {
                    bestRank = r;
                    best = s.slot();
                }
            }
            if (best >= 0) take.add(best);
        }
        if (!have.weapon()) {
            int best = -1, bestRank = -1;
            for (Stack s : container) {
                int r = weaponRank(s.itemId());
                if (r > bestRank) {
                    bestRank = r;
                    best = s.slot();
                }
            }
            if (best >= 0) take.add(best);
        }
        int totems = have.totems(), gaps = have.gaps(), food = have.food();
        for (Stack s : container) {
            if (take.contains(s.slot())) continue;
            String p = path(s.itemId());
            if (p.equals("totem_of_undying") && totems < t.totems()) {
                take.add(s.slot());
                totems += s.count();
            } else if ((p.equals("golden_apple") || p.equals("enchanted_golden_apple")) && gaps < t.gaps()) {
                take.add(s.slot());
                gaps += s.count();
            } else if (food(s.itemId()) && food < t.food()) {
                take.add(s.slot());
                food += s.count();
            }
        }
        for (String extra : extras) {
            if (have.carriedIds().contains(extra)) continue;
            for (Stack s : container) {
                if (s.itemId().equals(extra) && !take.contains(s.slot())) {
                    take.add(s.slot());
                    break;
                }
            }
        }
        return take;
    }
}
