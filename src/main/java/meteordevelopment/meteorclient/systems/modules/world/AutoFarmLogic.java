/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

/** Pure harvest rules for {@link AutoFarm}. */
public final class AutoFarmLogic {
    private AutoFarmLogic() {}

    public enum Kind { AGED, GOURD, STACKED, NONE }

    /**
     * @param age         current age (AGED only)
     * @param maxAge      max age (AGED only)
     * @param sameBelow   block below is the same plant (STACKED only)
     */
    public static boolean shouldHarvest(Kind kind, int age, int maxAge, boolean sameBelow) {
        return switch (kind) {
            case AGED -> age >= maxAge;
            case GOURD -> true;
            // Leave the bottom block of sugar cane / cactus / bamboo so it regrows.
            case STACKED -> sameBelow;
            case NONE -> false;
        };
    }

    /** Bonemeal only crops that are still growing. */
    public static boolean shouldBonemeal(Kind kind, int age, int maxAge) {
        return kind == Kind.AGED && age < maxAge;
    }
}
