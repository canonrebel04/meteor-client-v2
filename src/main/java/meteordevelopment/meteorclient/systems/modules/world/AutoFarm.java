/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Harvests mature crops, replants them and optionally bonemeals growing ones. */
public class AutoFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Interaction range.").defaultValue(4.5).range(1, 6).sliderRange(1, 6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between actions.").defaultValue(2).range(0, 20).sliderRange(0, 20).build());

    private final Setting<Integer> actionsPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("actions-per-tick").description("Max blocks handled per action tick.").defaultValue(1).range(1, 8).sliderRange(1, 8).build());

    private final Setting<Boolean> harvest = sgGeneral.add(new BoolSetting.Builder()
        .name("harvest").description("Break mature crops.").defaultValue(true).build());

    private final Setting<Boolean> gourds = sgGeneral.add(new BoolSetting.Builder()
        .name("pumpkins-melons").description("Harvest pumpkins and melons (not stems).").defaultValue(true).visible(harvest::get).build());

    private final Setting<Boolean> stacked = sgGeneral.add(new BoolSetting.Builder()
        .name("sugar-cane-cactus-bamboo").description("Harvest tall plants, keeping the bottom block.").defaultValue(true).visible(harvest::get).build());

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant").description("Replant harvested crops from the hotbar.").defaultValue(true).build());

    private final Setting<Boolean> bonemeal = sgGeneral.add(new BoolSetting.Builder()
        .name("bonemeal").description("Use bone meal on growing crops.").defaultValue(false).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rotate toward the block before acting.").defaultValue(true).build());

    private final Map<BlockPos, Item> toReplant = new LinkedHashMap<>();
    private final List<BlockPos> harvestList = new ArrayList<>();
    private final List<BlockPos> bonemealList = new ArrayList<>();
    private int timer;

    public AutoFarm() {
        super(Categories.World, "auto-farm", "Harvests, replants and bonemeals crops around you.");
    }

    @Override
    public void onActivate() {
        toReplant.clear();
        timer = 0;
    }

    private static AutoFarmLogic.Kind kindOf(Block b) {
        if (b instanceof CropBlock || b instanceof NetherWartBlock || b instanceof CocoaBlock) return AutoFarmLogic.Kind.AGED;
        if (b == Blocks.PUMPKIN || b == Blocks.MELON) return AutoFarmLogic.Kind.GOURD;
        if (b == Blocks.SUGAR_CANE || b == Blocks.CACTUS || b == Blocks.BAMBOO) return AutoFarmLogic.Kind.STACKED;
        return AutoFarmLogic.Kind.NONE;
    }

    private static int age(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock c) return c.getAge(s);
        if (b instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE);
        if (b instanceof CocoaBlock) return s.getValue(CocoaBlock.AGE);
        return 0;
    }

    private static int maxAge(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock c) return c.getMaxAge();
        if (b instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (b instanceof CocoaBlock) return CocoaBlock.MAX_AGE;
        return 0;
    }

    private boolean inRange(BlockPos p) {
        return Utils.squaredDistance(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= range.get() * range.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;
        if (timer-- > 0) return;

        harvestList.clear();
        bonemealList.clear();
        int r = (int) Math.ceil(range.get());

        BlockIterator.register(r, r, (pos, state) -> {
            if (!inRange(pos)) return;
            Block b = state.getBlock();
            AutoFarmLogic.Kind k = kindOf(b);
            if (k == AutoFarmLogic.Kind.NONE) return;
            if (k == AutoFarmLogic.Kind.GOURD && !gourds.get()) return;
            if (k == AutoFarmLogic.Kind.STACKED && !stacked.get()) return;
            boolean sameBelow = k == AutoFarmLogic.Kind.STACKED && mc.level.getBlockState(pos.below()).getBlock() == b;
            int a = age(state), m = maxAge(state);
            if (harvest.get() && AutoFarmLogic.shouldHarvest(k, a, m, sameBelow)) harvestList.add(pos.immutable());
            else if (bonemeal.get() && AutoFarmLogic.shouldBonemeal(k, a, m)) bonemealList.add(pos.immutable());
        });

        BlockIterator.after(this::act);
    }

    private void act() {
        if (mc.player == null) return;
        int budget = actionsPerTick.get();
        Comparator<BlockPos> byDist = Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p)));

        // 1) Replant
        if (replant.get()) {
            Iterator<Map.Entry<BlockPos, Item>> it = toReplant.entrySet().iterator();
            while (it.hasNext() && budget > 0) {
                Map.Entry<BlockPos, Item> e = it.next();
                BlockPos pos = e.getKey();
                if (!inRange(pos)) { it.remove(); continue; }
                if (!mc.level.getBlockState(pos).isAir()) { it.remove(); continue; }
                FindItemResult seed = InvUtils.findInHotbar(e.getValue());
                if (!seed.found()) continue;
                BlockPos below = pos.below();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
                useOn(seed, hit);
                it.remove();
                budget--;
            }
        }

        // 2) Harvest
        harvestList.sort(byDist);
        for (BlockPos pos : harvestList) {
            if (budget <= 0) break;
            BlockState state = mc.level.getBlockState(pos);
            Block b = state.getBlock();
            if (kindOf(b) == AutoFarmLogic.Kind.AGED && !(b instanceof CocoaBlock)) toReplant.put(pos, b.asItem());
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> BlockUtils.breakBlock(pos, true));
            else BlockUtils.breakBlock(pos, true);
            budget--;
        }

        // 3) Bonemeal
        if (budget > 0 && !bonemealList.isEmpty()) {
            FindItemResult bm = InvUtils.findInHotbar(Items.BONE_MEAL);
            if (bm.found()) {
                bonemealList.sort(byDist);
                for (BlockPos pos : bonemealList) {
                    if (budget-- <= 0) break;
                    useOn(bm, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                }
            }
        }

        timer = delay.get();
    }

    private void useOn(FindItemResult item, BlockHitResult hit) {
        Runnable run = () -> {
            if (item.isOffhand()) {
                BlockUtils.interact(hit, InteractionHand.OFF_HAND, true);
            } else {
                InvUtils.swap(item.slot(), true);
                BlockUtils.interact(hit, InteractionHand.MAIN_HAND, true);
                InvUtils.swapBack();
            }
        };
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(hit.getBlockPos()), Rotations.getPitch(hit.getBlockPos()), run);
        else run.run();
    }
}
