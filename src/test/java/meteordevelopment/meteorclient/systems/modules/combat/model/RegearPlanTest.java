/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** C60 regear plan. */
class RegearPlanTest {
    @Test
    void takesBestArmorWeaponAndConsumables() {
        List<RegearPlan.Stack> chest = List.of(
            new RegearPlan.Stack(0, "minecraft:iron_helmet", 1),
            new RegearPlan.Stack(1, "minecraft:netherite_helmet", 1),
            new RegearPlan.Stack(2, "minecraft:diamond_chestplate", 1),
            new RegearPlan.Stack(3, "minecraft:diamond_sword", 1),
            new RegearPlan.Stack(4, "minecraft:netherite_sword", 1),
            new RegearPlan.Stack(5, "minecraft:totem_of_undying", 1),
            new RegearPlan.Stack(6, "minecraft:totem_of_undying", 1),
            new RegearPlan.Stack(7, "minecraft:golden_apple", 16),
            new RegearPlan.Stack(8, "minecraft:golden_apple", 16),
            new RegearPlan.Stack(9, "minecraft:cooked_beef", 64),
            new RegearPlan.Stack(10, "minecraft:ender_pearl", 16),
            new RegearPlan.Stack(11, "minecraft:dirt", 64));
        var have = new RegearPlan.Have(new boolean[]{true, true, false, false}, false, 0, 0, 0, Set.of());
        List<Integer> take = RegearPlan.plan(chest, have, RegearPlan.Targets.DEFAULT, List.of("minecraft:ender_pearl"));
        assertEquals(List.of(2, 1, 4, 5, 6, 7, 9, 10), take);
    }

    @Test
    void nothingWhenKitted() {
        var have = new RegearPlan.Have(new boolean[]{true, true, true, true}, true, 4, 8, 32, Set.of("minecraft:ender_pearl"));
        assertTrue(RegearPlan.plan(List.of(new RegearPlan.Stack(0, "minecraft:totem_of_undying", 1)), have,
            RegearPlan.Targets.DEFAULT, List.of("minecraft:ender_pearl")).isEmpty());
    }
}
