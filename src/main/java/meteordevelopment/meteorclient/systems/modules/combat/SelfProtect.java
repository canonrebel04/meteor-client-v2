/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;

public class SelfProtect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> antiCev = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-cev")
        .description("Breaks crystals placed above your head and maintains an obsidian head cap.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> alwaysCapHead = sgGeneral.add(new BoolSetting.Builder()
        .name("always-cap-head")
        .description("Keeps an obsidian cap above your head even before a crystal is placed.")
        .defaultValue(false)
        .visible(antiCev::get)
        .build()
    );

    private final Setting<Boolean> antiPiston = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-piston")
        .description("Places obsidian blockers behind your head and breaks adjacent piston redstone when pushed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> breakPistonRedstone = sgGeneral.add(new BoolSetting.Builder()
        .name("break-piston-redstone")
        .description("Attempts to break redstone torches/blocks powering an attacking piston.")
        .defaultValue(true)
        .visible(antiPiston::get)
        .build()
    );

    private final Setting<Boolean> wallReinforce = sgGeneral.add(new BoolSetting.Builder()
        .name("wall-reinforce")
        .description("Listens to enemy block destruction packets on your surround walls to clear crystals and reinforce.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minBreakProgress = sgGeneral.add(new IntSetting.Builder()
        .name("min-break-progress")
        .description("Block destruction progress (0-9) at which surround walls are reinforced.")
        .defaultValue(5)
        .range(0, 9)
        .sliderRange(0, 9)
        .visible(wallReinforce::get)
        .build()
    );

    private final Setting<Boolean> outerReinforce = sgGeneral.add(new BoolSetting.Builder()
        .name("outer-reinforce")
        .description("Places an outer obsidian layer outside surround blocks being mined.")
        .defaultValue(true)
        .visible(wallReinforce::get)
        .build()
    );

    private final Setting<Boolean> support = sgGeneral.add(new BoolSetting.Builder()
        .name("support")
        .description("Places supporting obsidian when needed for head cap or reinforcement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side for defensive placements and crystal breaks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders hand swing animation.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights actively defended block positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How defended block boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color for defended positions.")
        .defaultValue(new SettingColor(0, 210, 255, 35))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color for defended positions.")
        .defaultValue(new SettingColor(0, 210, 255, 200))
        .visible(render::get)
        .build()
    );

    private record BreakProgress(int progress, long timestampMs) {}

    private final Map<BlockPos, BreakProgress> activeBreakProgress = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> recentlyMinedWalls = new ConcurrentHashMap<>();
    private BlockPos lastProtectedPos;

    public SelfProtect() {
        super(Categories.Combat, "self-protect", "Defends against Cev-Breaker, Piston-Push, and active surround wall mining.");
    }

    @Override
    public void onDeactivate() {
        activeBreakProgress.clear();
        recentlyMinedWalls.clear();
        lastProtectedPos = null;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || !(event.packet instanceof ClientboundBlockDestructionPacket packet)) return;

        int progress = packet.getProgress();
        BlockPos pos = packet.getPos().immutable();
        long now = System.currentTimeMillis();
        if (progress < 0 || progress > 9) {
            if (activeBreakProgress.remove(pos) != null) {
                recentlyMinedWalls.put(pos, now);
            }
        } else {
            activeBreakProgress.put(pos, new BreakProgress(progress, now));
            recentlyMinedWalls.put(pos, now);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null) return;

        long now = System.currentTimeMillis();
        activeBreakProgress.entrySet().removeIf(e -> now - e.getValue().timestampMs > 3000L);
        recentlyMinedWalls.entrySet().removeIf(e -> now - e.getValue() > 3000L);

        lastProtectedPos = null;
        BlockPos playerPos = mc.player.blockPosition();
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);

        if (antiCev.get()) {
            handleAntiCev(playerPos, obsidian);
        }
        if (antiPiston.get()) {
            handleAntiPiston(playerPos, obsidian);
        }
        if (wallReinforce.get()) {
            handleWallReinforce(playerPos, obsidian);
        }
    }

    private void handleAntiCev(BlockPos playerPos, FindItemResult obsidian) {
        BlockPos headCap = playerPos.above(2);
        BlockPos crystalSpace = headCap.above();

        AABB cevBox = new AABB(
            crystalSpace.getX() - 0.6, headCap.getY(), crystalSpace.getZ() - 0.6,
            crystalSpace.getX() + 1.6, crystalSpace.getY() + 2.0, crystalSpace.getZ() + 1.6
        );

        boolean threatDetected = alwaysCapHead.get() || isBeingMined(headCap, 0);
        List<Entity> crystals = mc.level.getEntities((Entity) null, cevBox, e -> e instanceof EndCrystal && e.isAlive());
        for (Entity entity : crystals) {
            threatDetected = true;
            breakCrystalSafely((EndCrystal) entity);
        }

        if (threatDetected && obsidian.found() && mc.level.getBlockState(headCap).canBeReplaced()) {
            lastProtectedPos = headCap;
            placeWithOptionalSupport(headCap, obsidian, 95);
        }
    }

    private void handleAntiPiston(BlockPos playerPos, FindItemResult obsidian) {
        BlockPos head = playerPos.above();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos checkPos = head.relative(dir);
            BlockState state = mc.level.getBlockState(checkPos);
            if (!(state.getBlock() instanceof PistonBaseBlock)) continue;
            if (state.getValue(PistonBaseBlock.FACING) != dir.getOpposite()) continue;

            // Place blocker behind our head so piston cannot eject us out of the hole
            BlockPos backBlocker = head.relative(dir.getOpposite());
            if (obsidian.found() && mc.level.getBlockState(backBlocker).canBeReplaced()) {
                lastProtectedPos = backBlocker;
                placeWithOptionalSupport(backBlocker, obsidian, 95);
            }

            // Cap above head as well
            BlockPos ceiling = playerPos.above(2);
            if (obsidian.found() && mc.level.getBlockState(ceiling).canBeReplaced()) {
                lastProtectedPos = ceiling;
                placeWithOptionalSupport(ceiling, obsidian, 92);
            }

            // Try punching redstone torches or starting break on redstone blocks powering the piston
            if (breakPistonRedstone.get()) {
                for (Direction adj : Direction.values()) {
                    BlockPos powerPos = checkPos.relative(adj);
                    Block b = mc.level.getBlockState(powerPos).getBlock();
                    if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH || b == Blocks.REDSTONE_BLOCK) {
                        instantBreakPower(powerPos);
                        break;
                    }
                }
            }
        }
    }

    private void handleWallReinforce(BlockPos playerPos, FindItemResult obsidian) {
        int threshold = minBreakProgress.get();
        int solidWalls = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wall = playerPos.relative(dir);
            if (!mc.level.getBlockState(wall).canBeReplaced()) {
                solidWalls++;
            }
        }
        boolean inSurround = solidWalls >= 3;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos wall = playerPos.relative(dir);
            BlockState wallState = mc.level.getBlockState(wall);
            boolean mined = isBeingMined(wall, threshold);
            boolean wasRecentlyMined = recentlyMinedWalls.containsKey(wall);

            if (wallState.canBeReplaced()) {
                if (!inSurround && !wasRecentlyMined) continue;
                breakCrystalsNear(wall);
                if (obsidian.found()) {
                    lastProtectedPos = wall;
                    placeWithOptionalSupport(wall, obsidian, 90);
                }
                continue;
            }

            if (mined) {
                recentlyMinedWalls.put(wall, System.currentTimeMillis());
                lastProtectedPos = wall;
                breakCrystalsNear(wall);

                if (outerReinforce.get() && obsidian.found()) {
                    BlockPos outer = wall.relative(dir);
                    if (mc.level.getBlockState(outer).canBeReplaced()) {
                        placeWithOptionalSupport(outer, obsidian, 85);
                    }
                }
            }
        }
    }

    private void breakCrystalsNear(BlockPos pos) {
        AABB box = new AABB(pos).inflate(1.5);
        List<Entity> crystals = mc.level.getEntities((Entity) null, box, e -> e instanceof EndCrystal && e.isAlive());
        for (Entity entity : crystals) {
            breakCrystalSafely((EndCrystal) entity);
        }
    }

    private void breakCrystalSafely(EndCrystal crystal) {
        if (mc.player == null || mc.player.connection == null) return;
        float selfDmg = DamageUtils.crystalDamage(mc.player, mc.player.position(), mc.player.getBoundingBox(), crystal.position(), DamageUtils.HIT_FACTORY);
        if (selfDmg >= PlayerUtils.getTotalHealth() - 1.0f) return;

        Runnable hit = () -> {
            mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
            PlayerUtils.punch(swing.get());
        };
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 95, hit);
        } else {
            hit.run();
        }
    }

    private void placeWithOptionalSupport(BlockPos pos, FindItemResult obsidian, int priority) {
        if (PlayerUtils.squaredDistanceTo(pos) > 25.0) return;
        if (BlockUtils.getPlaceSide(pos) == null) {
            if (!support.get()) return;
            for (Direction dir : Direction.values()) {
                if (dir == Direction.UP) continue;
                BlockPos sup = pos.relative(dir);
                if (PlayerUtils.squaredDistanceTo(sup) <= 25.0
                    && BlockUtils.getPlaceSide(sup) != null
                    && mc.level.getBlockState(sup).canBeReplaced()) {
                    BlockUtils.place(sup, obsidian, rotate.get(), priority - 5, swing.get(), true);
                    return;
                }
            }
            return;
        }
        BlockUtils.place(pos, obsidian, rotate.get(), priority, swing.get(), true);
    }

    private void instantBreakPower(BlockPos pos) {
        if (mc.gameMode == null || mc.level == null) return;
        Direction dir = BlockUtils.getDirection(pos);
        Runnable punch = () -> {
            mc.gameMode.startPrediction(mc.level, seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, dir, seq));
            mc.gameMode.startPrediction(mc.level, seq -> new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, dir, seq));
            PlayerUtils.punch(swing.get());
        };
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 90, punch);
        } else {
            punch.run();
        }
    }

    private boolean isBeingMined(BlockPos pos, int minProgress) {
        BreakProgress tracked = activeBreakProgress.get(pos);
        if (tracked != null && tracked.progress >= minProgress) {
            return true;
        }
        if (mc.level == null) return false;
        for (SortedSet<BlockDestructionProgress> set : mc.level.destructionProgress().values()) {
            if (set.isEmpty()) continue;
            BlockDestructionProgress last = set.last();
            if (last.getPos().equals(pos) && last.getProgress() >= minProgress) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || lastProtectedPos == null) return;
        event.renderer.box(lastProtectedPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
