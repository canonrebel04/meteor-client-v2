/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class PistonPush extends Module {
    public enum RedstoneMode {
        RedstoneBlock,
        RedstoneTorch,
        Auto
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Maximum range to find a target.")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Maximum block placement distance.")
        .defaultValue(4.5)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<RedstoneMode> redstoneMode = sgGeneral.add(new EnumSetting.Builder<RedstoneMode>()
        .name("redstone-mode")
        .description("Which redstone power source to place next to the piston.")
        .defaultValue(RedstoneMode.Auto)
        .build()
    );

    private final Setting<Boolean> onlyInHole = sgGeneral.add(new BoolSetting.Builder()
        .name("only-in-hole")
        .description("Only targets enemies standing in holes or burrowed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders hand swing animation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOnPush = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-on-push")
        .description("Automatically disables after the piston extends.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the piston and redstone target blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of highlighted positions.")
        .defaultValue(new SettingColor(255, 165, 0, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of highlighted positions.")
        .defaultValue(new SettingColor(255, 165, 0, 200))
        .visible(render::get)
        .build()
    );

    private BlockPos activePistonPos;
    private BlockPos activeRedstonePos;

    public PistonPush() {
        super(Categories.Combat, "piston-push", "Ejects enemies out of 1x1 holes using a head-level piston and redstone power.");
    }

    @Override
    public void onDeactivate() {
        activePistonPos = null;
        activeRedstonePos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null) return;

        Player target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.ClosestAngle);
        if (target == null || !Friends.get().shouldAttack(target)) {
            activePistonPos = null;
            activeRedstonePos = null;
            return;
        }

        if (onlyInHole.get() && !isSurroundedOrBurrowed(target)) {
            if (activePistonPos != null && mc.level.getBlockState(activePistonPos).getBlock() instanceof PistonBaseBlock
                && Boolean.TRUE.equals(mc.level.getBlockState(activePistonPos).getValue(PistonBaseBlock.EXTENDED))) {
                if (toggleOnPush.get()) toggle();
            }
            return;
        }

        FindItemResult piston = InvUtils.findInHotbar(Items.PISTON, Items.STICKY_PISTON);
        if (!piston.found()) return;

        BlockPos targetHead = target.blockPosition().above();
        PushSetup setup = findBestSetup(targetHead);
        if (setup == null) return;

        activePistonPos = setup.pistonPos;
        activeRedstonePos = setup.redstonePos;

        BlockState pistonState = mc.level.getBlockState(setup.pistonPos);
        if (pistonState.getBlock() instanceof PistonBaseBlock) {
            if (Boolean.TRUE.equals(pistonState.getValue(PistonBaseBlock.EXTENDED))) {
                if (toggleOnPush.get()) toggle();
                return;
            }
            // Piston is placed facing targetHead; place redstone power
            FindItemResult redstone = findRedstoneItem(setup.useBlock);
            if (redstone.found()) {
                if (setup.useBlock) {
                    BlockUtils.place(setup.redstonePos, redstone, true, 90, swing.get(), true, true);
                } else {
                    placeStandingTorch(setup.redstonePos, redstone);
                }
            }
            return;
        }

        // Rotate strictly along horizontal axis away from targetHead so PistonBaseBlock#getStateForPlacement
        // sets FACING = dir.getOpposite() (pushing toward targetHead).
        float yaw = directionToYaw(setup.pistonOffsetDir);
        Rotations.rotate(yaw, 0.0, 95, () -> {
            BlockUtils.place(setup.pistonPos, piston, false, 95, swing.get(), true, true);
        });
    }

    private void placeStandingTorch(BlockPos torchPos, FindItemResult torch) {
        if (mc.player == null || mc.gameMode == null) return;
        BlockPos supportPos = torchPos.below();
        Vec3 hitVec = new Vec3(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, supportPos, false);
        InteractionHand hand = torch.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 90, () -> {
            if (!torch.isOffhand()) InvUtils.swap(torch.slot(), true);
            BlockUtils.interact(bhr, hand, swing.get());
            if (!torch.isOffhand()) InvUtils.swapBack();
        });
    }

    private PushSetup findBestSetup(BlockPos targetHead) {
        double maxDistSq = placeRange.get() * placeRange.get();
        PushSetup fallback = null;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos pistonPos = targetHead.relative(dir);
            BlockPos pushInto = targetHead.relative(dir.getOpposite());

            if (PlayerUtils.squaredDistanceTo(pistonPos) > maxDistSq) continue;
            if (!mc.level.getBlockState(targetHead).canBeReplaced()) continue;
            if (!mc.level.getBlockState(pushInto).canBeReplaced()) continue;

            BlockState pistonCur = mc.level.getBlockState(pistonPos);
            boolean pistonAlreadyOriented = pistonCur.getBlock() instanceof PistonBaseBlock
                && pistonCur.getValue(PistonBaseBlock.FACING) == dir.getOpposite();
            if (!pistonAlreadyOriented && !BlockUtils.canPlaceBlock(pistonPos, true, Blocks.PISTON)) continue;
            if (!pistonAlreadyOriented && BlockUtils.getPlaceSide(pistonPos) == null) continue;

            RedstoneChoice redstone = findRedstoneSpot(pistonPos, targetHead, pushInto, maxDistSq);
            if (redstone != null) {
                PushSetup candidate = new PushSetup(pistonPos, dir, redstone.pos, redstone.useBlock);
                if (mc.level.getBlockState(pushInto.below()).canBeReplaced()) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            }
        }
        return fallback;
    }

    private RedstoneChoice findRedstoneSpot(BlockPos pistonPos, BlockPos targetHead, BlockPos pushInto, double maxDistSq) {
        boolean hasBlock = InvUtils.findInHotbar(Items.REDSTONE_BLOCK).found();
        boolean hasTorch = InvUtils.findInHotbar(Items.REDSTONE_TORCH).found();

        boolean tryBlock = (redstoneMode.get() == RedstoneMode.RedstoneBlock || redstoneMode.get() == RedstoneMode.Auto) && hasBlock;
        boolean tryTorch = (redstoneMode.get() == RedstoneMode.RedstoneTorch || redstoneMode.get() == RedstoneMode.Auto) && hasTorch;

        Direction[] order = new Direction[]{
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
        };

        if (tryBlock) {
            for (Direction adj : order) {
                BlockPos candidate = pistonPos.relative(adj);
                if (candidate.equals(targetHead) || candidate.equals(pushInto)) continue;
                if (PlayerUtils.squaredDistanceTo(candidate) > maxDistSq) continue;
                if (BlockUtils.canPlaceBlock(candidate, true, Blocks.REDSTONE_BLOCK) && BlockUtils.getPlaceSide(candidate) != null) {
                    return new RedstoneChoice(candidate, true);
                }
            }
        }

        if (tryTorch) {
            for (Direction adj : order) {
                if (adj == Direction.UP || adj == Direction.DOWN) continue;
                BlockPos candidate = pistonPos.relative(adj);
                if (candidate.equals(targetHead) || candidate.equals(pushInto)) continue;
                if (PlayerUtils.squaredDistanceTo(candidate) > maxDistSq) continue;
                if (mc.level.getBlockState(candidate).canBeReplaced()
                    && !mc.level.getBlockState(candidate.below()).isAir()
                    && mc.level.getBlockState(candidate.below()).isSolidRender()) {
                    return new RedstoneChoice(candidate, false);
                }
            }
        }

        return null;
    }

    private FindItemResult findRedstoneItem(boolean useBlock) {
        return InvUtils.findInHotbar(useBlock ? Items.REDSTONE_BLOCK : Items.REDSTONE_TORCH);
    }

    private boolean isSurroundedOrBurrowed(Player player) {
        BlockPos feet = player.blockPosition();
        if (!mc.level.getBlockState(feet).canBeReplaced()) return true;
        int solidSides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (!mc.level.getBlockState(feet.relative(dir)).getCollisionShape(mc.level, feet.relative(dir)).isEmpty()) {
                solidSides++;
            }
        }
        return solidSides >= 3;
    }

    private static float directionToYaw(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get()) return;
        if (activePistonPos != null) {
            event.renderer.box(activePistonPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (activeRedstonePos != null) {
            event.renderer.box(activeRedstonePos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private record PushSetup(BlockPos pistonPos, Direction pistonOffsetDir, BlockPos redstonePos, boolean useBlock) {}

    private record RedstoneChoice(BlockPos pos, boolean useBlock) {}
}
