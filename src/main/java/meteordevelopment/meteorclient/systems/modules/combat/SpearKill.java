/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * SpearKill: kill-aura for 26.x spears, honoring the minecraft:attack_range data component
 * (max_reach 4.5, min_reach 2.0 — spears cannot hit targets closer than ~2 blocks and
 * cannot crit or sprint-knockback). Uses the item's effective range for reach math.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;

public class SpearKill extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum attack distance (spears reach up to 4.5 blocks).")
        .defaultValue(4.5)
        .min(0.5)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> minRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-range")
        .description("Spears cannot hit targets closer than ~2 blocks — the module backs off below this.")
        .defaultValue(2.0)
        .min(0.5)
        .sliderMax(3)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side towards the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to a spear when a target is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between attacks.")
        .defaultValue(2)
        .range(0, 10)
        .build()
    );

    private final Setting<Boolean> onlyWhenHolding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-holding-spear")
        .description("Only acts while a spear is in your hand.")
        .defaultValue(false)
        .build()
    );

    private int attackTimer;

    public SpearKill() {
        super(Categories.Combat, "spear-kill", "Attacks targets with a spear, honoring the 26.x attack_range component (4.5 max / 2.0 min reach).");
    }

    @Override
    public void onDeactivate() {
        attackTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive()) return;

        if (attackTimer > 0) attackTimer--;

        boolean holdingSpear = isSpear(mc.player.getMainHandItem());
        if (onlyWhenHolding.get() && !holdingSpear) return;

        Player target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
        if (target == null || !Friends.get().shouldAttack(target)) return;

        if (!holdingSpear) {
            if (!autoSwitch.get()) return;
            FindItemResult spear = InvUtils.findInHotbar(this::isSpear);
            if (!spear.found()) return;
            InvUtils.swap(spear.slot(), false);
            return;
        }

        double distance = mc.player.distanceTo(target);
        if (distance < minRange.get() || distance > range.get()) return;

        if (attackTimer > 0) return;

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), 50, () -> attack(target));
        } else {
            attack(target);
        }
        attackTimer = delay.get();
    }

    private void attack(Player target) {
        mc.player.connection.send(new ServerboundAttackPacket(target.getId()));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** A spear = any item whose attack_range component extends past vanilla 3.0 reach. */
    private boolean isSpear(ItemStack stack) {
        AttackRange attackRange = stack.get(DataComponents.ATTACK_RANGE);
        return attackRange != null && attackRange.effectiveMaxRange(mc.player) > 3.0f;
    }
}
