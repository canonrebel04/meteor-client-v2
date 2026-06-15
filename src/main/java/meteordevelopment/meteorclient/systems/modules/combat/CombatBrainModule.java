/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.Set;

public class CombatBrainModule extends Module {
    public enum BrainState {
        IDLE,
        SCANNING,
        ANALYZING,
        ENGAGING,
        RETREATING,
        HEALING,
        FLEEING,
        EMERGENCY_LOG
    }

    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgEngagement = settings.createGroup("Engagement");
    private final SettingGroup sgAnalysis = settings.createGroup("Analysis");

    // --- Targeting ---

    private final Setting<Set<EntityType<?>>> targetEntities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("target-entities")
        .description("Entity types to target.")
        .defaultValue(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.ENDERMAN, EntityType.PIGLIN)
        .build()
    );

    private final Setting<Boolean> targetPlayers = sgTargeting.add(new BoolSetting.Builder()
        .name("target-players")
        .description("Target other players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> targetFriendly = sgTargeting.add(new BoolSetting.Builder()
        .name("target-friendly")
        .description("Only attack if they hit you first.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> targetRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Maximum range to search for targets.")
        .defaultValue(8.0)
        .min(1.0)
        .max(16.0)
        .sliderMax(16.0)
        .build()
    );

    // --- Engagement ---

    private final Setting<Boolean> autoModules = sgEngagement.add(new BoolSetting.Builder()
        .name("auto-modules")
        .description("Auto enable/disable KillAura, ArrowDodge, AutoArmor, AutoWeapon, AutoTotem.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> engageThreshold = sgEngagement.add(new DoubleSetting.Builder()
        .name("engage-threshold")
        .description("Threat level (0-1) below which to engage.")
        .defaultValue(0.4)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Double> fleeThreshold = sgEngagement.add(new DoubleSetting.Builder()
        .name("flee-threshold")
        .description("Threat level (0-1) above which to retreat/heal.")
        .defaultValue(0.7)
        .min(0.0)
        .max(1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    private final Setting<Double> fleeDistance = sgEngagement.add(new DoubleSetting.Builder()
        .name("flee-distance")
        .description("Distance to flee when retreating.")
        .defaultValue(8.0)
        .min(2.0)
        .max(20.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Boolean> criticals = sgEngagement.add(new BoolSetting.Builder()
        .name("criticals")
        .description("Also enable Criticals module.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> crystal = sgEngagement.add(new BoolSetting.Builder()
        .name("crystal")
        .description("Also enable CrystalAura (PvP crystal mode).")
        .defaultValue(false)
        .build()
    );

    // --- Analysis ---

    private final Setting<Boolean> analyzeGear = sgAnalysis.add(new BoolSetting.Builder()
        .name("analyze-gear")
        .description("Analyze target armor/weapon.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> viabilityCheck = sgAnalysis.add(new BoolSetting.Builder()
        .name("viability-check")
        .description("Skip fights you would lose.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> followDistance = sgAnalysis.add(new DoubleSetting.Builder()
        .name("follow-distance")
        .description("Stay this far from target.")
        .defaultValue(3.5)
        .min(1.0)
        .max(10.0)
        .sliderMax(10.0)
        .build()
    );

    // State

    private BrainState state = BrainState.IDLE;
    private LivingEntity currentTarget;
    private int tickCounter;
    private int stateTimer;
    private CombatFollowController followController;

    public CombatBrainModule() {
        super(Categories.Combat, "combat-brain", "Advanced combat AI brain. Auto-manages all combat modules, target selection, pathing, and threat analysis.");
    }

    @Override
    public void onActivate() {
        state = BrainState.SCANNING;
        currentTarget = null;
        tickCounter = 0;
        stateTimer = 0;
        followController = new CombatFollowController();
    }

    @Override
    public void onDeactivate() {
        if (followController != null) followController.stop();
        disableAllManagedModules();
        state = BrainState.IDLE;
        currentTarget = null;
        followController = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;

        // Run state machine every 2 ticks to reduce CPU
        if (tickCounter % 2 != 0) return;

        // Check health / threat
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int totems = countTotems();
        double threat = computeThreatLevel();

        // Emergency check
        if (health <= 4.0f && totems == 0 && threat > 0.8f) {
            transitionTo(BrainState.EMERGENCY_LOG);
            return;
        }

        // State machine
        switch (state) {
            case IDLE:
                transitionTo(BrainState.SCANNING);
                break;

            case SCANNING:
                currentTarget = findBestTarget();
                if (currentTarget != null) {
                    transitionTo(BrainState.ANALYZING);
                }
                break;

            case ANALYZING:
                if (currentTarget == null || !isTargetValid(currentTarget)) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                boolean canWin = !viabilityCheck.get() || assessViability(currentTarget);
                if (canWin) {
                    transitionTo(BrainState.ENGAGING);
                } else {
                    transitionTo(BrainState.RETREATING);
                }
                break;

            case ENGAGING:
                if (currentTarget == null || !isTargetValid(currentTarget)) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                // Check if threat got too high
                if (threat >= fleeThreshold.get()) {
                    transitionTo(BrainState.RETREATING);
                    break;
                }

                // Maintain modules
                if (autoModules.get()) enableCombatModules();
                doEngageTick();
                break;

            case RETREATING:
                if (threat < engageThreshold.get()) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                // Try to heal first
                if (health < 10.0f) {
                    transitionTo(BrainState.HEALING);
                    break;
                }

                if (autoModules.get()) disableCombatModules();
                doRetreatTick();
                break;

            case HEALING:
                if (health >= 14.0f || threat < engageThreshold.get()) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                doHealTick();
                break;

            case FLEEING:
                if (threat < fleeThreshold.get() && health >= 10.0f) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                doFleeTick();
                break;

            case EMERGENCY_LOG:
                doEmergencyLog();
                transitionTo(BrainState.IDLE);
                toggle(); // Disable the module after emergency log
                break;
        }

        stateTimer++;
    }

    // --- State transitions ---

    private void transitionTo(BrainState newState) {
        if (state == newState) return;

        onExitState(state);
        state = newState;
        stateTimer = 0;
        onEnterState(state);
    }

    private void onEnterState(BrainState s) {
        switch (s) {
            case ENGAGING:
                if (autoModules.get()) enableCombatModules();
                info("Engaging target");
                break;
            case RETREATING:
                info("Retreating - threat too high");
                break;
            case HEALING:
                info("Healing");
                break;
            case FLEEING:
                info("Fleeing");
                break;
            case EMERGENCY_LOG:
                info("Emergency log triggered");
                break;
            default:
                break;
        }
    }

    private void onExitState(BrainState s) {
        switch (s) {
            case ENGAGING:
                if (autoModules.get()) disableCombatModules();
                break;
            default:
                break;
        }
    }

    // --- Target selection ---

    private LivingEntity findBestTarget() {
        // Use TargetUtils for entity-based targeting
        Entity target = TargetUtils.get(entity -> {
            if (!(entity instanceof LivingEntity le)) return false;
            if (le == mc.player) return false;
            if (!le.isAlive()) return false;
            if (le.distanceTo(mc.player) > targetRange.get()) return false;

            // Check entity type filter
            if (targetEntities.get().contains(le.getType())) return true;

            // Player targeting
            if (le instanceof Player player) {
                if (!targetPlayers.get()) return false;
                if (Friends.get().isFriend(player)) return false;
                if (targetFriendly.get()) {
                    // Only attack if they hit us recently (simplified: check if angry)
                }
                return true;
            }

            return false;
        }, SortPriority.LowestDistance);

        if (target instanceof LivingEntity le) return le;
        return null;
    }

    private boolean isTargetValid(LivingEntity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity.distanceTo(mc.player) > targetRange.get() + 2.0) return false;
        return true;
    }

    private boolean assessViability(LivingEntity target) {
        if (!analyzeGear.get()) return true;

        double myScore = getPlayerCombatScore();
        double targetScore = getEntityCombatScore(target);

        // We engage if our score is at least 60% of target's score
        return myScore >= targetScore * 0.6;
    }

    private double getPlayerCombatScore() {
        if (mc.player == null) return 0;
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double armorScore = mc.player.getArmorValue() / 20.0; // 0-1
        double weaponScore = getHeldWeaponScore();
        return (health / 20.0) * 0.5 + armorScore * 0.3 + weaponScore * 0.2;
    }

    private double getEntityCombatScore(LivingEntity entity) {
        float health = entity.getHealth() + entity.getAbsorptionAmount();
        double armorScore = entity.getArmorValue() / 20.0;
        return (health / 20.0) * 0.5 + armorScore * 0.3 + 0.2; // assume weapon
    }

    private double getHeldWeaponScore() {
        if (mc.player == null) return 0;
        var stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) return 0.2;
        if (stack.getItem() == Items.NETHERITE_SWORD || stack.getItem() == Items.DIAMOND_SWORD) return 1.0;
        if (stack.getItem() == Items.IRON_SWORD) return 0.7;
        if (stack.getItem() == Items.STONE_SWORD) return 0.5;
        if (stack.getItem() == Items.WOODEN_SWORD) return 0.3;
        return 0.4;
    }

    // --- Threat analysis ---

    private double computeThreatLevel() {
        if (mc.player == null) return 0.0;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double healthFactor = 1.0 - (health / 20.0); // 0 = full, 1 = near death

        int totems = countTotems();
        double totemFactor = totems > 0 ? 0.0 : 0.3;

        // Check nearby threats
        double nearbyThreats = 0.0;
        if (mc.level != null) {
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                    double dist = le.distanceTo(mc.player);
                    if (dist < 6.0) {
                        nearbyThreats += (1.0 - dist / 6.0) * 0.3;
                    }
                }
            }
        }

        double threat = healthFactor * 0.4 + totemFactor * 0.2 + Math.min(nearbyThreats, 0.4);
        return Mth.clamp(threat, 0.0, 1.0);
    }

    // --- Module management ---

    private void enableCombatModules() {
        enableModule(KillAura.class);
        enableModule(ArrowDodge.class);
        enableModule(AutoArmor.class);
        enableModule(AutoWeapon.class);
        enableModule(AutoTotem.class);

        if (criticals.get()) enableModule(Criticals.class);
        if (crystal.get()) enableModule(CrystalAura.class);
    }

    private void disableCombatModules() {
        disableModule(KillAura.class);
        disableModule(ArrowDodge.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);
        disableModule(AutoTotem.class);

        disableModule(Criticals.class);
        disableModule(CrystalAura.class);
    }

    private void disableAllManagedModules() {
        disableModule(KillAura.class);
        disableModule(ArrowDodge.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);
        disableModule(AutoTotem.class);
        disableModule(Criticals.class);
        disableModule(CrystalAura.class);
    }

    private void enableModule(Class<? extends Module> klass) {
        Module module = Modules.get().get(klass);
        if (module != null && !module.isActive()) {
            module.enable();
        }
    }

    private void disableModule(Class<? extends Module> klass) {
        Module module = Modules.get().get(klass);
        if (module != null && module.isActive()) {
            module.disable();
        }
    }

    // --- Action ticks ---

    private void doEngageTick() {
        if (currentTarget == null) return;

        // Follow target at follow distance
        double dist = mc.player.distanceTo(currentTarget);
        if (dist > followDistance.get() + 0.5 && followController != null) {
            followController.follow(currentTarget, followDistance.get());
        }
    }

    private void doRetreatTick() {
        // Move away from threat using baritone flee
        if (currentTarget != null && followController != null) {
            followController.flee(currentTarget, fleeDistance.get());
        }
    }

    private void doHealTick() {
        // Sprint away from threats while healing
        if (followController != null && currentTarget != null) {
            followController.flee(currentTarget, fleeDistance.get() * 1.5);
        }
        if (mc.player != null && mc.player.getHealth() < mc.player.getMaxHealth()) {
            mc.player.setSprinting(true);
        }
    }

    private void doFleeTick() {
        // Sprint away from everything
        if (mc.player != null) {
            mc.player.setSprinting(true);
        }
        if (followController != null && currentTarget != null) {
            followController.flee(currentTarget, fleeDistance.get());
        }
    }

    private void doEmergencyLog() {
        if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal("CombatBrain: Emergency Log triggered."));
        }
        disableAllManagedModules();
        state = BrainState.IDLE;
    }

    // --- Utilities ---

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) count++;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.TOTEM_OF_UNDYING) count++;
        }
        return count;
    }

    @Override
    public String getInfoString() {
        if (currentTarget != null) {
            return currentTarget.getName().getString() + " (" + state.name() + ")";
        }
        return state.name();
    }
}
