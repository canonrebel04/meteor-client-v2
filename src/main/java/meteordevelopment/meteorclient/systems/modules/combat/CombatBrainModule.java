/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import net.minecraft.world.entity.MobCategory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    public enum EmergencyMode {
        DISCONNECT,
        BURROW,
        FLEE
    }

    public enum StrikePhase {
        BUBBLE,
        STRIKE
    }

    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgEngagement = settings.createGroup("Engagement");
    private final SettingGroup sgAnalysis = settings.createGroup("Analysis");

    // --- Targeting ---

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(
            EntityType.PLAYER,
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.ENDERMAN,
            EntityType.PIGLIN, EntityType.ZOMBIFIED_PIGLIN, EntityType.DROWNED, EntityType.HUSK, EntityType.STRAY,
            EntityType.WITHER_SKELETON, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.BLAZE, EntityType.GHAST,
            EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.RAVAGER, EntityType.EVOKER,
            EntityType.WARDEN, EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.BREEZE, EntityType.BOGGED
        )
        .build()
    );

    private final Setting<Boolean> targetPlayers = sgTargeting.add(new BoolSetting.Builder()
        .name("target-players")
        .description("Target other players.")
        .defaultValue(true)
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
        .description("Maximum range to search for targets (retained for backwards compatibility).")
        .defaultValue(8.0)
        .min(1.0)
        .max(16.0)
        .sliderMax(16.0)
        .build()
    );

    private final Setting<Double> acquireRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("acquire-range")
        .description("Maximum range to acquire (lock onto) a target. The brain paths to it from any distance within this range — baritone handles the travel.")
        .defaultValue(64.0)
        .min(8.0)
        .max(256.0)
        .sliderMax(256.0)
        .build()
    );

    private final Setting<Double> maxChaseRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("max-chase-range")
        .description("Maximum range to keep chasing an acquired target before abandoning it. Lets the brain chase far beyond the acquisition range — baritone paths the distance.")
        .defaultValue(64.0)
        .min(8.0)
        .max(256.0)
        .sliderMax(256.0)
        .build()
    );

    // --- Scoring ---

    private final SettingGroup sgScoring = settings.createGroup("Scoring");

    private final Setting<Double> targetDistanceWeight = sgScoring.add(new DoubleSetting.Builder()
        .name("target-distance-weight")
        .description("Weight for target distance in multi-target scoring (closer is better).")
        .defaultValue(0.4)
        .min(0.0)
        .max(2.0)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Double> targetHealthWeight = sgScoring.add(new DoubleSetting.Builder()
        .name("target-health-weight")
        .description("Weight for target health in multi-target scoring (lower health is better).")
        .defaultValue(0.2)
        .min(0.0)
        .max(2.0)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Double> targetDefenseWeight = sgScoring.add(new DoubleSetting.Builder()
        .name("target-defense-weight")
        .description("Weight for target-armor defense in multi-target scoring (less armor is better).")
        .defaultValue(0.2)
        .min(0.0)
        .max(2.0)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Double> targetWeaponWeight = sgScoring.add(new DoubleSetting.Builder()
        .name("target-weapon-weight")
        .description("Weight for target-weapons threat in multi-target scoring (weaker weapon is better).")
        .defaultValue(0.2)
        .min(0.0)
        .max(2.0)
        .sliderMax(2.0)
        .build()
    );

    private final Setting<Boolean> multiTarget = sgScoring.add(new BoolSetting.Builder()
        .name("multi-target")
        .description("Enable smart multi-target scoring and switching.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchDelay = sgScoring.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Minimum ticks before switching targets to prevent anti-flicker.")
        .defaultValue(20)
        .min(0)
        .max(200)
        .sliderMax(200)
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

    private final Setting<Boolean> modeSwitch = sgEngagement.add(new BoolSetting.Builder()
        .name("mode-switch")
        .description("Enable tactical combat mode switching loop.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hitAndRun = sgEngagement.add(new BoolSetting.Builder()
        .name("hit-and-run")
        .description("Enable hit-and-run strike cycle between safety bubble and strike range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> strikeDistance = sgEngagement.add(new DoubleSetting.Builder()
        .name("strike-distance")
        .description("Distance to get to target when darting in to attack.")
        .defaultValue(2.5)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Integer> strikeDurationTicks = sgEngagement.add(new IntSetting.Builder()
        .name("strike-duration-ticks")
        .description("Duration (in ticks) to stay in the strike phase before retreating.")
        .defaultValue(10)
        .min(1)
        .max(100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> retreatCooldownTicks = sgEngagement.add(new IntSetting.Builder()
        .name("retreat-cooldown-ticks")
        .description("Minimum ticks to stay in the safety bubble before next strike.")
        .defaultValue(10)
        .min(0)
        .max(200)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> threatHighDwell = sgEngagement.add(new IntSetting.Builder()
        .name("threat-high-dwell")
        .description("Consecutive FSM passes (threatDwell / stateDwell ticks) threat must stay above flee-threshold before retreating (threatHysteresis).")
        .defaultValue(5)
        .min(1)
        .max(100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> threatLowDwell = sgEngagement.add(new IntSetting.Builder()
        .name("threat-low-dwell")
        .description("Consecutive FSM passes (threatDwell / retreatTicks) threat must stay below engage-threshold before re-engaging.")
        .defaultValue(5)
        .min(1)
        .max(100)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> modeHysteresisTicks = sgEngagement.add(new IntSetting.Builder()
        .name("mode-hysteresis-ticks")
        .description("Minimum ticks to hold a combat mode before switching (prevents flapping).")
        .defaultValue(20)
        .min(0)
        .max(200)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> stateReentryDelay = sgEngagement.add(new IntSetting.Builder()
        .name("state-reentry-delay")
        .description("Minimum FSM passes before RETREATING/HEALING/FLEEING can be re-entered after leaving them. Prevents state-loop spam under sustained threat.")
        .defaultValue(20)
        .min(1)
        .max(200)
        .sliderMax(200)
        .build()
    );

    private final Setting<EmergencyMode> emergencyMode = sgEngagement.add(new EnumSetting.Builder<EmergencyMode>()
        .name("emergency-mode")
        .description("Action to take when emergency protocol triggers.")
        .defaultValue(EmergencyMode.DISCONNECT)
        .build()
    );

    private final Setting<Double> losWeight = sgEngagement.add(new DoubleSetting.Builder()
        .name("los-weight")
        .description("Threat multiplier for visible enemies with line of sight.")
        .defaultValue(2.0)
        .min(1.0)
        .max(5.0)
        .sliderRange(1.0, 5.0)
        .build()
    );

    private final Setting<Boolean> coverModifier = sgEngagement.add(new BoolSetting.Builder()
        .name("cover-modifier")
        .description("Modify threat level based on nearby cover/walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> crystalThreat = sgEngagement.add(new DoubleSetting.Builder()
        .name("crystal-threat")
        .description("Threat added per nearby end crystal.")
        .defaultValue(0.15)
        .min(0.0)
        .max(0.5)
        .sliderRange(0.0, 0.5)
        .build()
    );

    // --- Automator ---

    private final SettingGroup sgAutomator = settings.createGroup("Automator");

    final Setting<Double> crystalDetectRange = sgAutomator.add(new DoubleSetting.Builder()
        .name("crystal-detect-range")
        .description("End crystal detection range for CrystalAura.")
        .defaultValue(6.0)
        .min(1.0)
        .max(16.0)
        .sliderMax(16.0)
        .build()
    );

    final Setting<Boolean> bowDetect = sgAutomator.add(new BoolSetting.Builder()
        .name("bow-detect")
        .description("Enable ArrowDodge when target holds a bow, crossbow, or trident.")
        .defaultValue(true)
        .build()
    );

    final Setting<Double> autoHealThreshold = sgAutomator.add(new DoubleSetting.Builder()
        .name("auto-heal-threshold")
        .description("Health threshold (health + absorption) below which auto-heal triggers.")
        .defaultValue(14.0)
        .min(1.0)
        .max(20.0)
        .sliderMax(20.0)
        .build()
    );

    final Setting<Boolean> autoHeal = sgAutomator.add(new BoolSetting.Builder()
        .name("auto-heal")
        .description("Auto enable AutoEat and AutoGap when health is low.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> autoTotemSetting = sgAutomator.add(new BoolSetting.Builder()
        .name("auto-totem")
        .description("Auto enable AutoTotem when health is low and no totems.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> waterDetect = sgAutomator.add(new BoolSetting.Builder()
        .name("water-detect")
        .description("Auto enable Jesus when player or target is in water.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> bedDetect = sgAutomator.add(new BoolSetting.Builder()
        .name("bed-detect")
        .description("Auto enable BedAura when target is near a bed block in the nether.")
        .defaultValue(false)
        .build()
    );

    final Setting<Boolean> holeDetect = sgAutomator.add(new BoolSetting.Builder()
        .name("hole-detect")
        .description("Auto enable Surround when player is in a 1x1 hole.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> swarmSurround = sgAutomator.add(new BoolSetting.Builder()
        .name("swarm-surround")
        .description("Auto enable Surround when 3+ hostiles are within 4 blocks (swarm protection).")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> scaffoldFlee = sgAutomator.add(new BoolSetting.Builder()
        .name("scaffold-flee")
        .description("Auto enable Scaffold to bridge over gaps while retreating/healing/fleeing.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> autoWeapon = sgAutomator.add(new BoolSetting.Builder()
        .name("auto-weapon")
        .description("Auto enable AutoWeapon when engaging targets.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> autoArmor = sgAutomator.add(new BoolSetting.Builder()
        .name("auto-armor")
        .description("Auto enable AutoArmor when engaging targets.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> shieldSwap = sgAutomator.add(new BoolSetting.Builder()
        .name("shield-swap")
        .description("Auto enable ShieldAutoSwap to block incoming attacks.")
        .defaultValue(true)
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

    private final Setting<Double> undergearRatio = sgAnalysis.add(new DoubleSetting.Builder()
        .name("undergear-ratio")
        .description("Ratio threshold below which player is considered undergeared compared to target.")
        .defaultValue(0.6)
        .min(0.1)
        .max(1.0)
        .sliderRange(0.1, 1.0)
        .build()
    );

    private final Setting<Double> followDistance = sgAnalysis.add(new DoubleSetting.Builder()
        .name("follow-distance")
        .description("Stay this far from target. Used as manual override when dynamic-follow is off.")
        .defaultValue(3.5)
        .min(1.0)
        .max(10.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> dynamicFollow = sgAnalysis.add(new BoolSetting.Builder()
        .name("dynamic-follow")
        .description("Dynamically compute follow distance from target reach, potion effects, and weapon type.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> followRecalcTicks = sgAnalysis.add(new IntSetting.Builder()
        .name("follow-recalc-ticks")
        .description("How often (in ticks) to re-analyze the target and recompute the dynamic follow distance.")
        .defaultValue(20)
        .min(5)
        .max(200)
        .sliderMax(200)
        .build()
    );

    // State

    private BrainState state = BrainState.IDLE;
    private LivingEntity currentTarget;
    private int tickCounter;
    private int stateTimer;
    private int followRecalcTimer;
    private CombatFollowController followController;
    private CombatTargetAnalyzer.TargetAnalysis lastAnalysis;
    private CombatTerrainGrid terrainGrid;
    private final HashMap<UUID, Long> lastAttackedTimestamps = new HashMap<>();
    private int stuckHealTicks;
    private ModuleAutomator automator;
    private CombatMode combatMode = CombatMode.AGGRESSIVE;
    private boolean stealthAntiDetectionEnabled = false;
    private int modeHoldTimer;
    private int switchTimer;
    private int lastSwitchTick = -999;
    private double currentTargetScore;
    private StrikePhase strikePhase = StrikePhase.BUBBLE;
    private int strikeTimer;
    private int bubbleTimer;
    private int threatHighTicks;
    private int threatLowTicks;
    private final Map<BrainState, Integer> lastStateExitPass = new HashMap<>();
    private int passCounter;

    // KillAura settings save/restore state
    private boolean savedKillAura = false;
    private boolean savedKillAuraState = false;
    private boolean savedPauseBaritone = false;
    private boolean savedAutoSwitch = false;
    private boolean savedSwapBack = false;
    private int savedMaxTargets = 1;
    private double savedRange = 4.5;
    private KillAura.RotationMode savedRotate = KillAura.RotationMode.Always;
    private Set<EntityType<?>> savedEntities = null;

    // Target tracking statistics
    private int trackedEntityCount = 0;
    private int killedInCombat = 0;
    private int killsInARow = 0;
    private int lastKillTick = -999;

    private boolean isReentryCooldownActive(BrainState stateCheck) {
        int lastExit = lastStateExitPass.getOrDefault(stateCheck, -9999);
        return (passCounter - lastExit) < stateReentryDelay.get();
    }

    public CombatBrainModule() {
        super(Categories.Combat, "combat-mode", "Autonomous combat AI brain. Auto-manages target selection, Baritone pathing, bubble hit-and-run, crystal fighting, and defensive abilities.", "combat-brain", "smart-combat");
    }

    @Override
    public void onActivate() {
        state = BrainState.SCANNING;
        currentTarget = null;
        tickCounter = 0;
        stateTimer = 0;
        lastSwitchTick = -999;
        switchTimer = 0;
        currentTargetScore = 0.0;
        strikePhase = StrikePhase.BUBBLE;
        strikeTimer = 0;
        bubbleTimer = 0;
        threatHighTicks = 0;
        threatLowTicks = 0;
        lastStateExitPass.clear();
        passCounter = 0;
        killedInCombat = 0;
        killsInARow = 0;
        trackedEntityCount = 0;
        lastKillTick = -999;
        savedKillAura = false;
        followController = new CombatFollowController();
        terrainGrid = new CombatTerrainGrid();
        automator = new ModuleAutomator(this);
        stealthAntiDetectionEnabled = false;
        lastAttackedTimestamps.clear();
        stuckHealTicks = 0;

        // Immediate target acquisition on activation so pathing starts instantly
        if (mc.player != null && mc.level != null) {
            currentTarget = findBestTarget();
            if (currentTarget != null) {
                transitionTo(BrainState.ANALYZING);
            }
        }

        info("CombatBrain AI enabled");
    }

    @Override
    public void onDeactivate() {
        if (followController != null) followController.stop();
        if (automator != null) automator.shutdown();
        if (stealthAntiDetectionEnabled) {
            disableModule(AntiDetectionModule.class);
            stealthAntiDetectionEnabled = false;
        }
        disableAllManagedModules();
        state = BrainState.IDLE;
        combatMode = CombatMode.AGGRESSIVE;
        modeHoldTimer = 0;
        currentTarget = null;
        followController = null;
        automator = null;
        lastStateExitPass.clear();
        passCounter = 0;
        killsInARow = 0;
        trackedEntityCount = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;

        // Run state machine every 2 ticks to reduce CPU
        if (tickCounter % 2 != 0) return;

        passCounter++;

        // Check health / threat
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int totems = countTotems();
        double threat = computeThreatLevel();

        // Tactical combat-mode evaluation
        if (modeSwitch.get()) {
            CombatMode proposed = CombatMode.evaluateCombatMode(
                threat,
                engageThreshold.get(),
                fleeThreshold.get(),
                health,
                totems,
                currentTarget,
                terrainGrid,
                false,
                false,
                crystal.get()
            );
            CombatMode prevMode = combatMode;
            combatMode = CombatMode.holdMode(combatMode, proposed, modeHoldTimer, modeHysteresisTicks.get());
            if (combatMode != prevMode) {
                modeHoldTimer = 0;
            } else {
                modeHoldTimer += 2;
            }

            // STEALTH mode wiring: enable AntiDetectionModule while hidden so
            // rotation jitter + sneak cycling mask bot-like behavior. Disable
            // and release when leaving STEALTH.
            if (combatMode == CombatMode.STEALTH) {
                if (!stealthAntiDetectionEnabled) {
                    enableModule(AntiDetectionModule.class);
                    stealthAntiDetectionEnabled = true;
                }
            } else if (stealthAntiDetectionEnabled) {
                disableModule(AntiDetectionModule.class);
                stealthAntiDetectionEnabled = false;
            }
        }

        // Emergency check
        if (health <= 4.0f && totems == 0 && threat > 0.8f) {
            transitionTo(BrainState.EMERGENCY_LOG);
            return;
        }

        // Target kill tracking
        if (currentTarget != null && !currentTarget.isAlive()) {
            killedInCombat++;
            killsInARow++;
            lastKillTick = tickCounter;
            currentTarget = null;
        }

        // Track combat interactions for targetFriendly
        if (mc.player.hurtTime > 0) {
            LivingEntity attacker = mc.player.getLastHurtByMob();
            if (attacker instanceof Player p) {
                lastAttackedTimestamps.put(p.getUUID(), (long) tickCounter);
            }
        }
        if (currentTarget != null && mc.player.getLastHurtMob() == currentTarget) {
            lastAttackedTimestamps.put(currentTarget.getUUID(), (long) tickCounter);
        }

        // State machine
        switch (state) {
            case IDLE:
                // Hold in IDLE for stateReentryDelay passes (stateHold / cooldownTicks) to let BURROW/FLEE recover
                if (stateTimer >= stateReentryDelay.get()) {
                    transitionTo(BrainState.SCANNING);
                }
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

                // Analyze target with real analyzer
                lastAnalysis = CombatTargetAnalyzer.analyze(currentTarget);

                boolean isUndergeared = viabilityCheck.get() && CombatTargetAnalyzer.undergeared(currentTarget, undergearRatio.get());
                boolean canWin = isInvincible() || !viabilityCheck.get() || (assessViability(currentTarget) && !isUndergeared);
                // Dwell gate / reentry check: if recently left RETREATING, do NOT immediately re-enter RETREATING
                // (prevents RETREATING->HEALING->SCANNING->ANALYZING->RETREATING stateLoop spam). Engage instead.
                if (canWin || isReentryCooldownActive(BrainState.RETREATING)) {
                    transitionTo(BrainState.ENGAGING);
                } else {
                    transitionTo(BrainState.RETREATING);
                }
                break;

            case ENGAGING:
                if (currentTarget == null || !isTargetValid(currentTarget)) {
                    threatHighTicks = 0;
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                // Check if threat got too high (threatHysteresis threatDwell dwell requirement; bypassed when isInvincible())
                if (!isInvincible() && threat >= fleeThreshold.get()) {
                    threatHighTicks++;
                    if (threatHighTicks >= threatHighDwell.get()) {
                        if (!isReentryCooldownActive(BrainState.RETREATING)) {
                            threatHighTicks = 0;
                            transitionTo(BrainState.RETREATING);
                            break;
                        }
                    }
                } else {
                    threatHighTicks = 0;
                }

                // Maintain modules
                if (autoModules.get()) enableCombatModules();
                doEngageTick();
                break;

            case RETREATING:
                if (isInvincible() || threat < engageThreshold.get()) {
                    if (isInvincible()) {
                        threatLowTicks = 0;
                        transitionTo(BrainState.SCANNING);
                        break;
                    }
                    threatLowTicks++;
                    if (threatLowTicks >= threatLowDwell.get()) {
                        threatLowTicks = 0;
                        transitionTo(BrainState.SCANNING);
                        break;
                    }
                } else {
                    threatLowTicks = 0;
                }

                // Try to heal first
                if (health < 10.0f) {
                    if (!isReentryCooldownActive(BrainState.HEALING)) {
                        transitionTo(BrainState.HEALING);
                        break;
                    }
                }

                if (autoModules.get()) disableCombatModules();
                doRetreatTick();
                break;

            case HEALING:
                if (health >= 14.0f || threat < engageThreshold.get()) {
                    stuckHealTicks = 0;
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                // If health stays below 8 for too long, flee
                if (health < 8.0f) {
                    stuckHealTicks++;
                    if (stuckHealTicks > 100) {
                        if (!isReentryCooldownActive(BrainState.FLEEING)) {
                            stuckHealTicks = 0;
                            transitionTo(BrainState.FLEEING);
                            break;
                        }
                    }
                } else {
                    stuckHealTicks = 0;
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
                break;
        }

        // Stuck detection: if same state > 200 ticks with no progress, reset
        if (stateTimer > 200) {
            stateTimer = 0;
            transitionTo(BrainState.IDLE);
        }

        // Module automator update during active combat states
        if (autoModules.get() && automator != null) {
            if (state == BrainState.ENGAGING || state == BrainState.RETREATING
                || state == BrainState.HEALING || state == BrainState.FLEEING) {
                automator.update(currentTarget, terrainGrid);
            }
        }

        stateTimer++;
    }

    // --- State transitions ---

    private void transitionTo(BrainState newState) {
        if (state == newState) return;

        BrainState oldState = state;
        onExitState(state);
        state = newState;
        stateTimer = 0;
        threatHighTicks = 0;
        threatLowTicks = 0;
        // Record the pass at which we LEFT the old state, so re-entering
        // RETREATING/HEALING/FLEEING can be gated by isReentryCooldownActive.
        // (Without this write, the cooldown map always reads -9999 and the
        // re-entry guard is a silent no-op.)
        lastStateExitPass.put(oldState, passCounter);
        onEnterState(state);
        onStateChange(oldState, newState);
    }

    private void onStateChange(BrainState oldState, BrainState newState) {
        if (automator != null) {
            automator.onStateChange(oldState, newState);
        }
    }

    private void onEnterState(BrainState s) {
        switch (s) {
            case ENGAGING:
                if (autoModules.get()) enableCombatModules();
                // H4 fix: a stale REQUEST_PAUSE from KillAura/BowAimbot/AutoEat
                // (PathManagers.get().pause()) can leave BaritonePathManager's
                // process active, suppressing follow. Force-resume before engaging.
                PathManagers.get().resume();
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

    // --- Target selection & multi-target scoring ---

    private LivingEntity findBestTarget() {
        if (mc.level == null || mc.player == null) return null;

        // Candidate predicate: handles BOTH players and mobs
        double acquireRangeSq = acquireRange.get() * acquireRange.get();
        java.util.function.Predicate<Entity> candidatePredicate = entity -> {
            if (!(entity instanceof LivingEntity le)) return false;
            if (le == mc.player) return false;
            if (!le.isAlive()) return false;
            // Acquire targets up to acquireRange (default 64) so long-range targets are locked onto and pathed to
            if (le.distanceToSqr(mc.player) > acquireRangeSq) return false;

            Set<EntityType<?>> types = entities.get();
            if (types.isEmpty()) {
                types = Set.of(
                    EntityType.PLAYER,
                    EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER, EntityType.ENDERMAN,
                    EntityType.PIGLIN, EntityType.ZOMBIFIED_PIGLIN, EntityType.DROWNED, EntityType.HUSK, EntityType.STRAY,
                    EntityType.WITHER_SKELETON, EntityType.SLIME, EntityType.MAGMA_CUBE, EntityType.BLAZE, EntityType.GHAST,
                    EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.RAVAGER, EntityType.EVOKER,
                    EntityType.WARDEN, EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.BREEZE, EntityType.BOGGED
                );
            }
            if (!types.contains(le.getType())) return false;

            // Player targeting safety checks
            if (le instanceof Player player) {
                if (!targetPlayers.get()) return false;
                if (player.isCreative()) return false;
                if (!Friends.get().shouldAttack(player)) return false;
                if (targetFriendly.get()) {
                    // Only attack if they hit us recently or we hit them
                    Long lastAttack = lastAttackedTimestamps.get(player.getUUID());
                    if (lastAttack == null || tickCounter - lastAttack > 600) return false;
                }
            }

            return true;
        };

        // Fallback: single-target LowestDistance mode if multi-target is disabled
        if (!multiTarget.get()) {
            Entity target = TargetUtils.get(candidatePredicate, SortPriority.LowestDistance);
            if (target instanceof LivingEntity le) return le;
            return null;
        }

        // Smart multi-target scoring mode
        LivingEntity bestCandidate = null;
        double bestScore = -1.0;
        int candidateCount = 0;

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!candidatePredicate.test(entity)) continue;
            candidateCount++;
            if (entity instanceof LivingEntity le) {
                double score = scoreTarget(le);
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = le;
                }
            }
        }
        trackedEntityCount = candidateCount;

        // Anti-flicker / switch-delay logic with target-cycling:
        if (currentTarget != null && isTargetValid(currentTarget)) {
            int ticksSinceLastSwitch = tickCounter - lastSwitchTick;
            switchTimer = ticksSinceLastSwitch;

            if (bestCandidate != null && bestCandidate != currentTarget) {
                double currentScore = scoreTarget(currentTarget);
                boolean marginExceeded = ticksSinceLastSwitch >= switchDelay.get() && bestScore > currentScore + 0.15;
                boolean cycleTimeout = ticksSinceLastSwitch >= switchDelay.get() * 2 && bestScore > currentScore;

                if (marginExceeded || cycleTimeout) {
                    lastSwitchTick = tickCounter;
                    currentTargetScore = bestScore;
                    return bestCandidate;
                }
            }

            currentTargetScore = scoreTarget(currentTarget);
            return currentTarget;
        }

        // Initial acquisition or replacing invalid target
        if (bestCandidate != null) {
            lastSwitchTick = tickCounter;
            switchTimer = 0;
            currentTargetScore = bestScore;
        }

        return bestCandidate;
    }

    /**
     * Helper method to compute smart multi-target engagement score for a candidate entity.
     */
    private double scoreTarget(LivingEntity target) {
        CombatTargetAnalyzer.TargetAnalysis analysis = CombatTargetAnalyzer.analyze(target);
        if (analysis == null) return 0.0;
        return CombatTargetAnalyzer.targetScore(
            analysis,
            targetDistanceWeight.get(),
            targetHealthWeight.get(),
            targetDefenseWeight.get(),
            targetWeaponWeight.get(),
            acquireRange.get()
        );
    }

    private boolean isTargetValid(LivingEntity entity) {
        if (entity == null || !entity.isAlive()) return false;
        // Chase range is much larger than acquisition range: once a target is
        // acquired, the brain follows it (via baritone) up to max-chase-range
        // instead of abandoning the chase at target-range + 2.
        if (entity.distanceTo(mc.player) > maxChaseRange.get()) return false;
        return true;
    }

    private boolean assessViability(LivingEntity target) {
        if (!analyzeGear.get()) return true;
        double viability = CombatTargetAnalyzer.calculateViability(target);
        return viability > 0.4;
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

    // --- Threat analysis (refined) ---

    /**
     * Checks if mc.player is invincible in CREATIVE or SPECTATOR gameMode (or has instabuild abilities enabled).
     * Prevents threat calculations, threatHysteresis retreats (retreatTicks), and retreat/flee FSM transitions in creative mode.
     */
    private boolean isInvincible() {
        if (mc.player == null) return false;
        return mc.player.isCreative() || mc.player.isSpectator() || mc.player.getAbilities().instabuild;
    }

    private double computeThreatLevel() {
        if (mc.player == null) return 0.0;
        if (isInvincible()) return 0.0;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();

        double selfVuln = 1.0 / (1.0 + Math.exp(0.5 * (health - 10.0)));

        int totems = countTotems();
        double totemExposure = Math.pow(0.4, totems);

        double envPressure = 0.0;
        double crystalPressure = 0.0;

        if (mc.level != null) {
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                    boolean isHostile = le.getType().getCategory() == MobCategory.MONSTER
                        || (le instanceof Player && le != mc.player);
                    if (!isHostile) continue;

                    double dist = le.distanceTo(mc.player);
                    if (dist < 8.0) {
                        double distFactor = 1.0 / (1.0 + dist * dist);
                        float dmg = meteordevelopment.meteorclient.utils.entity.DamageUtils.getAttackDamage(le, mc.player);
                        double dmgNorm = Math.min(1.0, dmg / 10.0);

                        double losFactor = (terrainGrid != null && terrainGrid.isTargetVisible(le))
                            ? losWeight.get() : 1.0;

                        envPressure += distFactor * dmgNorm * losFactor;
                    }
                } else if (entity.getType() == EntityType.END_CRYSTAL) {
                    double dist = entity.distanceTo(mc.player);
                    if (dist < 12.0) {
                        crystalPressure += (12.0 - dist) / 12.0;
                    }
                }
            }
        }

        double envThreat = Math.min(1.0, envPressure * 0.8);

        double coverFactor = 1.0;
        if (coverModifier.get() && mc.level != null) {
            BlockPos playerPos = mc.player.blockPosition();
            int solidNeighbors = 0;
            int[] dx = {-1, 1, 0, 0, -1, -1, 1, 1};
            int[] dz = {0, 0, -1, 1, -1, 1, -1, 1};

            for (int i = 0; i < 8; i++) {
                BlockPos footCheck = playerPos.offset(dx[i], 0, dz[i]);
                BlockPos headCheck = playerPos.offset(dx[i], 1, dz[i]);
                if (!mc.level.getBlockState(footCheck).isAir() && !mc.level.getBlockState(headCheck).isAir()) {
                    solidNeighbors++;
                }
            }

            coverFactor = 1.2 - (solidNeighbors * 0.05);
        }

        double survivalProb = (1.0 - selfVuln * 0.5)
                            * (1.0 - totemExposure * 0.2)
                            * (1.0 - envThreat * 0.7);

        double threat = 1.0 - survivalProb + crystalPressure * crystalThreat.get();

        return Mth.clamp(threat * coverFactor, 0.0, 1.0);
    }

    // --- Module management ---

    private void syncKillAura() {
        KillAura killAura = Modules.get().get(KillAura.class);
        if (killAura == null) return;

        if (!savedKillAura) {
            savedKillAuraState = killAura.isActive();
            savedPauseBaritone = ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-baritone")).get();
            savedAutoSwitch = ((Setting<Boolean>) (Setting<?>) killAura.settings.get("auto-switch")).get();
            savedSwapBack = ((Setting<Boolean>) (Setting<?>) killAura.settings.get("swap-back")).get();
            savedMaxTargets = ((Setting<Integer>) (Setting<?>) killAura.settings.get("max-targets")).get();
            savedRange = ((Setting<Double>) (Setting<?>) killAura.settings.get("range")).get();
            savedRotate = ((Setting<KillAura.RotationMode>) (Setting<?>) killAura.settings.get("rotate")).get();
            savedEntities = new java.util.HashSet<>(
                ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).get()
            );
            savedKillAura = true;
        }

        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-baritone")).set(false);
        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("auto-switch")).set(true);
        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("swap-back")).set(false);

        Set<EntityType<?>> killAuraEntities = new java.util.HashSet<>(entities.get());
        if (targetPlayers.get()) killAuraEntities.add(EntityType.PLAYER);
        ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).set(killAuraEntities);
        ((Setting<Integer>) (Setting<?>) killAura.settings.get("max-targets")).set(3);

        // ANTICHEAT: clamp KillAura attack range to 2.9 (vanilla reach is 3.0,
        // Grim allows 3.01 but ADDS the player's current velocity to the reach
        // calc — a sprinting bot attacking at 3.0 exceeds it on first contact).
        // The 0.1 margin absorbs movement; the brain's follow bubble handles
        // positioning. Never attack beyond vanilla reach.
        ((Setting<Double>) (Setting<?>) killAura.settings.get("range")).set(Math.min(
            ((Setting<Double>) (Setting<?>) killAura.settings.get("range")).get(), 2.9));

        // ANTICHEAT: force rotation mode to OnHit (rotate only at the moment of
        // attack) instead of Always. Constant per-tick aim-lock at the target's
        // exact body angles is the classic killaura tell (AimModulo360 /
        // rotation-GCD checks). OnHit sends far fewer rotation packets and only
        // when actually attacking — looks like a player flicking to their target.
        ((Setting<KillAura.RotationMode>) (Setting<?>) killAura.settings.get("rotate")).set(KillAura.RotationMode.OnHit);
    }

    private void restoreKillAura() {
        if (!savedKillAura) return;
        KillAura killAura = Modules.get().get(KillAura.class);
        if (killAura != null) {
            ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-baritone")).set(savedPauseBaritone);
            ((Setting<Boolean>) (Setting<?>) killAura.settings.get("auto-switch")).set(savedAutoSwitch);
            ((Setting<Boolean>) (Setting<?>) killAura.settings.get("swap-back")).set(savedSwapBack);
            if (savedEntities != null) {
                ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).set(savedEntities);
            }
            ((Setting<Integer>) (Setting<?>) killAura.settings.get("max-targets")).set(savedMaxTargets);
            ((Setting<Double>) (Setting<?>) killAura.settings.get("range")).set(savedRange);
            ((Setting<KillAura.RotationMode>) (Setting<?>) killAura.settings.get("rotate")).set(savedRotate);
        }
        savedKillAura = false;
    }

    private void enableCombatModules() {
        syncKillAura();

        enableModule(KillAura.class);
        if (autoArmor.get()) enableModule(AutoArmor.class);
        if (autoWeapon.get()) enableModule(AutoWeapon.class);
        enableModule(AutoTool.class);
        if (shieldSwap.get()) enableModule(ShieldAutoSwapModule.class);

        if (criticals.get()) enableModule(Criticals.class);
    }

    private void disableCombatModules() {
        restoreKillAura();

        disableModule(KillAura.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);
        disableModule(AutoTool.class);
        disableModule(ShieldAutoSwapModule.class);
        disableModule(Criticals.class);
    }

    private void disableAllManagedModules() {
        restoreKillAura();

        disableModule(KillAura.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);
        disableModule(AutoTool.class);
        disableModule(ShieldAutoSwapModule.class);
        disableModule(Criticals.class);
        disableModule(HoleFiller.class);
        disableModule(Surround.class);
        disableModule(ArrowDodge.class);
        if (automator != null) {
            automator.shutdown();
        }
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

        // Re-analyze target periodically (or on target change) so the dynamic
        // follow distance tracks reach/potion/gear changes.
        if (lastAnalysis == null
            || lastAnalysis.entity() != currentTarget
            || followRecalcTimer >= followRecalcTicks.get()) {
            lastAnalysis = CombatTargetAnalyzer.analyze(currentTarget);
            followRecalcTimer = 0;
        }
        followRecalcTimer++;

        // Resolve follow distance: dynamic (reach/potion/weapon aware) or manual override (safety bubble)
        double targetDistance = followDistance.get();
        if (dynamicFollow.get() && lastAnalysis != null) {
            targetDistance = CombatTargetAnalyzer.computeDynamicFollowDistance(lastAnalysis, CombatMode.modePadding(combatMode));
        }

        // If hit-and-run is disabled, use standard follow behavior
        if (!hitAndRun.get()) {
            if (terrainGrid != null) {
                terrainGrid.update(currentTarget);
                List<BlockPos> blockers = terrainGrid.getPathBlocks();
                if (!blockers.isEmpty()) {
                    if (followController != null) {
                        followController.follow(currentTarget, targetDistance);
                    }
                    return;
                }
            }

            double dist = mc.player.distanceTo(currentTarget);
            if (dist > targetDistance + 0.5 && followController != null) {
                followController.follow(currentTarget, targetDistance);
            }
            return;
        }

        // Hit-and-run strike cycle — cooldown-synchronized
        double effectiveStrikeDist = Math.min(strikeDistance.get(), targetDistance);

        // Terrain obstacle check using active phase's distance
        if (terrainGrid != null) {
            terrainGrid.update(currentTarget);
            List<BlockPos> blockers = terrainGrid.getPathBlocks();
            if (!blockers.isEmpty()) {
                if (followController != null) {
                    double activeDist = (strikePhase == StrikePhase.STRIKE) ? effectiveStrikeDist : targetDistance;
                    followController.follow(currentTarget, activeDist);
                }
                return;
            }
        }

        double dist = mc.player.distanceTo(currentTarget);

        double myAtkSpeed = 1.6;
        try {
            myAtkSpeed = mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        } catch (Exception ignored) {}
        int optimalStrikeTicks = (int) Math.ceil(20.0 / myAtkSpeed) + 4;
        int activeStrikeDuration = Math.max(optimalStrikeTicks, strikeDurationTicks.get());

        boolean targetJustSwung = false;
        if (currentTarget instanceof Player targetPlayer) {
            targetJustSwung = targetPlayer.getAttackStrengthScale(0.0f) < 0.3f;
        }

        if (strikePhase == StrikePhase.STRIKE) {
            if (strikeTimer >= activeStrikeDuration) {
                strikePhase = StrikePhase.BUBBLE;
                strikeTimer = 0;
                bubbleTimer = 0;
                if (followController != null && dist > targetDistance + 0.5) {
                    followController.follow(currentTarget, targetDistance);
                }
            } else {
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
                strikeTimer += 2;
            }
        } else { // BUBBLE phase
            boolean cooldownReady = bubbleTimer >= retreatCooldownTicks.get();
            // targetJustSwung already computed above (line ~1171) from
            // currentTarget.getAttackStrengthScale — reuse it for the swarm gate.
            // Never dive into STRIKE while surrounded: attacking into a swarm
            // guarantees we get hit by the crowd. Only strike when the immediate
            // vicinity (2 blocks) has 0 other hostiles, or at most 1 total when
            // the primary target just swung (safe trade window).
            boolean swarmClear = countNearbyHostiles(2.0) == 0;
            boolean safeWindow = countNearbyHostiles(3.0) <= 1 && targetJustSwung;
            boolean shouldStrike = cooldownReady && (swarmClear || safeWindow);

            if (dist <= targetDistance + 0.5 && shouldStrike) {
                strikePhase = StrikePhase.STRIKE;
                strikeTimer = 0;
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
            } else {
                if (dist > targetDistance + 0.5 && followController != null) {
                    followController.follow(currentTarget, targetDistance);
                }
                bubbleTimer += 2;
            }
        }
    }

    /**
     * Counts living hostile entities (monsters or players) within the given
     * radius of the local player. Used to gate the hit-and-run strike phase:
     * diving into a swarm is suicide, so STRIKE only happens when the nearby
     * crowd is empty or a safe trade window exists.
     */
    private int countNearbyHostiles(double radius) {
        if (mc.level == null || mc.player == null) return 0;
        int count = 0;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
            boolean hostile = le.getType().getCategory() == MobCategory.MONSTER
                || (le instanceof Player && le != mc.player);
            if (!hostile) continue;
            if (le.distanceToSqr(mc.player) <= radius * radius) count++;
        }
        return count;
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
        switch (emergencyMode.get()) {
            case DISCONNECT -> {
                if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(Component.literal("CombatBrain: Emergency Log triggered."));
                }
                disableAllManagedModules();
                state = BrainState.IDLE;
                toggle(); // Only DISCONNECT mode hard-disables module
            }
            case BURROW -> {
                info("Emergency: burrowing");
                disableModule(KillAura.class);
                disableModule(ArrowDodge.class);
                enableModule(Surround.class);
                enableModule(HoleFiller.class);
                transitionTo(BrainState.HEALING);
            }
            case FLEE -> {
                info("Emergency: fleeing");
                disableAllManagedModules();
                if (mc.player != null) {
                    mc.player.setSprinting(true);
                }
                if (followController != null && currentTarget != null) {
                    followController.flee(currentTarget, fleeDistance.get() * 2.0);
                }
                transitionTo(BrainState.FLEEING);
            }
        }
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

    public CombatMode getCombatMode() {
        return combatMode;
    }

    public BrainState getState() {
        return state;
    }

    public LivingEntity getCurrentTarget() {
        return currentTarget;
    }

    public StrikePhase getStrikePhase() {
        return strikePhase;
    }

    public int getKillsInCombat() {
        return killedInCombat;
    }

    public int getKillsInARow() {
        return killsInARow;
    }

    public int getTrackedEntityCount() {
        return trackedEntityCount;
    }

    public double getCurrentTargetScore() {
        return currentTargetScore;
    }

    @Override
    public String getInfoString() {
        if (currentTarget != null) {
            return currentTarget.getName().getString() + " (" + state.name() + "/" + combatMode.name() + "/" + strikePhase.name() + ")";
        }
        return state.name() + "/" + combatMode.name();
    }
}
