/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.modules.combat.model.ArmorPolicy;
import meteordevelopment.meteorclient.systems.modules.combat.model.DefenseDirector;
import meteordevelopment.meteorclient.systems.modules.combat.model.PotionPlanner;
import meteordevelopment.meteorclient.systems.modules.combat.model.StatusResponse;
import meteordevelopment.meteorclient.systems.modules.player.AutoPot;
import meteordevelopment.meteorclient.utils.misc.Overrides;

import baritone.api.BaritoneAPI;
import baritone.api.process.IRestockProcess;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.Surround;
import meteordevelopment.meteorclient.systems.modules.movement.NoFall;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Jesus;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoGap;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
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
    private int lastRestockTriggerTick = -200;
    private static final int DEFAULT_RULE_COOLDOWN = 20;
    /** C98: helper-module settings we changed for combat, restored on shutdown. */
    private final Overrides overrides = new Overrides();

    public ModuleAutomator(CombatBrainModule brain) {
        this.brain = brain;
    }

    public void update(LivingEntity target, CombatTerrainGrid grid) {
        if (mc.player == null || mc.level == null) return;
        automatorTickCounter += 2;

        activeRuleNames.clear();

        // Priority order evaluation
        if (brain.defenseDirector.get()) evalDefenseRule(target);
        else evalNoTotemRule();
        evalWitherMilkRule();
        evalFireResistanceRule(target);
        evalBuffPotionRule();
        evalWardenRule(target);
        evalNoFallClutchRule();
        evalCrystalRule();
        evalBowRule(target);
        evalBedRule(target);
        if (!brain.defenseDirector.get()) evalLowHealthRule();
        evalWaterRule(target);
        evalSurroundRule();
        evalShulkerRestockRule(target);
        evalScaffoldFleeRule();
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
        overrides.restoreAll();
        brain.setDefensePlan(null);
        activeRuleNames.clear();
        waterRuleTimer = 0;
        lastRuleToggleTick.clear();
        lastRestockTriggerTick = -200;
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
            // Only enable CrystalAura if the player actually has end crystals
            if (hasEndCrystals()) {
                active = true;
            }
        }
        setModuleState(CrystalAura.class, active);
        if (active) activeRuleNames.add("CrystalDetect");
    }

    private boolean hasEndCrystals() {
        if (mc.player == null) return false;
        return InvUtils.find(Items.END_CRYSTAL).found();
    }

    private boolean isCrystalNearby(double range) {
        if (mc.level == null || mc.player == null) return false;
        double rangeSq = range * range;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity.getType() == EntityTypes.END_CRYSTAL && entity.distanceToSqr(mc.player) <= rangeSq) {
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
        // Mode loadout (spec section 3): KITE/SNIPE keep ArrowDodge up while we hold range.
        CombatMode mode = brain.getCombatMode();
        if (target != null && (mode == CombatMode.KITE || mode == CombatMode.SNIPE)) active = true;
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

        // CRITICAL COMBAT RULE: NEVER eat regular food in mainhand if an active threat is in melee range (< 5.5m)!
        // Eating regular food slows the player down by 80%, prevents swinging weapon, cancels sprinting, and leads to death.
        boolean threatInMeleeRange = false;
        if (mc.level != null && mc.player != null) {
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                    boolean isHostile = le.getType().getCategory() == MobCategory.MONSTER
                        || (le instanceof Player && le != mc.player);
                    // ⚡ Bolt: Use distanceToSqr to avoid Math.sqrt overhead
                    if (isHostile && le.distanceToSqr(mc.player) <= 5.5 * 5.5) {
                        threatInMeleeRange = true;
                        break;
                    }
                }
            }
        }

        // Only allow AutoEat if there is NO threat in melee range (or player is safely out of reach)
        boolean allowAutoEat = active && !threatInMeleeRange;
        // Gapping (Golden Apples) in offhand is safe during combat
        boolean allowAutoGap = active && hasGap;

        // Ensure AutoEat and AutoGap never pause Baritone or Auras during combat AI
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        if (autoEat != null) {
            Setting<Boolean> pb = (Setting<Boolean>) (Setting<?>) autoEat.settings.get("pause-baritone");
            if (pb != null) pb.set(false);
            Setting<Boolean> pa = (Setting<Boolean>) (Setting<?>) autoEat.settings.get("pause-auras");
            if (pa != null) pa.set(false);
        }
        AutoGap autoGap = Modules.get().get(AutoGap.class);
        if (autoGap != null) {
            Setting<Boolean> pb = (Setting<Boolean>) (Setting<?>) autoGap.settings.get("pause-baritone");
            if (pb != null) pb.set(false);
            Setting<Boolean> pa = (Setting<Boolean>) (Setting<?>) autoGap.settings.get("pause-auras");
            if (pa != null) pa.set(false);
        }

        setModuleState(AutoEat.class, allowAutoEat);
        setModuleState(AutoGap.class, allowAutoGap);
        if (allowAutoEat || allowAutoGap) activeRuleNames.add("AutoHeal");
    }

    /**
     * C51-C59: one defense plan from all P1 policies drives the helper modules. They are only enabled/disabled by
     * us when we enabled them (a module the user turned on stays on), and settings we change on them are restored
     * when combat ends.
     */
    private void evalDefenseRule(LivingEntity target) {
        if (mc.player == null || mc.level == null) return;
        DefenseDirector.Plan plan = DefenseDirector.decide(defenseInput(target));
        brain.setDefensePlan(plan);

        boolean heal = brain.autoHeal.get();
        if (heal && (plan.runAutoGap() || plan.runAutoEat())) {
            AutoEat autoEat = Modules.get().get(AutoEat.class);
            if (autoEat != null) {
                overrideBool(autoEat, "pause-baritone", false);
                overrideBool(autoEat, "pause-auras", false);
            }
            AutoGap autoGap = Modules.get().get(AutoGap.class);
            if (autoGap != null) {
                overrideBool(autoGap, "pause-baritone", false);
                overrideBool(autoGap, "pause-auras", false);
            }
        }
        setModuleState(AutoGap.class, heal && plan.runAutoGap());
        setModuleState(AutoEat.class, heal && plan.runAutoEat());
        if (heal && (plan.runAutoGap() || plan.runAutoEat())) activeRuleNames.add("AutoHeal");

        // potions: AutoPot heals at our heal threshold while the plan wants an instant-health potion
        AutoPot autoPot = Modules.get().get(AutoPot.class);
        if (autoPot != null && heal && plan.runAutoPot()) {
            overrideDouble(autoPot, "health", brain.autoHealThreshold.get());
        } else if (autoPot != null) {
            restoreSetting(autoPot, "health");
        }
        setModuleState(AutoPot.class, heal && plan.runAutoPot());
        if (heal && plan.runAutoPot()) activeRuleNames.add("AutoPot");

        // totems: Offhand (if the user runs it) or AutoTotem keeps the totem; strict while the plan says hold
        if (brain.autoTotemSetting.get()) {
            Offhand offhand = Modules.get().get(Offhand.class);
            boolean userOffhand = offhand != null && offhand.isActive() && !enabledByAutomator.contains(Offhand.class);
            AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
            if (userOffhand) {
                // never run two offhand managers at once: Offhand keeps the totem by raising its min-health
                setModuleState(AutoTotem.class, false);
                if (plan.holdTotem()) overrideDouble(offhand, "min-health", 36.0);
                else restoreSetting(offhand, "min-health");
            } else {
                boolean haveTotem = countTotems() > 0;
                setModuleState(AutoTotem.class, haveTotem);
                if (autoTotem != null) {
                    if (plan.holdTotem()) overrideEnum(autoTotem, "mode", AutoTotem.Mode.Strict);
                    else restoreSetting(autoTotem, "mode");
                }
            }
            if (plan.holdTotem()) activeRuleNames.add("HoldTotem");
        }

        // armor: replace near-broken pieces out of melee, chestplate over elytra in a ground fight
        AutoArmor autoArmor = Modules.get().get(AutoArmor.class);
        if (autoArmor != null) {
            switch (plan.chest()) {
                case CHESTPLATE -> overrideBool(autoArmor, "ignore-elytra", false);
                case ELYTRA -> overrideBool(autoArmor, "ignore-elytra", true);
                default -> restoreSetting(autoArmor, "ignore-elytra");
            }
            double dist = target != null ? mc.player.distanceTo(target) : 99;
            // Anti-break is default off; only enable it out of melee so we don't unequip in a trade.
            if (plan.runAutoArmor() && ArmorPolicy.replaceBroken(0, 10, true, dist)) {
                overrideBool(autoArmor, "anti-break", true);
            } else {
                restoreSetting(autoArmor, "anti-break");
            }
        }
        if (plan.runAutoArmor()) {
            setModuleState(AutoArmor.class, true);
            activeRuleNames.add("Armor");
        }
    }

    private DefenseDirector.Input defenseInput(LivingEntity target) {
        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double dist = target != null ? mc.player.distanceTo(target) : 99;
        double dps = brain.incomingDps();
        double burst = dps + (isCrystalNearby(6) ? 8 : 0) + (mc.player.fallDistance > 4 ? mc.player.fallDistance - 3 : 0);
        int totemsInv = countTotems() - (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? 1 : 0);
        boolean wearingElytra = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA);
        double worst = 1;
        for (net.minecraft.world.entity.EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack st = mc.player.getItemBySlot(slot);
            if (!st.isEmpty() && st.isDamageableItem() && st.getMaxDamage() > 0) {
                worst = Math.min(worst, 1.0 - (double) st.getDamageValue() / st.getMaxDamage());
            }
        }
        int netherite = 0;
        for (net.minecraft.world.entity.EquipmentSlot slot : ARMOR_SLOTS) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getItemBySlot(slot).getItem()).getPath();
            if (id.startsWith("netherite_")) netherite++;
        }
        boolean fightOn = target != null && brain.getState() == CombatBrainModule.BrainState.ENGAGING;
        boolean fleeing = brain.getState() == CombatBrainModule.BrainState.FLEEING || brain.getState() == CombatBrainModule.BrainState.RETREATING
            || brain.getState() == CombatBrainModule.BrainState.HEALING;
        boolean enemySprint = target != null && target.isSprinting();
        int enemyKb = target != null ? meteordevelopment.meteorclient.utils.Utils.getEnchantmentLevel(target.getMainHandItem(),
            net.minecraft.world.item.enchantment.Enchantments.KNOCKBACK) : 0;
        return new DefenseDirector.Input(
            hp, mc.player.getMaxHealth() + mc.player.getAbsorptionAmount(), dps, burst, dist, 3.0,
            target != null && !mc.player.hasLineOfSight(target),
            InvUtils.find(Items.GOLDEN_APPLE).found(), InvUtils.find(Items.ENCHANTED_GOLDEN_APPLE).found(),
            findPotion(MobEffects.INSTANT_HEALTH, false).found(), findPotion(MobEffects.INSTANT_HEALTH, true).found(),
            mc.player.hasEffect(MobEffects.REGENERATION),
            mc.player.getFoodData().getFoodLevel(), mc.player.getFoodData().getSaturationLevel(),
            totemsInv, mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING), brain.justPopped(),
            brain.damageByType(), InvUtils.find(Items.SHIELD).found() || mc.player.getOffhandItem().is(Items.SHIELD),
            wearingElytra, false, fightOn, mc.player.onGround(),
            InvUtils.find(st -> st.getItem() != Items.ELYTRA && st.is(net.minecraft.tags.ItemTags.CHEST_ARMOR)).found(),
            InvUtils.find(Items.ELYTRA).found(), worst, false,
            new PotionPlanner.Have(findPotionWithEffect(MobEffects.STRENGTH).found(), findPotionWithEffect(MobEffects.SPEED).found(),
                findPotionWithEffect(MobEffects.RESISTANCE).found(), findPotionWithEffect(MobEffects.FIRE_RESISTANCE).found(),
                findPotionWithEffect(MobEffects.REGENERATION).found(), findPotionWithEffect(MobEffects.INSTANT_HEALTH).found()),
            new PotionPlanner.Active(mc.player.hasEffect(MobEffects.STRENGTH), mc.player.hasEffect(MobEffects.SPEED),
                mc.player.hasEffect(MobEffects.RESISTANCE), mc.player.hasEffect(MobEffects.FIRE_RESISTANCE), mc.player.hasEffect(MobEffects.REGENERATION)),
            mc.player.isOnFire(), fleeing, netherite, enemySprint, enemyKb, fightOn ? edgeDistance() : 99,
            brain.autoHealThreshold.get());
    }

    private static final net.minecraft.world.entity.EquipmentSlot[] ARMOR_SLOTS = {
        net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
        net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};

    /** Distance to the nearest drop of 4+ blocks or lava in 8 directions (up to 5 blocks; 99 if none). */
    private double edgeDistance() {
        BlockPos feet = mc.player.blockPosition();
        double best = 99;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int r = 1; r <= 5; r++) {
                    BlockPos p = feet.offset(dx * r, 0, dz * r);
                    if (!mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) break; // wall: no edge this way
                    boolean lava = mc.level.getFluidState(p.below()).is(net.minecraft.tags.FluidTags.LAVA)
                        || mc.level.getFluidState(p).is(net.minecraft.tags.FluidTags.LAVA);
                    boolean drop = true;
                    for (int d = 1; d <= 4 && drop; d++) {
                        BlockPos q = p.below(d);
                        drop = mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty() && !mc.level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER);
                    }
                    if (lava || drop) {
                        best = Math.min(best, r * (dx != 0 && dz != 0 ? 1.414 : 1.0));
                        break;
                    }
                }
            }
        }
        return best;
    }

    private FindItemResult findPotion(Holder<MobEffect> effect, boolean splash) {
        return InvUtils.find(stack -> {
            if (!(splash ? stack.is(Items.SPLASH_POTION) : stack.is(Items.POTION))) return false;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return false;
            for (MobEffectInstance inst : contents.getAllEffects()) if (inst.getEffect().is(effect)) return true;
            return false;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void overrideSetting(Module m, String name, T value) {
        Setting<T> s = (Setting<T>) m.settings.get(name);
        if (s == null) return;
        overrides.apply(m.name + "." + name, s::get, s::set, value);
    }

    private void overrideBool(Module m, String name, boolean v) {
        overrideSetting(m, name, v);
    }

    private void overrideDouble(Module m, String name, double v) {
        overrideSetting(m, name, v);
    }

    private <E extends Enum<E>> void overrideEnum(Module m, String name, E v) {
        overrideSetting(m, name, v);
    }

    private void restoreSetting(Module m, String name) {
        overrides.restore(m.name + "." + name);
    }

    private boolean hasGoldenApples() {
        if (mc.player == null) return false;
        return InvUtils.find(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE).found();
    }

    private void evalNoTotemRule() {
        boolean active = false;
        if (brain.autoTotemSetting.get() && mc.player != null) {
            boolean offhandHasTotem = mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
            boolean hasTotemInInv = false;
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                if (mc.player.getInventory().getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
                    hasTotemInInv = true;
                    break;
                }
            }
            // AutoTotem should activate if the offhand lacks a totem but one exists in inventory
            if (!offhandHasTotem && hasTotemInInv) {
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
        // Allocation free (plan S88): one mutable position instead of 405 BlockPos per call
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    p.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState state = mc.level.getBlockState(p);
                    if (state.getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Unified surround rule evaluating both 1x1 hole containment and open-field mob swarm defense.
     */
    private void evalSurroundRule() {
        boolean holeActive = false;
        if (brain.holeDetect.get() && mc.player != null) {
            holeActive = isIn1x1Hole();
            if (holeActive) activeRuleNames.add("HoleDetect");
        }

        boolean swarmActive = false;
        if (brain.swarmSurround.get() && mc.player != null && mc.level != null) {
            int hostiles = 0;
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                if (!(entity instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
                boolean hostile = le.getType().getCategory() == MobCategory.MONSTER
                    || (le instanceof Player && le != mc.player);
                if (!hostile) continue;
                if (le.distanceToSqr(mc.player) <= 4.0 * 4.0) hostiles++;
            }
            swarmActive = hostiles >= 3;
            if (swarmActive) activeRuleNames.add("SwarmSurround");
        }

        // Only enable Surround if player actually has blocks to place (need at
        // least 4 for feet placement). Enabling with no blocks is a no-op that
        // wastes module state and confuses the user.
        // Mode loadout (spec section 3): BURROW digs in with Surround.
        boolean burrowMode = brain.getCombatMode() == CombatMode.BURROW;
        if (burrowMode) activeRuleNames.add("BurrowMode");
        boolean shouldSurround = (holeActive || swarmActive || burrowMode) && hasPlaceableBlocks(4);
        setModuleState(Surround.class, shouldSurround);
    }

    /**
     * Scaffold-bridge while fleeing: when the FSM is in RETREATING/HEALING/
     * FLEEING and the player is over a void/liquid gap, Scaffold lays blocks
     * underfoot so the escape path doesn't drop us into a ravine or water.
     * Only active during defensive states to avoid interfering with offense.
     */
    private void evalScaffoldFleeRule() {
        boolean active = false;
        if (brain.scaffoldFlee.get() && mc.player != null && mc.level != null) {
            CombatBrainModule.BrainState st = brain.getState();
            boolean fleeing = st == CombatBrainModule.BrainState.RETREATING
                || st == CombatBrainModule.BrainState.HEALING
                || st == CombatBrainModule.BrainState.FLEEING;
            if (fleeing && !mc.player.onGround()) {
                // Over a gap (nothing solid within 3 below) → bridge
                BlockPos feet = mc.player.blockPosition();
                boolean overGap = true;
                for (int i = 1; i <= 3; i++) {
                    if (mc.level.getBlockState(feet.below(i)).isSolidRender()) {
                        overGap = false;
                        break;
                    }
                }
                // Scaffold needs at least 3 blocks in hotbar to be useful
                active = overGap && hasPlaceableBlocks(3);
            }
        }
        setModuleState(Scaffold.class, active);
        if (active) activeRuleNames.add("ScaffoldFlee");
    }

    /**
     * Checks if the player has at least {@code minCount} total placeable blocks
     * in the hotbar slots (0-8). Only hotbar matters since that's what block
     * placement modules use.
     */
    private boolean hasPlaceableBlocks(int minCount) {
        if (mc.player == null) return false;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && Block.byItem(stack.getItem()) != Blocks.AIR) {
                count += stack.getCount();
                if (count >= minCount) return true;
            }
        }
        return false;
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

    private void evalWitherMilkRule() {
        if (!brain.witherMilk.get() || mc.player == null) return;
        // C57: milk for wither, high levitation or poison at low HP; keep our buffs otherwise
        java.util.EnumSet<StatusResponse.Effect> effects = java.util.EnumSet.noneOf(StatusResponse.Effect.class);
        if (mc.player.hasEffect(MobEffects.WITHER)) effects.add(StatusResponse.Effect.WITHER);
        if (mc.player.hasEffect(MobEffects.POISON)) effects.add(StatusResponse.Effect.POISON);
        if (mc.player.hasEffect(MobEffects.LEVITATION)) effects.add(StatusResponse.Effect.LEVITATION);
        boolean goodBuffs = mc.player.hasEffect(MobEffects.STRENGTH) || mc.player.hasEffect(MobEffects.RESISTANCE) || mc.player.hasEffect(MobEffects.SPEED);
        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double height = mc.player.onGround() ? 0 : Math.min(64, mc.player.fallDistance + 4);
        boolean drink = StatusResponse.respond(effects, hp, true, goodBuffs, height).contains(StatusResponse.Response.DRINK_MILK);
        if (drink) {
            FindItemResult milk = InvUtils.find(Items.MILK_BUCKET);
            if (milk.found()) {
                activeRuleNames.add("WitherMilk");
                if (mc.player.getOffhandItem().is(Items.MILK_BUCKET)) {
                    mc.options.keyUse.setDown(true);
                } else if (!brain.offhandLocked()) {
                    InvUtils.move().from(milk.slot()).toOffhand();
                }
                return;
            }
        }
        if (mc.player.getOffhandItem().is(Items.MILK_BUCKET) && !drink) {
            mc.options.keyUse.setDown(false);
        }
    }

    private void evalFireResistanceRule(LivingEntity target) {
        if (!brain.fireResPotion.get() || mc.player == null || mc.level == null) return;
        if (mc.player.hasEffect(MobEffects.FIRE_RESISTANCE)) return;

        DefenseDirector.Plan plan = brain.defensePlan();
        boolean isFireThreat = (plan != null && plan.potions().contains(PotionPlanner.Buff.FIRE_RESISTANCE))
            || (mc.level.dimension() == Level.NETHER)
            || (target != null && (target.getType() == EntityTypes.BLAZE
                || target.getType() == EntityTypes.GHAST
                || target.getType() == EntityTypes.MAGMA_CUBE
                || target.getType() == EntityTypes.WITHER_SKELETON));

        if (isFireThreat) {
            FindItemResult potionSlot = findPotionWithEffect(MobEffects.FIRE_RESISTANCE);
            if (potionSlot.found()) {
                activeRuleNames.add("FireResPotion");
                if (mc.player.getOffhandItem().is(Items.POTION)) {
                    mc.options.keyUse.setDown(true);
                } else if (!brain.offhandLocked()) {
                    InvUtils.move().from(potionSlot.slot()).toOffhand();
                }
            }
        }
    }

    /** C54 leftover: drink planned strength/speed when the user has opted in. Drinkable potions only (no camera). */
    private void evalBuffPotionRule() {
        if (!brain.useBuffPotions.get() || mc.player == null || brain.offhandLocked()) return;
        DefenseDirector.Plan plan = brain.defensePlan();
        if (plan == null || plan.potions() == null || plan.potions().isEmpty()) return;
        // Don't fight fire-res / milk for the offhand.
        if (activeRuleNames.contains("FireResPotion") || activeRuleNames.contains("WitherMilk")
            || activeRuleNames.contains("WardenResistance")) return;
        Holder<MobEffect> want = null;
        String rule = null;
        if (plan.potions().contains(PotionPlanner.Buff.STRENGTH) && !mc.player.hasEffect(MobEffects.STRENGTH)) {
            want = MobEffects.STRENGTH;
            rule = "StrengthPotion";
        } else if (plan.potions().contains(PotionPlanner.Buff.SPEED) && !mc.player.hasEffect(MobEffects.SPEED)) {
            want = MobEffects.SPEED;
            rule = "SpeedPotion";
        }
        if (want == null) return;
        FindItemResult potion = findDrinkablePotion(want);
        if (!potion.found()) return;
        activeRuleNames.add(rule);
        drinkPotion(potion);
    }

    private FindItemResult findDrinkablePotion(Holder<MobEffect> effect) {
        return InvUtils.find(stack -> {
            if (!stack.is(Items.POTION)) return false;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return false;
            for (MobEffectInstance inst : contents.getAllEffects()) {
                if (inst.getEffect().is(effect)) return true;
            }
            return false;
        });
    }

    private void drinkPotion(FindItemResult potion) {
        if (mc.player.getOffhandItem().is(Items.POTION)) {
            mc.options.keyUse.setDown(true);
        } else {
            InvUtils.move().from(potion.slot()).toOffhand();
        }
    }

    private void evalWardenRule(LivingEntity target) {
        if (!brain.wardenCounter.get() || mc.player == null || mc.level == null) return;

        boolean wardenNear = isWardenNearby(20.0);
        if (wardenNear && !mc.player.hasEffect(MobEffects.RESISTANCE)) {
            FindItemResult potionSlot = findPotionWithEffect(MobEffects.RESISTANCE);
            if (potionSlot.found()) {
                activeRuleNames.add("WardenResistance");
                if (mc.player.getOffhandItem().is(Items.POTION)) {
                    mc.options.keyUse.setDown(true);
                } else if (!brain.offhandLocked()) {
                    InvUtils.move().from(potionSlot.slot()).toOffhand();
                }
            }
        }
    }

    private void evalNoFallClutchRule() {
        boolean active = false;
        if (brain.noFallClutch.get() && mc.player != null && mc.level != null) {
            if (mc.player.getDeltaMovement().y < -0.6 && !mc.player.onGround() && !mc.player.isInWater()) {
                active = true;
            }
        }
        setModuleState(NoFall.class, active);
        if (active) activeRuleNames.add("NoFallClutch");
    }

    private FindItemResult findPotionWithEffect(Holder<MobEffect> effect) {
        return InvUtils.find(stack -> {
            if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) return false;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return false;
            for (MobEffectInstance inst : contents.getAllEffects()) {
                if (inst.getEffect().is(effect)) return true;
            }
            return false;
        });
    }

    private boolean isWardenNearby(double range) {
        if (mc.level == null || mc.player == null) return false;
        double rangeSq = range * range;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity.getType() == EntityTypes.WARDEN && entity.distanceToSqr(mc.player) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private void evalShulkerRestockRule(LivingEntity target) {
        if (mc.player == null || mc.level == null) return;

        IRestockProcess restockProcess;
        try {
            restockProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getRestockProcess();
        } catch (Exception ignored) {
            return;
        }
        if (restockProcess == null) return;
        if (restockProcess.isActive()) {
            activeRuleNames.add("ShulkerRestock");
            return;
        }

        if (!mc.player.onGround()) return;
        if (automatorTickCounter - lastRestockTriggerTick < 60) return;

        boolean safe = isIn1x1Hole(target) || isFarFromEnemies(target, 14.0);
        if (!safe) return;

        Item itemToRestock = null;
        if (countTotems() <= 1 && hasShulkerContaining(Items.TOTEM_OF_UNDYING)) {
            itemToRestock = Items.TOTEM_OF_UNDYING;
        } else {
            CrystalAura crystalAura = Modules.get().get(CrystalAura.class);
            if (crystalAura != null && crystalAura.isActive()
                && countInventoryItem(Items.END_CRYSTAL) < 8
                && hasShulkerContaining(Items.END_CRYSTAL)) {
                itemToRestock = Items.END_CRYSTAL;
            }
        }

        if (itemToRestock != null && restockProcess.restock(itemToRestock)) {
            lastRestockTriggerTick = automatorTickCounter;
            activeRuleNames.add("ShulkerRestock");
        }
    }

    private boolean isIn1x1Hole(LivingEntity target) {
        if (mc.player == null || mc.level == null) return false;
        if (target != null && target != mc.player && target.distanceToSqr(mc.player) <= 2.25) {
            return false;
        }
        BlockPos feet = mc.player.blockPosition();
        if (mc.level.getBlockState(feet.below()).getCollisionShape(mc.level, feet.below()).isEmpty()) {
            return false;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (mc.level.getBlockState(side).getCollisionShape(mc.level, side).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isFarFromEnemies(LivingEntity target, double minDistance) {
        if (mc.player == null || mc.level == null) return false;
        double minSq = minDistance * minDistance;
        if (target != null && target != mc.player && !target.isDeadOrDying() && target.distanceToSqr(mc.player) <= minSq) {
            return false;
        }
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity instanceof Player player && player != mc.player && !player.isDeadOrDying() && Friends.get().shouldAttack(player)) {
                if (player.distanceToSqr(mc.player) <= minSq) {
                    return false;
                }
            }
        }
        return true;
    }

    private int countInventoryItem(Item item) {
        if (mc.player == null) return 0;
        int total = 0;
        if (mc.player.getOffhandItem().is(item)) total += mc.player.getOffhandItem().getCount();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private boolean hasShulkerContaining(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)) continue;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null && contents.nonEmptyItemCopyStream().anyMatch(inner -> inner.is(targetItem))) {
                return true;
            }
        }
        return false;
    }
}
