/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalRunAway;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PortalEscape extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> useChorus = sgGeneral.add(new BoolSetting.Builder()
        .name("use-chorus")
        .description("Eat chorus fruit first if available in hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> usePearlPhase = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pearl-phase")
        .description("Use PearlPhase if no chorus fruit is available.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineOut = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-out")
        .description("Mine the fastest adjacent exit block if trapped.")
        .defaultValue(true)
        .build()
    );

    private BlockPos trappedPortalPos;
    private int cooldownTicks;

    public PortalEscape() {
        super(Categories.Movement, "portal-escape", "Automatically escapes trapped Nether portals using chorus fruit, pearl phase, or mining.");
    }

    @Override
    public void onActivate() {
        trappedPortalPos = null;
        cooldownTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        BlockPos feet = mc.player.blockPosition();
        boolean inPortal = mc.level.getBlockState(feet).is(Blocks.NETHER_PORTAL)
            || mc.level.getBlockState(feet.above()).is(Blocks.NETHER_PORTAL);

        if (!inPortal) {
            if (trappedPortalPos != null && feet.distSqr(trappedPortalPos) >= 16.0) {
                trappedPortalPos = null;
            }
            return;
        }

        if (!isTrapped(feet)) return;

        trappedPortalPos = feet;

        if (useChorus.get()) {
            FindItemResult chorus = InvUtils.findInHotbar(Items.CHORUS_FRUIT);
            if (chorus.found()) {
                InvUtils.swap(chorus.slot(), false);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                cooldownTicks = 25;
                return;
            }
        }

        if (usePearlPhase.get()) {
            FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
            PearlPhase pp = Modules.get().get(PearlPhase.class);
            if (pearl.found() && pp != null && !pp.isActive()) {
                pp.toggle();
                cooldownTicks = 30;
                return;
            }
        }

        if (mineOut.get()) {
            BlockPos target = findWeakestExit(feet);
            if (target != null) {
                BlockState state = mc.level.getBlockState(target);
                FindItemResult tool = InvUtils.findFastestTool(state);
                if (tool.found()) InvUtils.swap(tool.slot(), false);
                Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), 100, () -> {
                    meteordevelopment.meteorclient.utils.world.BlockUtils.breakBlock(target, true);
                });
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalRunAway(32, feet));
            }
        }
    }

    private boolean isTrapped(BlockPos feet) {
        if (mc.level == null) return false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = feet.relative(dir);
            BlockState low = mc.level.getBlockState(adj);
            BlockState high = mc.level.getBlockState(adj.above());
            boolean blocked = !low.getCollisionShape(mc.level, adj).isEmpty()
                || !high.getCollisionShape(mc.level, adj.above()).isEmpty()
                || low.is(Blocks.LAVA) || low.is(Blocks.FIRE);
            if (!blocked) return false;
        }
        return true;
    }

    private BlockPos findWeakestExit(BlockPos feet) {
        if (mc.level == null) return null;
        BlockPos best = null;
        float bestHardness = Float.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = feet.relative(dir).above();
            BlockState state = mc.level.getBlockState(adj);
            if (state.isAir()) continue;
            float hardness = state.getDestroySpeed(mc.level, adj);
            if (hardness >= 0 && hardness < bestHardness) {
                bestHardness = hardness;
                best = adj;
            }
        }
        return best;
    }
}
