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
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class Blocker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> checkRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("check-range")
        .description("Radius around player to scan for explosion threats.")
        .defaultValue(7.0)
        .min(2.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum predicted explosion damage before placing an obsidian blast shield.")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Double> minExposure = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-exposure")
        .description("Minimum player hitbox exposure to trigger shield placement.")
        .defaultValue(0.25)
        .range(0.05, 1.0)
        .sliderRange(0.1, 1.0)
        .build()
    );

    private final Setting<Boolean> crystals = sgGeneral.add(new BoolSetting.Builder()
        .name("crystals")
        .description("Shields against nearby End Crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> anchors = sgGeneral.add(new BoolSetting.Builder()
        .name("anchors")
        .description("Shields against nearby charged Respawn Anchors.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> beds = sgGeneral.add(new BoolSetting.Builder()
        .name("beds")
        .description("Shields against nearby explosive Beds in the Nether/End.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only places shield obsidian while on ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> support = sgGeneral.add(new BoolSetting.Builder()
        .name("support")
        .description("Places supporting obsidian below foot shield if open air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side toward the shield placement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Renders hand swing animation.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Highlights the active blast shield block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shield box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color for the shield block.")
        .defaultValue(new SettingColor(50, 180, 255, 45))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color for the shield block.")
        .defaultValue(new SettingColor(50, 180, 255, 220))
        .visible(render::get)
        .build()
    );

    private enum ThreatKind {
        CRYSTAL,
        ANCHOR,
        BED
    }

    private record Threat(Vec3 pos, BlockPos sourceBlock, ThreatKind kind, float damage) {}

    private BlockPos activeShieldPos;

    public Blocker() {
        super(Categories.Combat, "blocker", "Places blast-shield obsidian at your feet between you and high-exposure crystals, anchors, or beds.");
    }

    @Override
    public void onDeactivate() {
        activeShieldPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null) return;
        activeShieldPos = null;

        if (onlyOnGround.get() && !mc.player.onGround()) return;

        FindItemResult block = InvUtils.findInHotbar(Items.OBSIDIAN, Items.CRYING_OBSIDIAN, Items.ENDER_CHEST);
        if (!block.found()) return;

        Threat worst = findHighestThreat();
        if (worst == null) return;

        BlockPos shieldPos = findBestShieldBlock(worst, block.found());
        if (shieldPos == null) return;

        activeShieldPos = shieldPos;
        if (BlockUtils.getPlaceSide(shieldPos) == null) {
            if (!support.get()) return;
            BlockPos below = shieldPos.below();
            if (BlockUtils.getPlaceSide(below) != null && BlockUtils.canPlaceBlock(below, true, Blocks.OBSIDIAN)) {
                BlockUtils.place(below, block, rotate.get(), 80, swing.get(), true);
            }
            return;
        }
        BlockUtils.place(shieldPos, block, rotate.get(), 85, swing.get(), true);
    }

    private Threat findHighestThreat() {
        List<Threat> threats = new ArrayList<>();
        Vec3 playerPos = mc.player.position();
        AABB playerBox = mc.player.getBoundingBox();
        double rangeSq = checkRange.get() * checkRange.get();

        if (crystals.get()) {
            AABB scanBox = new AABB(playerPos, playerPos).inflate(checkRange.get());
            for (Entity entity : mc.level.getEntities((Entity) null, scanBox, e -> e instanceof EndCrystal && e.isAlive())) {
                if (entity.distanceToSqr(mc.player) > rangeSq) continue;
                Vec3 pos = entity.position();
                double exposure = computeExposure(pos, playerBox, DamageUtils.HIT_FACTORY);
                if (exposure < minExposure.get()) continue;
                float dmg = DamageUtils.crystalDamage(mc.player, playerPos, playerBox, pos, DamageUtils.HIT_FACTORY);
                if (dmg >= minDamage.get()) {
                    threats.add(new Threat(pos, null, ThreatKind.CRYSTAL, dmg));
                }
            }
        }

        boolean scanAnchors = anchors.get();
        boolean scanBeds = beds.get() && (mc.level.dimension() == Level.NETHER || mc.level.dimension() == Level.END);
        if (scanAnchors || scanBeds) {
            int r = (int) Math.ceil(checkRange.get());
            BlockPos center = mc.player.blockPosition();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        BlockState state = mc.level.getBlockState(cursor);
                        if (scanAnchors && state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                            Vec3 pos = Vec3.atCenterOf(cursor);
                            if (pos.distanceToSqr(playerPos) > rangeSq) continue;
                            BlockPos anchorPos = cursor.immutable();
                            DamageUtils.RaycastFactory ignoreSelf = DamageUtils.getOverridingHitFactory(anchorPos, Blocks.AIR.defaultBlockState());
                            double exposure = computeExposure(pos, playerBox, ignoreSelf);
                            if (exposure < minExposure.get()) continue;
                            float dmg = DamageUtils.anchorDamage(mc.player, playerPos, playerBox, pos, ignoreSelf);
                            if (dmg >= minDamage.get()) {
                                threats.add(new Threat(pos, anchorPos, ThreatKind.ANCHOR, dmg));
                            }
                        } else if (scanBeds && state.getBlock() instanceof BedBlock) {
                            Vec3 pos = Vec3.atCenterOf(cursor);
                            if (pos.distanceToSqr(playerPos) > rangeSq) continue;
                            BlockPos bedPos = cursor.immutable();
                            DamageUtils.RaycastFactory ignoreSelf = DamageUtils.getOverridingHitFactory(bedPos, Blocks.AIR.defaultBlockState());
                            double exposure = computeExposure(pos, playerBox, ignoreSelf);
                            if (exposure < minExposure.get()) continue;
                            float dmg = DamageUtils.bedDamage(mc.player, playerPos, playerBox, pos, ignoreSelf);
                            if (dmg >= minDamage.get()) {
                                threats.add(new Threat(pos, bedPos, ThreatKind.BED, dmg));
                            }
                        }
                    }
                }
            }
        }

        Threat worst = null;
        for (Threat threat : threats) {
            if (worst == null || threat.damage > worst.damage) {
                worst = threat;
            }
        }
        return worst;
    }

    private BlockPos findBestShieldBlock(Threat threat, boolean hasBlock) {
        if (!hasBlock) return null;

        BlockPos playerPos = mc.player.blockPosition();
        Vec3 playerVec = mc.player.position();
        AABB playerBox = mc.player.getBoundingBox();

        BlockPos bestPos = null;
        float bestMitigated = threat.damage;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = playerPos.relative(dir);
            if (!BlockUtils.canPlaceBlock(candidate, true, Blocks.OBSIDIAN)) continue;
            if (BlockUtils.getPlaceSide(candidate) == null
                && (!support.get() || BlockUtils.getPlaceSide(candidate.below()) == null)) {
                continue;
            }

            DamageUtils.RaycastFactory withShield = (ctx, pos) -> {
                if (pos.equals(candidate)) {
                    return Blocks.OBSIDIAN.defaultBlockState().getCollisionShape(mc.level, pos).clip(ctx.start(), ctx.end(), pos);
                }
                if (threat.sourceBlock != null && pos.equals(threat.sourceBlock)) {
                    return null;
                }
                return DamageUtils.HIT_FACTORY.apply(ctx, pos);
            };

            float mitigated = switch (threat.kind) {
                case CRYSTAL -> DamageUtils.crystalDamage(mc.player, playerVec, playerBox, threat.pos, withShield);
                case ANCHOR -> DamageUtils.anchorDamage(mc.player, playerVec, playerBox, threat.pos, withShield);
                case BED -> DamageUtils.bedDamage(mc.player, playerVec, playerBox, threat.pos, withShield);
            };

            if (mitigated < bestMitigated - 0.5f) {
                bestMitigated = mitigated;
                bestPos = candidate;
            }
        }

        return bestPos;
    }

    private static float computeExposure(Vec3 source, AABB box, DamageUtils.RaycastFactory raycastFactory) {
        double xDiff = box.maxX - box.minX;
        double yDiff = box.maxY - box.minY;
        double zDiff = box.maxZ - box.minZ;

        double xStep = 1.0 / (xDiff * 2.0 + 1.0);
        double yStep = 1.0 / (yDiff * 2.0 + 1.0);
        double zStep = 1.0 / (zDiff * 2.0 + 1.0);

        if (xStep <= 0.0 || yStep <= 0.0 || zStep <= 0.0) return 0f;

        int misses = 0;
        int hits = 0;

        double xOffset = (1.0 - Math.floor(1.0 / xStep) * xStep) * 0.5;
        double zOffset = (1.0 - Math.floor(1.0 / zStep) * zStep) * 0.5;

        xStep *= xDiff;
        yStep *= yDiff;
        zStep *= zDiff;

        double startX = box.minX + xOffset;
        double startY = box.minY;
        double startZ = box.minZ + zOffset;
        double endX = box.maxX + xOffset;
        double endY = box.maxY;
        double endZ = box.maxZ + zOffset;

        for (double x = startX; x <= endX; x += xStep) {
            for (double y = startY; y <= endY; y += yStep) {
                for (double z = startZ; z <= endZ; z += zStep) {
                    DamageUtils.ExposureRaycastContext ctx = new DamageUtils.ExposureRaycastContext(new Vec3(x, y, z), source);
                    if (BlockGetter.traverseBlocks(ctx.start(), ctx.end(), ctx, raycastFactory, _ -> null) == null) {
                        misses++;
                    }
                    hits++;
                }
            }
        }
        return hits == 0 ? 0f : (float) misses / hits;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || activeShieldPos == null) return;
        event.renderer.box(activeShieldPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
