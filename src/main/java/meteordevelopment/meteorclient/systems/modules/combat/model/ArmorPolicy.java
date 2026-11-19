/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

/**
 * Armor management (C55): when to swap a breaking piece mid-fight (only when safe) and elytra <-> chestplate.
 */
public final class ArmorPolicy {
    public enum ChestChoice { CHESTPLATE, ELYTRA, KEEP }

    private ArmorPolicy() {
    }

    /** Replace a piece under {@code threshold} durability when we have a spare and nobody is within melee range. */
    public static boolean replaceBroken(double durability, double threshold, boolean haveSpare, double enemyDistance) {
        return haveSpare && durability <= threshold && enemyDistance > 6.0;
    }

    /**
     * Elytra only while actually flying / about to escape by air; chestplate whenever a fight is on or we're on the
     * ground (an elytra gives no armor).
     */
    public static ChestChoice chest(boolean wearingElytra, boolean airEscapePlanned, boolean fightOn, boolean onGround, boolean haveChestplate, boolean haveElytra) {
        if (airEscapePlanned && haveElytra && !wearingElytra) return ChestChoice.ELYTRA;
        if (wearingElytra && haveChestplate && (fightOn || onGround) && !airEscapePlanned) return ChestChoice.CHESTPLATE;
        return ChestChoice.KEEP;
    }
}
