/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * MaceKill: 1.21+ mace smash-attack combat. Launches itself (jump off a ledge, or wind-charge
 * self-launch) to build >1.5 blocks of fall, then smashes the target while falling.
 * With Wind Burst on the mace the bounce chains into continuous smashes.
 * Mechanics: smash = +4 dmg/block (first 3), +2 (next 5), +1 after; trigger > 1.5 blocks fall.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;

public class MaceKill extends Module {
    public enum LaunchMode {
        WindCharge,
        Jump
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to the target.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<LaunchMode> launchMode = sgGeneral.add(new EnumSetting.Builder<LaunchMode>()
        .name("launch-mode")
        .description("How to start the fall. WindCharge throws a wind charge at your feet (works on flat ground); Jump requires a ledge (at least 2 blocks of drop nearby).")
        .defaultValue(LaunchMode.WindCharge)
        .build()
    );

    private final Setting<Double> minFall = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-fall")
        .description("Fall distance required before smashing (>1.5 blocks triggers the smash).")
        .defaultValue(2.0)
        .range(1.6, 10)
        .sliderMax(6)
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
        .description("Switches to the mace when a target is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> attackDelay = sgGeneral.add(new IntSetting.Builder()
        .name("attack-delay")
        .description("Ticks between smash attempts.")
        .defaultValue(8)
        .range(1, 20)
        .build()
    );

    private final Setting<Boolean> onlyWhenHolding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-holding-mace")
        .description("Only acts while a mace is in your hand.")
        .defaultValue(false)
        .build()
    );

    private int attackTimer;
    private int jumpTicks;
    private boolean wasOnGround = true;

    public MaceKill() {
        super(Categories.Combat, "mace-kill", "Automatically performs mace smash attacks on targets using wind-charge self-launch or ledge jumps.");
    }

    @Override
    public void onDeactivate() {
        attackTimer = 0;
        jumpTicks = 0;
        if (mc.options != null && mc.options.keyJump.isDown()) mc.options.keyJump.setDown(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive()) return;

        if (attackTimer > 0) attackTimer--;
        if (jumpTicks > 0) {
            mc.options.keyJump.setDown(true);
            jumpTicks--;
            if (jumpTicks == 0) mc.options.keyJump.setDown(false);
        }

        boolean holdingMace = mc.player.getMainHandItem().getItem() instanceof MaceItem;
        if (onlyWhenHolding.get() && !holdingMace) return;

        Player target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
        if (target == null || !Friends.get().shouldAttack(target)) return;

        // Ensure mace in hand
        if (!holdingMace) {
            if (!autoSwitch.get()) return;
            FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
            if (!mace.found()) return;
            InvUtils.swap(mace.slot(), false);
            return; // wait a tick for the swap
        }

        // Launch phase: not falling far enough yet
        if (mc.player.fallDistance < minFall.get()) {
            if (mc.player.onGround() && !wasOnGround) jumpTicks = 0; // landed between smashes, allow re-launch
            if (mc.player.onGround() && jumpTicks == 0) {
                launch();
            }
            wasOnGround = mc.player.onGround();
            return;
        }

        wasOnGround = mc.player.onGround();

        // Smash phase: falling enough, hit the target if in reach
        if (attackTimer > 0) return;
        if (mc.player.distanceTo(target) > 3.2) return; // mace reach ~3.0 + margin

        if (rotate.get()) {
            double yaw = Rotations.getYaw(target);
            double pitch = Rotations.getPitch(target);
            Rotations.rotate(yaw, pitch, 50, () -> attack(target));
        } else {
            attack(target);
        }
        attackTimer = attackDelay.get();
    }

    private void launch() {
        if (launchMode.get() == LaunchMode.Jump) {
            // Only jump when a 2-block drop exists nearby (a plain jump is only ~1.25 blocks of fall)
            if (!hasDropNearby()) return;
            jumpTicks = 2;
            return;
        }

        // WindCharge: throw a wind charge straight down at our feet
        FindItemResult wc = InvUtils.findInHotbar(Items.WIND_CHARGE);
        if (!wc.found()) return;

        InteractionHand hand = wc.getHand();
        if (!wc.isOffhand() && hand == InteractionHand.MAIN_HAND && !InvUtils.testInMainHand(Items.WIND_CHARGE)) {
            InvUtils.swap(wc.slot(), false);
        }

        Rotations.rotate(mc.player.getYRot(), 90, 50, () -> {
            if (mc.player != null && mc.gameMode != null) {
                mc.gameMode.useItem(mc.player, hand);
            }
        });
    }

    private boolean hasDropNearby() {
        var level = mc.level;
        if (level == null) return false;
        var pos = mc.player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var p = pos.offset(dx, -1, dz);
                if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isAir()) return true;
            }
        }
        return false;
    }

    private void attack(Player target) {
        mc.player.connection.send(new ServerboundAttackPacket(target.getId()));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
