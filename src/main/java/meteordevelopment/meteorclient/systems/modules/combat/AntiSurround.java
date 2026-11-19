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
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
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
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class AntiSurround extends Module {
    public enum Mode {
        CevBreaker,
        City
    }

    public enum SwitchMode {
        Silent,
        Normal
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("CevBreaker breaks obsidian under a crystal above head; City times pre-crystal placement with surround breaking.")
        .defaultValue(Mode.CevBreaker)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Maximum range to acquire a target.")
        .defaultValue(6.0)
        .min(1.0)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> breakRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Maximum block breaking / crystal placement reach.")
        .defaultValue(4.5)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Double> preCrystalProgress = sgGeneral.add(new DoubleSetting.Builder()
        .name("pre-crystal-progress")
        .description("Mining completion ratio at which City mode pre-places the crystal.")
        .defaultValue(0.75)
        .range(0.1, 0.99)
        .sliderRange(0.5, 0.95)
        .visible(() -> mode.get() == Mode.City)
        .build()
    );

    private final Setting<Double> minCityDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-city-damage")
        .description("Minimum predicted damage to target for City crystal spot.")
        .defaultValue(5.0)
        .min(0.0)
        .sliderMax(20.0)
        .visible(() -> mode.get() == Mode.City)
        .build()
    );

    private final Setting<SwitchMode> switchMode = sgGeneral.add(new EnumSetting.Builder<SwitchMode>()
        .name("switch-mode")
        .description("How to switch to pickaxe and obsidian/crystals.")
        .defaultValue(SwitchMode.Silent)
        .build()
    );

    private final Setting<Boolean> support = sgGeneral.add(new BoolSetting.Builder()
        .name("support")
        .description("Places supporting obsidian when needed for Cev or City placement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Skips detonating crystals that would deal lethal self-damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side toward blocks and crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Renders hand swing animation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRepeat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-repeat")
        .description("Keeps cycling Cev/City attacks while enabled instead of toggling off.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the target block and crystal position.")
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
        .description("Fill color for target block highlight.")
        .defaultValue(new SettingColor(255, 60, 60, 40))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for target block highlight.")
        .defaultValue(new SettingColor(255, 60, 60, 200))
        .visible(render::get)
        .build()
    );

    private BlockPos activeMinePos;
    private BlockPos activeCrystalBase;
    private float mineProgress;
    private boolean mineStarted;
    private boolean detonatePending;
    private int detonateWaitTicks;

    public AntiSurround() {
        super(Categories.Combat, "anti-surround", "Automates Cev-Breaker (obsidian + crystal above head) and Pre-Crystal City surround breaking.");
    }

    @Override
    public void onActivate() {
        resetMineState();
    }

    @Override
    public void onDeactivate() {
        resetMineState();
    }

    private void resetMineState() {
        activeMinePos = null;
        activeCrystalBase = null;
        mineProgress = 0.0f;
        mineStarted = false;
        detonatePending = false;
        detonateWaitTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null || mc.gameMode == null) return;

        Player target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.ClosestAngle);
        if (target == null || !Friends.get().shouldAttack(target)) {
            resetMineState();
            return;
        }

        if (mode.get() == Mode.CevBreaker) {
            tickCevBreaker(target);
        } else {
            tickCity(target);
        }
    }

    private void tickCevBreaker(Player target) {
        BlockPos capPos = target.blockPosition().above(2);
        BlockPos crystalSpace = capPos.above();

        if (PlayerUtils.squaredDistanceTo(capPos) > breakRange.get() * breakRange.get()) {
            resetMineState();
            return;
        }

        // Finish detonation if block just broke
        if (detonatePending) {
            detonateWaitTicks++;
            if (mc.level.getBlockState(capPos).canBeReplaced()) {
                EndCrystal crystal = findCrystalAbove(capPos);
                if (crystal != null && tryBreakCrystal(crystal)) {
                    detonatePending = false;
                    detonateWaitTicks = 0;
                    mineStarted = false;
                    mineProgress = 0.0f;
                    if (!autoRepeat.get()) toggle();
                    return;
                }
            } else if (detonateWaitTicks <= 15 && mineProgress >= 1.0f) {
                EndCrystal crystal = findCrystalAbove(capPos);
                if (crystal != null) {
                    FindItemResult pick = findPickaxe();
                    if (pick.found()) sendMineAction(capPos, pick, true);
                    return;
                }
            }
            if (detonateWaitTicks > 15) {
                mineStarted = false;
                mineProgress = 0.0f;
            }
            detonatePending = false;
            detonateWaitTicks = 0;
        }

        BlockState capState = mc.level.getBlockState(capPos);
        if (capState.getDestroySpeed(mc.level, capPos) < 0) return; // Bedrock cap

        activeMinePos = capPos;
        activeCrystalBase = capPos;

        if (capState.canBeReplaced()) {
            mineStarted = false;
            mineProgress = 0.0f;

            // Break stray crystal occupying capPos if needed before placing
            EndCrystal blockingCrystal = findCrystalAt(capPos);
            if (blockingCrystal != null) {
                tryBreakCrystal(blockingCrystal);
                return;
            }

            FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
            if (!obsidian.found()) return;

            if (BlockUtils.getPlaceSide(capPos) == null) {
                if (!support.get() || !placeSupportFor(capPos, obsidian)) return;
            }
            BlockUtils.place(capPos, obsidian, rotate.get(), 80, swingHand.get(), true, switchMode.get() == SwitchMode.Silent);
            return;
        }

        // Ensure crystal exists above capPos
        EndCrystal existing = findCrystalAbove(capPos);
        if (existing == null) {
            if (!mc.level.isEmptyBlock(crystalSpace)) return;
            placeCrystalOn(capPos);
        }

        FindItemResult pick = findPickaxe();
        if (!pick.found()) return;

        if (!mineStarted) {
            mineProgress = 0.0f;
            mineStarted = true;
            sendMineAction(capPos, pick, false);
        }

        mineProgress += BlockUtils.getBreakDelta(pick.slot(), capState);
        if (mineProgress >= 1.0f && findCrystalAbove(capPos) != null) {
            sendMineAction(capPos, pick, true);
            detonatePending = true;
            detonateWaitTicks = 0;
        }
    }

    private void tickCity(Player target) {
        if (detonatePending) {
            detonateWaitTicks++;
            if (activeCrystalBase != null) {
                EndCrystal crystal = findCrystalAbove(activeCrystalBase);
                if (crystal != null && (activeMinePos == null || mc.level.getBlockState(activeMinePos).canBeReplaced())) {
                    if (tryBreakCrystal(crystal)) {
                        detonatePending = false;
                        detonateWaitTicks = 0;
                        mineStarted = false;
                        mineProgress = 0.0f;
                        if (!autoRepeat.get()) toggle();
                        return;
                    }
                }
            }
            if ((activeMinePos != null && mc.level.getBlockState(activeMinePos).canBeReplaced()) || detonateWaitTicks > 15) {
                if (detonateWaitTicks > 15) {
                    mineStarted = false;
                    mineProgress = 0.0f;
                }
                detonatePending = false;
                detonateWaitTicks = 0;
            }
        }

        BlockPos cityPos = EntityUtils.getCityBlock(target);
        if (cityPos == null || PlayerUtils.squaredDistanceTo(cityPos) > breakRange.get() * breakRange.get()) {
            resetMineState();
            return;
        }

        if (!cityPos.equals(activeMinePos)) {
            activeMinePos = cityPos;
            mineProgress = 0.0f;
            mineStarted = false;
            detonatePending = false;
        }

        BlockPos preBase = findBestPreCrystalBase(target, cityPos);
        activeCrystalBase = preBase != null ? preBase : cityPos.below();

        BlockState cityState = mc.level.getBlockState(cityPos);
        if (cityState.canBeReplaced()) {
            mineStarted = false;
            mineProgress = 0.0f;
            if (activeCrystalBase != null && !activeCrystalBase.equals(cityPos)) {
                EndCrystal crystal = findCrystalAbove(activeCrystalBase);
                if (crystal == null) {
                    if (mc.level.getBlockState(activeCrystalBase).canBeReplaced() && support.get()) {
                        FindItemResult obs = InvUtils.findInHotbar(Items.OBSIDIAN);
                        if (obs.found()) BlockUtils.place(activeCrystalBase, obs, rotate.get(), 75, swingHand.get(), true, switchMode.get() == SwitchMode.Silent);
                    } else if (isCrystalBaseValid(activeCrystalBase)) {
                        placeCrystalOn(activeCrystalBase);
                    }
                } else {
                    if (tryBreakCrystal(crystal) && !autoRepeat.get()) {
                        toggle();
                    }
                }
            }
            return;
        }

        FindItemResult pick = findPickaxe();
        if (!pick.found()) return;

        if (!mineStarted) {
            mineProgress = 0.0f;
            mineStarted = true;
            sendMineAction(cityPos, pick, false);
        }

        mineProgress += BlockUtils.getBreakDelta(pick.slot(), cityState);

        // Pre-crystal placement timing on outer support base before city block finishes breaking
        if (preBase != null && mineProgress >= preCrystalProgress.get()) {
            if (mc.level.getBlockState(preBase).canBeReplaced() && support.get()) {
                FindItemResult obs = InvUtils.findInHotbar(Items.OBSIDIAN);
                if (obs.found()) {
                    BlockUtils.place(preBase, obs, rotate.get(), 75, swingHand.get(), true, switchMode.get() == SwitchMode.Silent);
                }
            } else if (isCrystalBaseValid(preBase) && findCrystalAbove(preBase) == null) {
                placeCrystalOn(preBase);
            }
        }

        if (mineProgress >= 1.0f) {
            sendMineAction(cityPos, pick, true);
            detonatePending = true;
            detonateWaitTicks = 0;
        }
    }

    private BlockPos findBestPreCrystalBase(Player target, BlockPos cityPos) {
        BlockPos targetFeet = target.blockPosition();
        BlockPos best = null;
        float bestDmg = minCityDamage.get().floatValue();

        DamageUtils.RaycastFactory ignoringCity = (ctx, pos) -> {
            if (pos.equals(cityPos)) return null;
            return DamageUtils.HIT_FACTORY.apply(ctx, pos);
        };

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos outer = cityPos.relative(dir);
            if (outer.equals(targetFeet) || outer.equals(targetFeet.above())) continue;
            BlockPos base = outer.below();
            if (PlayerUtils.squaredDistanceTo(base) > breakRange.get() * breakRange.get()) continue;

            boolean baseReady = isCrystalBaseValid(base)
                || (support.get() && mc.level.getBlockState(base).canBeReplaced() && InvUtils.findInHotbar(Items.OBSIDIAN).found());
            if (!baseReady || !mc.level.isEmptyBlock(outer)) continue;

            Vec3 crystalVec = new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            float dmg = DamageUtils.crystalDamage(target, target.position(), target.getBoundingBox(), crystalVec, ignoringCity);
            if (dmg >= bestDmg) {
                if (antiSuicide.get()) {
                    float selfDmg = DamageUtils.crystalDamage(mc.player, mc.player.position(), mc.player.getBoundingBox(), crystalVec, DamageUtils.HIT_FACTORY);
                    if (selfDmg >= PlayerUtils.getTotalHealth() - 1.0f) continue;
                }
                bestDmg = dmg;
                best = base;
            }
        }
        return best;
    }

    private boolean isCrystalBaseValid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) && mc.level.isEmptyBlock(pos.above());
    }

    private boolean placeSupportFor(BlockPos targetPos, FindItemResult obsidian) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.UP) continue;
            BlockPos candidate = targetPos.relative(dir);
            if (PlayerUtils.squaredDistanceTo(candidate) > breakRange.get() * breakRange.get()) continue;
            if (BlockUtils.getPlaceSide(candidate) != null
                && BlockUtils.place(candidate, obsidian, rotate.get(), 75, swingHand.get(), true, switchMode.get() == SwitchMode.Silent)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeCrystalOn(BlockPos basePos) {
        FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystal.found() || mc.player == null || mc.gameMode == null) return false;

        Vec3 hitVec = new Vec3(basePos.getX() + 0.5, basePos.getY() + 1.0, basePos.getZ() + 0.5);
        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, basePos, false);
        InteractionHand hand = crystal.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean silent = switchMode.get() == SwitchMode.Silent && !crystal.isOffhand();

        Runnable doPlace = () -> {
            if (!crystal.isOffhand()) InvUtils.swap(crystal.slot(), silent);
            mc.gameMode.useItemOn(mc.player, hand, bhr);
            if (swingHand.get()) PlayerUtils.swingHand(hand, true);
            if (silent) InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 85, doPlace);
        } else {
            doPlace.run();
        }
        return true;
    }

    private boolean tryBreakCrystal(EndCrystal crystal) {
        if (mc.player == null || mc.player.connection == null) return false;
        if (antiSuicide.get()) {
            float selfDmg = DamageUtils.crystalDamage(mc.player, mc.player.position(), mc.player.getBoundingBox(), crystal.position(), DamageUtils.HIT_FACTORY);
            if (selfDmg >= PlayerUtils.getTotalHealth() - 1.0f) return false;
        }

        Runnable doHit = () -> {
            mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
            PlayerUtils.punch(swingHand.get());
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(crystal), Rotations.getPitch(crystal), 90, doHit);
        } else {
            doHit.run();
        }
        return true;
    }

    private EndCrystal findCrystalAbove(BlockPos basePos) {
        AABB box = new AABB(
            basePos.getX() - 0.5, basePos.getY() + 1.0, basePos.getZ() - 0.5,
            basePos.getX() + 1.5, basePos.getY() + 3.0, basePos.getZ() + 1.5
        );
        List<Entity> list = mc.level.getEntities((Entity) null, box, e -> e instanceof EndCrystal && e.isAlive());
        return list.isEmpty() ? null : (EndCrystal) list.getFirst();
    }

    private EndCrystal findCrystalAt(BlockPos pos) {
        AABB box = new AABB(pos);
        List<Entity> list = mc.level.getEntities((Entity) null, box, e -> e instanceof EndCrystal && e.isAlive());
        return list.isEmpty() ? null : (EndCrystal) list.getFirst();
    }

    private FindItemResult findPickaxe() {
        return InvUtils.findInHotbar(stack -> stack.is(Items.NETHERITE_PICKAXE) || stack.is(Items.DIAMOND_PICKAXE));
    }

    private void sendMineAction(BlockPos pos, FindItemResult pick, boolean stop) {
        if (mc.player == null || mc.gameMode == null || mc.level == null) return;
        boolean silent = switchMode.get() == SwitchMode.Silent;
        Direction dir = BlockUtils.getDirection(pos);

        Runnable act = () -> {
            InvUtils.swap(pick.slot(), silent);
            ServerboundPlayerActionPacket.Action action = stop
                ? ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                : ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK;
            mc.gameMode.startPrediction(mc.level, sequence -> new ServerboundPlayerActionPacket(action, pos, dir, sequence));
            PlayerUtils.punch(swingHand.get());
            if (silent) InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 80, act);
        } else {
            act.run();
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || activeMinePos == null) return;
        event.renderer.box(activeMinePos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        if (activeCrystalBase != null && !activeCrystalBase.equals(activeMinePos)) {
            event.renderer.box(activeCrystalBase, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
