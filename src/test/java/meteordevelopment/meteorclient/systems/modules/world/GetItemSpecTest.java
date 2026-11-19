/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
