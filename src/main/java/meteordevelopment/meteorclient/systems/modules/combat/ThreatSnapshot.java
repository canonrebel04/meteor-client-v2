/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ThreatSnapshot {
    private final Player target;
    private final double distance;
    private final double threatLevel;

    public ThreatSnapshot(double scanRange) {
        this.target = TargetUtils.getPlayerTarget(scanRange, SortPriority.LowestDistance);
        if (target != null) {
            this.distance = mc.player.distanceTo(target);
            this.threatLevel = calculateThreat();
        } else {
            this.distance = Double.MAX_VALUE;
            this.threatLevel = 0.0;
        }
    }

    public Player getTarget() {
        return target;
    }

    public double getDistance() {
        return distance;
    }

    public double getThreatLevel() {
        return threatLevel;
    }

    private double calculateThreat() {
        if (target == null) return 0.0;

        // Base threat based on proximity
        double base = 0.0;
        if (distance < 4.0) {
            base = 0.5; // Melee range
        } else if (distance < 8.0) {
            base = 0.3; // Mid range
        } else {
            base = 0.1; // Long range
        }

        // Threat scales up if target holds weapon / explosives
        ItemStack mainHand = target.getMainHandItem();
        if (mainHand.is(net.minecraft.tags.ItemTags.SWORDS) || mainHand.getItem() == Items.NETHERITE_AXE || mainHand.getItem() == Items.DIAMOND_AXE) {
            base += 0.2;
        } else if (mainHand.getItem() == Items.END_CRYSTAL || mainHand.getItem() == Items.RESPAWN_ANCHOR) {
            base += 0.3;
        }

        // Threat increases if our health is low
        float selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (selfHealth < 10f) {
            base += 0.3;
        } else if (selfHealth < 16f) {
            base += 0.15;
        }

        // Threat increases if we have few/no totems
        int totems = countTotems();
        if (totems == 0) {
            base += 0.4;
        } else if (totems == 1) {
            base += 0.15;
        }

        return Math.min(1.0, Math.max(0.0, base));
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
            count++;
        }
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
                count++;
            }
        }
        return count;
    }
}
