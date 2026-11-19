/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import java.util.Locale;

/** Pure helpers for the Get Item module (target spec and progress messages); no Minecraft/Baritone types. */
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
}
