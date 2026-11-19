/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import meteordevelopment.meteorclient.systems.modules.combat.model.RegearPlan;
import meteordevelopment.meteorclient.utils.player.CameraArbiter;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Regear route (C60, decision 3): walk to the configured stash container with Baritone, open it with a legit look
 * (camera via {@link CameraArbiter}), shift-click the planned stacks at a human pace, close it and let AutoArmor
 * equip. Plain item moves only, no packets beyond vanilla clicks.
 */
public final class RegearController {
    public enum Phase { IDLE, PATHING, OPENING, LOOTING, DONE, FAILED }

    private Phase phase = Phase.IDLE;
    private @Nullable BlockPos stash;
    private int timer, closeIn = -1;
    private RegearPacer pacer = RegearPacer.create(0);
    private int pacerDelay = 0;
    private List<Integer> queue = new ArrayList<>();
    private @Nullable String failure;
    private int attempts;

    public Phase phase() {
        return phase;
    }

    public boolean active() {
        return phase == Phase.PATHING || phase == Phase.OPENING || phase == Phase.LOOTING;
    }

    public @Nullable String failure() {
        return failure;
    }

    public void start(BlockPos stashPos) {
        if (active()) return;
        stash = stashPos;
        phase = Phase.PATHING;
        timer = 0;
        failure = null;
        attempts++;
        baritone().ifPresent(b -> b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(stashPos)));
    }

    public void cancel() {
        if (active()) baritone().ifPresent(b -> b.getPathingBehavior().cancelEverything());
        phase = Phase.IDLE;
        queue.clear();
    }

    /** Re-arm after a successful regear or a respawn. */
    public void resetAttempts() {
        attempts = 0;
        if (phase == Phase.DONE || phase == Phase.FAILED) phase = Phase.IDLE;
    }

    public int attempts() {
        return attempts;
    }

    private java.util.Optional<IBaritone> baritone() {
        try {
            return java.util.Optional.of(BaritoneAPI.getProvider().getPrimaryBaritone());
        } catch (RuntimeException | LinkageError e) {
            return java.util.Optional.empty();
        }
    }

    private void fail(String why) {
        failure = why;
        phase = Phase.FAILED;
        baritone().ifPresent(b -> b.getPathingBehavior().cancelEverything());
    }

    /** @param clickDelay ticks between shift-clicks, 0 = Baritone's shared GUI pacing; @param extras user extra item list */
    public void tick(int clickDelay, List<Item> extras, RegearPlan.Targets targets) {
        if (mc.player == null || mc.level == null || stash == null || mc.gameMode == null) return;
        timer++;
        BlockPos chest = stash;
        switch (phase) {
            case PATHING -> {
                double d = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
                if (d <= 4.0) {
                    baritone().ifPresent(b -> b.getPathingBehavior().cancelEverything());
                    phase = Phase.OPENING;
                    timer = 0;
                } else if (timer > 20 * 90) {
                    fail("could not reach the stash within 90 s");
                } else if (timer % 100 == 0) {
                    baritone().ifPresent(b -> {
                        if (!b.getCustomGoalProcess().isActive()) b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(chest));
                    });
                }
            }
            case OPENING -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    queue = planFor(mc.player.containerMenu, extras, targets);
                    phase = Phase.LOOTING;
                    timer = 0;
                    closeIn = -1;
                    if (pacerDelay != clickDelay) {
                        pacerDelay = clickDelay;
                        pacer = RegearPacer.create(clickDelay);
                    }
                    int stacks = 0;
                    AbstractContainerMenu menu = mc.player.containerMenu;
                    for (int i = 0; i < menu.slots.size() - 36; i++) if (!menu.getSlot(i).getItem().isEmpty()) stacks++;
                    pacer.opened(stacks); // S41: time to read the contents
                    return;
                }
                Vec3 c = Vec3.atCenterOf(chest);
                float yaw = (float) Rotations.getYaw(c), pitch = (float) Rotations.getPitch(c);
                CameraArbiter.claim(this, CameraArbiter.PRIORITY_STEER, yaw, pitch, true);
                boolean aimed = Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yaw)) < 8 && Math.abs(mc.player.getXRot() - pitch) < 8;
                if (aimed && timer % 10 == 0) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(c, Direction.UP, chest, false));
                    meteordevelopment.meteorclient.utils.player.PlayerUtils.swingHand(InteractionHand.MAIN_HAND);
                }
                if (timer > 200) fail("stash container did not open (is it a chest/barrel/shulker?)");
            }
            case LOOTING -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu == mc.player.inventoryMenu) {
                    phase = queue.isEmpty() ? Phase.DONE : Phase.FAILED;
                    if (!queue.isEmpty()) failure = "container closed early";
                    return;
                }
                if (closeIn < 0 && (queue.isEmpty() || InvUtils.findEmpty().slot() < 0)) {
                    closeIn = pacer.closeDelay(); // S41: a short beat before closing
                }
                if (closeIn >= 0) {
                    if (closeIn-- > 0) return;
                    mc.player.closeContainer();
                    phase = Phase.DONE;
                    return;
                }
                if (!pacer.ready(queue.get(0), 9)) return; // S40: shared pacing incl. cursor travel
                InvUtils.shiftClick().slotId(queue.remove(0));
            }
            default -> {
            }
        }
    }

    static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private List<Integer> planFor(AbstractContainerMenu menu, List<Item> extras, RegearPlan.Targets targets) {
        if (mc.player == null) return new ArrayList<>();
        int containerSlots = Math.max(0, menu.slots.size() - 36);
        List<RegearPlan.Stack> stacks = new ArrayList<>();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) stacks.add(new RegearPlan.Stack(i, id(s), s.getCount()));
        }
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        boolean[] armor = new boolean[4];
        for (int i = 0; i < 4; i++) {
            ItemStack worn = mc.player.getItemBySlot(slots[i]);
            final int slot = i;
            armor[i] = !worn.isEmpty() || InvUtils.find(st -> !st.isEmpty() && RegearPlan.armorSlot(id(st)) == slot).found();
        }
        Set<String> carried = new HashSet<>();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty()) carried.add(id(s));
        }
        boolean weapon = InvUtils.find(st -> !st.isEmpty() && RegearPlan.weaponRank(id(st)) >= 0).found();
        int totems = InvUtils.find(Items.TOTEM_OF_UNDYING).count();
        int gaps = InvUtils.find(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE).count();
        int food = InvUtils.find(st -> !st.isEmpty() && RegearPlan.food(id(st))).count();
        List<String> extraIds = new ArrayList<>();
        for (Item it : extras) extraIds.add(BuiltInRegistries.ITEM.getKey(it).toString());
        return RegearPlan.plan(stacks, new RegearPlan.Have(armor, weapon, totems, gaps, food, carried), targets, extraIds);
    }
}
