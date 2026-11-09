/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

/** Pure scoring logic for {@link BaseFinder}, kept free of Minecraft types for unit testing. */
public final class BaseFinderScoring {
    private BaseFinderScoring() {}

    public static boolean isBase(int matches, int minBlocks, boolean hasStructureMarker, boolean excludeStructures) {
        if (excludeStructures && hasStructureMarker) return false;
        return matches >= Math.max(1, minBlocks);
    }
}
