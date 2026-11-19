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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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

    private final Setting<Boolean> elytraDiveBomb = sgGeneral.add(new BoolSetting.Builder()
        .name("elytra-dive-bomb")
        .description("Exits elytra glide above a target, builds fall distance, smashes with a mace, and recovers glide.")
        .defaultValue(true)
        .build()
    );

    private enum DiveState {
        NONE,
        FALLING,
        RECOVER
    }

    private int attackTimer;
    private int jumpTicks;
    private boolean wasOnGround = true;
    private DiveState diveState = DiveState.NONE;
    private int diveTicks;
    private int savedElytraSlot = -1;
    private boolean movedElytraFromChest;

    public MaceKill() {
        super(Categories.Combat, "mace-kill", "Automatically performs mace smash attacks on targets using wind-charge self-launch or ledge jumps.");
    }

    @Override
    public void onDeactivate() {
        attackTimer = 0;
        jumpTicks = 0;
        diveState = DiveState.NONE;
        diveTicks = 0;
        savedElytraSlot = -1;
        movedElytraFromChest = false;
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
        if (onlyWhenHolding.get() && !holdingMace && diveState == DiveState.NONE) return;

        if (elytraDiveBomb.get() && handleElytraDiveBomb(holdingMace)) {
            return;
        }

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

        boolean isOffhand = wc.isOffhand();
        int prevSlot = mc.player.getInventory().getSelectedSlot();
        if (!isOffhand) {
            InvUtils.swap(wc.slot(), false);
        }

        InteractionHand hand = isOffhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        Rotations.rotate(mc.player.getYRot(), 90, 50, () -> {
            if (mc.player != null && mc.gameMode != null) {
                mc.gameMode.useItem(mc.player, hand);
                if (!isOffhand) {
                    InvUtils.swap(prevSlot, false);
                }
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

    private boolean handleElytraDiveBomb(boolean holdingMace) {
        if (diveState == DiveState.RECOVER) {
            recoverElytraGlide();
            return true;
        }

        if (diveState == DiveState.FALLING) {
            diveTicks++;
            if (mc.player.onGround() || mc.player.isInWater() || diveTicks > 80) {
                diveState = DiveState.RECOVER;
                recoverElytraGlide();
                return true;
            }

            if (!holdingMace && autoSwitch.get()) {
                FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
                if (mace.found()) InvUtils.swap(mace.slot(), false);
            }

            if (mc.player.isFallFlying()) {
                exitElytraGlide();
                return true;
            }

            boolean nowHoldingMace = mc.player.getMainHandItem().getItem() instanceof MaceItem;
            if (nowHoldingMace && mc.player.fallDistance >= minFall.get() && attackTimer <= 0) {
                double maxServerReach = Math.min(range.get(), mc.player.entityInteractionRange());
                Player smashTarget = findDiveSmashTarget(maxServerReach);
                if (smashTarget != null) {
                    if (rotate.get()) {
                        Rotations.rotate(Rotations.getYaw(smashTarget), Rotations.getPitch(smashTarget), 50, () -> attack(smashTarget));
                    } else {
                        attack(smashTarget);
                    }
                    attackTimer = attackDelay.get();
                    diveState = DiveState.RECOVER;
                    diveTicks = 0;
                    return true;
                }
            }
            return true;
        }

        // DiveState.NONE: engage dive bomb when fall-flying above a valid enemy
        if (!mc.player.isFallFlying()) return false;
        if (!holdingMace && (onlyWhenHolding.get() || !autoSwitch.get() || !InvUtils.findInHotbar(Items.MACE).found())) {
            return false;
        }

        Player diveTarget = findDiveAboveTarget();
        if (diveTarget == null) return false;

        if (!holdingMace) {
            FindItemResult mace = InvUtils.findInHotbar(Items.MACE);
            if (!mace.found()) return false;
            InvUtils.swap(mace.slot(), false);
        }

        if (exitElytraGlide()) {
            diveState = DiveState.FALLING;
            diveTicks = 0;
            return true;
        }
        return false;
    }

    private Player findDiveAboveTarget() {
        if (mc.level == null || mc.player == null) return null;
        Player best = null;
        double bestXz = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isDeadOrDying() || !player.isAlive() || player.isCreative()) continue;
            if (!Friends.get().shouldAttack(player)) continue;
            double dy = mc.player.getY() - player.getY();
            if (dy < 4.5) continue;
            double xz = Math.hypot(mc.player.getX() - player.getX(), mc.player.getZ() - player.getZ());
            if (xz <= 3.8 && xz < bestXz) {
                bestXz = xz;
                best = player;
            }
        }
        return best;
    }

    private Player findDiveSmashTarget(double maxReach) {
        if (mc.level == null || mc.player == null) return null;
        Vec3 eyes = mc.player.getEyePosition();
        Player best = null;
        double bestDistSq = maxReach * maxReach;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isDeadOrDying() || !player.isAlive() || player.isCreative()) continue;
            if (!Friends.get().shouldAttack(player)) continue;
            AABB box = player.getBoundingBox();
            double cx = Mth.clamp(eyes.x, box.minX, box.maxX);
            double cy = Mth.clamp(eyes.y, box.minY, box.maxY);
            double cz = Mth.clamp(eyes.z, box.minZ, box.maxZ);
            double dSq = eyes.distanceToSqr(cx, cy, cz);
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = player;
            }
        }
        return best;
    }

    private boolean exitElytraGlide() {
        if (mc.player == null) return false;
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.has(DataComponents.GLIDER)) return !mc.player.isFallFlying();
        if (!(mc.player.containerMenu instanceof InventoryMenu)) return false;

        int chestplateSlot = -1;
        int emptySlot = -1;
        int limit = mc.player.getInventory().getNonEquipmentItems().size();
        for (int i = 0; i < limit; i++) {
            ItemStack stack = mc.player.getInventory().getNonEquipmentItems().get(i);
            if (stack.isEmpty()) {
                if (emptySlot == -1) emptySlot = i;
            } else if (stack.has(DataComponents.EQUIPPABLE)
                && !stack.has(DataComponents.GLIDER)
                && stack.get(DataComponents.EQUIPPABLE).slot() == EquipmentSlot.CHEST) {
                chestplateSlot = i;
                break;
            }
        }

        if (chestplateSlot != -1) {
            savedElytraSlot = chestplateSlot;
            movedElytraFromChest = true;
            InvUtils.move().from(chestplateSlot).toArmor(2);
            if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(0));
            return true;
        } else if (emptySlot != -1) {
            savedElytraSlot = emptySlot;
            movedElytraFromChest = true;
            InvUtils.move().fromArmor(2).to(emptySlot);
            if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(0));
            return true;
        }
        return false;
    }

    private void recoverElytraGlide() {
        if (mc.player == null) {
            diveState = DiveState.NONE;
            return;
        }

        if (movedElytraFromChest && mc.player.containerMenu instanceof InventoryMenu) {
            int elytraSlot = -1;
            if (savedElytraSlot >= 0 && savedElytraSlot < mc.player.getInventory().getNonEquipmentItems().size()
                && mc.player.getInventory().getNonEquipmentItems().get(savedElytraSlot).has(DataComponents.GLIDER)) {
                elytraSlot = savedElytraSlot;
            } else {
                for (int i = 0; i < mc.player.getInventory().getNonEquipmentItems().size(); i++) {
                    if (mc.player.getInventory().getNonEquipmentItems().get(i).has(DataComponents.GLIDER)) {
                        elytraSlot = i;
                        break;
                    }
                }
            }
            if (elytraSlot != -1) {
                InvUtils.move().from(elytraSlot).toArmor(2);
                if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(0));
            }
            movedElytraFromChest = false;
            savedElytraSlot = -1;
        }

        if (!mc.player.onGround()
            && !mc.player.isFallFlying()
            && mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)
            && mc.player.connection != null) {
            mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }

        diveState = DiveState.NONE;
        diveTicks = 0;
    }

    private void attack(Player target) {
        if (mc.player == null || mc.player.connection == null) return;
        mc.player.connection.send(new ServerboundAttackPacket(target.getId()));
        mc.player.resetAttackStrengthTicker();
        PlayerUtils.punch(true);
    }
}
