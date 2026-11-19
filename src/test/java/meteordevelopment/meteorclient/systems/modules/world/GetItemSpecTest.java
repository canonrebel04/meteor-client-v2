/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GetItemSpecTest {
    @Test
    void tagWinsOverItem() {
        assertEquals("#minecraft:logs", GetItemSpec.target("logs", "minecraft:iron_pickaxe"));
        assertEquals("#minecraft:logs", GetItemSpec.target("#logs", "minecraft:iron_pickaxe"));
        assertEquals("#mymod:things", GetItemSpec.target(" #MyMod:Things ", "minecraft:stone"));
    }

    @Test
    void emptyTagUsesItem() {
        assertEquals("minecraft:iron_pickaxe", GetItemSpec.target("", "minecraft:iron_pickaxe"));
        assertEquals("minecraft:iron_pickaxe", GetItemSpec.target(null, "minecraft:iron_pickaxe"));
    }

    @Test
    void stepKeyIgnoresProgress() {
        String a = "getting minecraft:iron_pickaxe 0/1, step 3: mining stone 2/8";
        String b = "getting minecraft:iron_pickaxe 0/1, step 3: mining stone 5/8";
        assertEquals(GetItemSpec.stepKey(a), GetItemSpec.stepKey(b));
        assertEquals("step 3", GetItemSpec.stepKey(a));
        assertEquals("step 4", GetItemSpec.stepKey("getting x 0/1, step 4"));
    }

    @Test
    void chestNamesAreNormalised() {
        assertEquals(List.of("protect", "do not touch"), GetItemSpec.chestNames(Arrays.asList(" Protect", "", null, "DO NOT TOUCH", "protect ")));
        assertTrue(GetItemSpec.chestNames(null).isEmpty());
    }

    @Test
    void chestPositionsAreParsedLeniently() {
        assertEquals(List.of("1,64,-3", "10,-20,5"), GetItemSpec.chestPositions(Arrays.asList("1, 64, -3", "10 -20 5", "x,y,z", "1,2", "1,64,-3")));
    }

    @Test
    void combatHandoff() {
        assertEquals(GetItemSpec.Handoff.ENABLE, GetItemSpec.handoff(true, true, false, false, 0, 100));
        assertEquals(GetItemSpec.Handoff.NONE, GetItemSpec.handoff(true, true, true, false, 0, 100));
        assertEquals(GetItemSpec.Handoff.NONE, GetItemSpec.handoff(true, false, true, true, 50, 100));
        assertEquals(GetItemSpec.Handoff.DISABLE, GetItemSpec.handoff(true, false, true, true, 100, 100));
        // never turn off a Combat Brain the user turned on
        assertEquals(GetItemSpec.Handoff.NONE, GetItemSpec.handoff(true, false, true, false, 500, 100));
        assertEquals(GetItemSpec.Handoff.NONE, GetItemSpec.handoff(false, true, false, false, 0, 100));
        assertEquals(GetItemSpec.Handoff.DISABLE, GetItemSpec.handoff(false, false, true, true, 0, 100));
    }

    @Test
    void autoEatDefersToMeteor() {
        assertTrue(GetItemSpec.baritoneAutoEat(true, false));
        assertFalse(GetItemSpec.baritoneAutoEat(true, true));
        assertFalse(GetItemSpec.baritoneAutoEat(false, false));
    }

    @Test
    void stepKeyKeepsPauseReason() {
        assertEquals("step 2 [fleeing", GetItemSpec.stepKey("getting x 0/1, step 2 [fleeing: 2 hostiles within 5m]"));
    }
}
