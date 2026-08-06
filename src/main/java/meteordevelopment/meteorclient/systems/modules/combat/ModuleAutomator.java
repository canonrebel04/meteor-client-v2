/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Jesus;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Rule-based module automator for CombatBrain.
 * Evaluates tactical rules every tick and toggles modules strictly on state change.
 */
public class ModuleAutomator {
    private final CombatBrainModule brain;
    private final Set<Class<? extends Module>> enabledByAutomator = new HashSet<>();
    private final List<String> activeRuleNames = new ArrayList<>();

    /** Dwell timer (in update() passes) keeping Jesus active after leaving water to stop surface-bobbing flapping. */
    private int waterRuleTimer;

    /** Per-rule cooldown tracker: maps rule name -> tick of last toggle to prevent rapid flapping. */
    private final java.util.HashMap<String, Integer> lastRuleToggleTick = new java.util.HashMap<>();
    private int automatorTickCounter = 0;
    private static final int DEFAULT_RULE_COOLDOWN = 20;

    public ModuleAutomator(CombatBrainModule brain) {
        this.brain = brain;
    }

    public void update(LivingEntity target, CombatTerrainGrid grid) {
        if (mc.player == null || mc.level == null) return;
        automatorTickCounter += 2;

        activeRuleNames.clear();

        // Priority order evaluation
        evalNoTotemRule();
        evalCrystalRule();
        evalBowRule(target);
        evalBedRule(target);
        evalLowHealthRule();
        evalWaterRule(target);
        evalHoleRule();
    }

    public void onStateChange(CombatBrainModule.BrainState oldState, CombatBrainModule.BrainState newState) {
        if (newState == CombatBrainModule.BrainState.IDLE
            || newState == CombatBrainModule.BrainState.SCANNING
            || newState == CombatBrainModule.BrainState.ANALYZING) {
            shutdown();
        }
    }

    public void shutdown() {
        for (Class<? extends Module> klass : new HashSet<>(enabledByAutomator)) {
            setModuleState(klass, false);
        }
        enabledByAutomator.clear();
        activeRuleNames.clear();
        waterRuleTimer = 0;
        lastRuleToggleTick.clear();
        automatorTickCounter = 0;
    }

    public List<String> getActiveRules() {
        return Collections.unmodifiableList(activeRuleNames);
    }

    private void setModuleState(Class<? extends Module> klass, boolean enable) {
        Module module = Modules.get().get(klass);
        if (module == null) return;

        if (enable) {
            if (!module.isActive()) {
                module.enable();
            }
            enabledByAutomator.add(klass);
        } else {
            if (enabledByAutomator.contains(klass)) {
                if (module.isActive()) {
                    module.disable();
                }
                enabledByAutomator.remove(klass);
            }
        }
    }

    private void evalCrystalRule() {
        boolean active = false;
        double range = brain.crystalDetectRange.get();
        if (range > 0 && isCrystalNearby(range)) {
            active = true;
        }
        setModuleState(CrystalAura.class, active);
        if (active) activeRuleNames.add("CrystalDetect");
    }

    private boolean isCrystalNearby(double range) {
        if (mc.level == null || mc.player == null) return false;
        double rangeSq = range * range;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity.getType() == EntityType.END_CRYSTAL && entity.distanceToSqr(mc.player) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private void evalBowRule(LivingEntity target) {
        boolean active = false;
        if (brain.bowDetect.get() && target != null) {
            active = isHoldingRanged(target);
        }
        setModuleState(ArrowDodge.class, active);
        if (active) activeRuleNames.add("BowDetect");
    }

    private boolean isHoldingRanged(LivingEntity target) {
        return isRangedItem(target.getMainHandItem().getItem()) || isRangedItem(target.getOffhandItem().getItem());
    }

    private boolean isRangedItem(Item item) {
        return item == Items.BOW || item == Items.CROSSBOW || item == Items.TRIDENT;
    }

    private void evalLowHealthRule() {
        boolean active = false;
        boolean hasGap = false;
        if (brain.autoHeal.get() && mc.player != null) {
            float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (health < brain.autoHealThreshold.get()) {
                active = true;
                hasGap = hasGoldenApples();
            }
        }
        setModuleState(AutoEat.class, active);
        setModuleState(AutoGap.class, active && hasGap);
        if (active) activeRuleNames.add("AutoHeal");
    }

    private boolean hasGoldenApples() {
        if (mc.player == null) return false;
        return InvUtils.find(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE).found();
    }

    private void evalNoTotemRule() {
        boolean active = false;
        if (brain.autoTotemSetting.get() && mc.player != null) {
            float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            int totems = countTotems();
            if (totems == 0 && health < 10.0f) {
                active = true;
            }
        }
        setModuleState(AutoTotem.class, active);
        if (active) activeRuleNames.add("NoTotem");
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) count++;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.TOTEM_OF_UNDYING) count++;
        }
        return count;
    }

    /** Maximum dwell passes keeping Jesus active after leaving water (20 passes × 2 ticks = 40 ticks ≈ 2 s). */
    private static final int WATER_DWELL_PASSES = 20;

    private void evalWaterRule(LivingEntity target) {
        boolean active = false;
        if (brain.waterDetect.get() && mc.player != null) {
            if (isNearWater(target)) {
                // Near water: reset dwell timer and stay active
                waterRuleTimer = WATER_DWELL_PASSES;
                active = true;
            } else {
                // Not near water: drain timer; keep active until timer hits 0
                if (waterRuleTimer > 0) {
                    waterRuleTimer = Math.max(0, waterRuleTimer - 1);
                    active = waterRuleTimer > 0;
                }
            }
        } else {
            waterRuleTimer = 0;
        }
        setModuleState(Jesus.class, active);
        if (active) activeRuleNames.add("WaterDetect");
    }

    /**
     * Returns true if the player is in/touching/under water, or if any block within
     * 3 blocks below the player's feet is a water or lava fluid — OR the target is in water.
     * Uses isInWater() and isUnderWater() (verified in AntiHunger/Sprint in this codebase).
     */
    private boolean isNearWater(LivingEntity target) {
        if (mc.player == null) return false;
        // Player water checks
        if (mc.player.isInWater() || mc.player.isUnderWater()) return true;
        // Scan up to 3 blocks below feet for water/lava fluid
        if (mc.level != null) {
            BlockPos feet = mc.player.blockPosition();
            for (int i = 0; i <= 3; i++) {
                BlockState state = mc.level.getBlockState(feet.below(i));
                net.minecraft.world.level.material.FluidState fs = state.getFluidState();
                if (fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER)
                    || fs.is(Fluids.LAVA) || fs.is(Fluids.FLOWING_LAVA)) {
                    return true;
                }
            }
        }
        // Target water check
        if (target != null && target.isInWater()) return true;
        return false;
    }

    private void evalBedRule(LivingEntity target) {
        boolean active = false;
        if (brain.bedDetect.get() && mc.level != null && target != null) {
            if (mc.level.dimension() == Level.NETHER && isTargetNearBed(target)) {
                active = true;
            }
        }
        setModuleState(BedAura.class, active);
        if (active) activeRuleNames.add("BedDetect");
    }

    private boolean isTargetNearBed(LivingEntity target) {
        BlockPos pos = target.blockPosition();
        int radius = 4;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos p = pos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(p);
                    if (state.getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void evalHoleRule() {
        boolean active = false;
        if (brain.holeDetect.get() && mc.player != null) {
            active = isIn1x1Hole();
        }
        setModuleState(Surround.class, active);
        if (active) activeRuleNames.add("HoleDetect");
    }

    private boolean isIn1x1Hole() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos[] cardinal = new BlockPos[] {
            playerPos.north(), playerPos.south(), playerPos.east(), playerPos.west()
        };
        for (BlockPos cp : cardinal) {
            if (isAirOrLiquid(cp) || isAirOrLiquid(cp.below())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAirOrLiquid(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA) || state.canBeReplaced();
    }
}
