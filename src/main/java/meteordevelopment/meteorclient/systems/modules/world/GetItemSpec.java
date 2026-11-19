/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import java.util.*;

/** Pure helpers for the Get Item module (target spec, protect lists, interop decisions); no Minecraft/Baritone types. */
public final class GetItemSpec {
    private GetItemSpec() {
    }

    /**
     * The {@code #get} target: the tag when one is given ("logs", "#logs", "minecraft:logs" all become
     * "#minecraft:logs"), otherwise the item id.
     */
    public static String target(String tag, String itemId) {
        String t = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        if (!t.isEmpty()) {
            if (t.startsWith("#")) t = t.substring(1);
            if (t.indexOf(':') < 0) t = "minecraft:" + t;
            return "#" + t;
        }
        return itemId;
    }

    /** The "step N" part of a Baritone Get Item status line, used to announce step changes once. */
    public static String stepKey(String status) {
        if (status == null) return "";
        int i = status.indexOf(", step ");
        if (i < 0) return status;
        int end = status.indexOf(':', i);
        return end < 0 ? status.substring(i + 2) : status.substring(i + 2, end);
    }

    /** Protected chest name patterns: trimmed, lower-cased, de-duplicated, blanks dropped (order kept). */
    public static List<String> chestNames(List<String> names) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (names != null) for (String n : names) {
            if (n == null) continue;
            String t = n.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return new ArrayList<>(out);
    }

    /** Protected chest positions as "x,y,z" (spaces or semicolons allowed in the input); invalid entries dropped. */
    public static List<String> chestPositions(List<String> positions) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (positions != null) for (String p : positions) {
            if (p == null) continue;
            String[] parts = p.trim().split("[,;\\s]+");
            if (parts.length != 3) continue;
            try {
                out.add(Integer.parseInt(parts[0]) + "," + Integer.parseInt(parts[1]) + "," + Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(out);
    }

    /** What to do with CombatBrain this tick of the hand-off (A46 + Meteor combat). */
    public enum Handoff { NONE, ENABLE, DISABLE }

    /**
     * Enable CombatBrain while Baritone pauses for hostiles; turn it back off only if we turned it on, after the
     * threat has been gone for {@code calmTicks} ticks.
     */
    public static Handoff handoff(boolean enabled, boolean pausedForHostiles, boolean combatActive, boolean weEnabledIt, int calmTicks, int calmNeeded) {
        if (!enabled) return weEnabledIt && combatActive ? Handoff.DISABLE : Handoff.NONE;
        if (pausedForHostiles) return combatActive ? Handoff.NONE : Handoff.ENABLE;
        if (weEnabledIt && combatActive && calmTicks >= calmNeeded) return Handoff.DISABLE;
        return Handoff.NONE;
    }

    /** Baritone's auto-eat is off while Meteor's AutoEat module runs (they would fight over the hotbar). */
    public static boolean baritoneAutoEat(boolean wanted, boolean meteorAutoEatActive) {
        return wanted && !meteorAutoEatActive;
    }

    /** Meteor modules that drive mining/inventory on their own and conflict with a Get Item run. */
    public static final List<String> CONFLICTING = List.of("infinity-miner", "vein-miner", "nuker", "auto-smelter", "echest-farmer",
        "auto-breed", "auto-shearer", "auto-fish"); // A42: these grab the same animals / rod / hotbar as M3 gather steps

    /** A30 hunting: what to do with Baritone's hunt target this tick. */
    public enum Hunt { NONE, AIM, ATTACK }

    /**
     * Legit hunting: aim at the target once it is near, attack only when it is under the crosshair, within reach and
     * the attack cooldown is charged (no multi-hit spam, no through-wall hits).
     */
    public static Hunt hunt(boolean enabled, int targetId, double distance, double reach, float cooldown, boolean onCrosshair) {
        if (!enabled || targetId < 0 || distance > reach + 3) return Hunt.NONE;
        if (distance <= reach && onCrosshair && cooldown >= 0.92f) return Hunt.ATTACK;
        return Hunt.AIM;
    }
}
