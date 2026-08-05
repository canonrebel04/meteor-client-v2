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

import java.util.HashMap;
import java.util.List;
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

    private final Setting<Integer> modeHysteresisTicks = sgEngagement.add(new IntSetting.Builder()
        .name("mode-hysteresis-ticks")
        .description("Minimum ticks to hold a combat mode before switching (prevents flapping).")
        .defaultValue(20)
        .min(0)
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

    public CombatBrainModule() {
        super(Categories.Combat, "combat-brain", "Advanced combat AI brain. Auto-manages all combat modules, target selection, pathing, and threat analysis.");
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

                // Analyze target with real analyzer
                lastAnalysis = CombatTargetAnalyzer.analyze(currentTarget);

                boolean isUndergeared = viabilityCheck.get() && CombatTargetAnalyzer.undergeared(currentTarget, undergearRatio.get());
                boolean canWin = !viabilityCheck.get() || (assessViability(currentTarget) && !isUndergeared);
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
                    stuckHealTicks = 0;
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                // If health stays below 8 for too long, flee
                if (health < 8.0f) {
                    stuckHealTicks++;
                    if (stuckHealTicks > 100) {
                        stuckHealTicks = 0;
                        transitionTo(BrainState.FLEEING);
                        break;
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
                transitionTo(BrainState.IDLE);
                toggle(); // Disable the module after emergency log
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
        java.util.function.Predicate<Entity> candidatePredicate = entity -> {
            if (!(entity instanceof LivingEntity le)) return false;
            if (le == mc.player) return false;
            if (!le.isAlive()) return false;
            // Acquire targets up to acquireRange (default 64) so long-range targets are locked onto and pathed to
            if (le.distanceTo(mc.player) > acquireRange.get()) return false;

            // Check entity type filter (mobs)
            if (targetEntities.get().contains(le.getType())) return true;

            // Player targeting
            if (le instanceof Player player) {
                if (!targetPlayers.get()) return false;
                if (Friends.get().isFriend(player)) return false;
                if (targetFriendly.get()) {
                    // Only attack if they hit us recently or we hit them
                    Long lastAttack = lastAttackedTimestamps.get(player.getUUID());
                    if (lastAttack == null || tickCounter - lastAttack > 600) return false;
                }
                return true;
            }

            return false;
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

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!candidatePredicate.test(entity)) continue;
            if (entity instanceof LivingEntity le) {
                double score = scoreTarget(le);
                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = le;
                }
            }
        }

        // Anti-flicker / switch-delay logic:
        // Initial target acquisition (currentTarget == null) bypasses switch delay.
        // Post-acquisition switches require switch-delay ticks to elapse and new best score to exceed current by margin 0.15.
        if (currentTarget != null && isTargetValid(currentTarget)) {
            int ticksSinceLastSwitch = tickCounter - lastSwitchTick;
            switchTimer = ticksSinceLastSwitch;

            if (bestCandidate != null && bestCandidate != currentTarget) {
                double currentScore = scoreTarget(currentTarget);
                if (ticksSinceLastSwitch >= switchDelay.get() && bestScore > currentScore + 0.15) {
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

    private double computeThreatLevel() {
        if (mc.player == null) return 0.0;

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double healthFactor = 1.0 - (health / 20.0); // 0 = full, 1 = near death

        int totems = countTotems();
        double totemFactor = totems > 0 ? 0.0 : 0.3;

        // Check nearby threats and crystal entities
        double nearbyThreats = 0.0;
        int nearbyCrystals = 0;

        if (mc.level != null) {
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                if (entity instanceof LivingEntity le && le != mc.player && le.isAlive()) {
                    double dist = le.distanceTo(mc.player);
                    if (dist < 6.0) {
                        double losFactor = (terrainGrid != null && terrainGrid.isTargetVisible(le)) ? losWeight.get() : 1.0;
                        nearbyThreats += (1.0 - dist / 6.0) * 0.3 * losFactor;
                    }
                } else if (entity.getType() == EntityType.END_CRYSTAL) {
                    double dist = entity.distanceTo(mc.player);
                    if (dist < 6.0) {
                        nearbyCrystals++;
                    }
                }
            }
        }

        double threat = healthFactor * 0.4 + totemFactor * 0.2 + Math.min(nearbyThreats, 0.4) + (nearbyCrystals * crystalThreat.get());

        // Cover modifier calculation
        if (coverModifier.get() && mc.level != null) {
            BlockPos playerPos = mc.player.blockPosition();
            int solidNeighbors = 0;
            int[] dx = {-1, 1, 0, 0, -1, -1, 1, 1};
            int[] dz = {0, 0, -1, 1, -1, 1, -1, 1};

            for (int i = 0; i < 8; i++) {
                BlockPos checkPos = playerPos.offset(dx[i], 0, dz[i]);
                if (!mc.level.getBlockState(checkPos).isAir()) {
                    solidNeighbors++;
                }
            }

            if (solidNeighbors == 0) {
                threat *= 1.2;
            } else if (solidNeighbors >= 4) {
                threat *= 0.8;
            }
        }

        return Mth.clamp(threat, 0.0, 1.0);
    }

    // --- Module management ---

    private void enableCombatModules() {
        // CRITICAL: Disable KillAura's pause-baritone or it will fight us for control
        // KillAura.pauseOnCombat (default true) calls PathManagers.get().pause() which
        // registers a REQUEST_PAUSE process that blocks all other baritone commands
        KillAura killAura = Modules.get().get(KillAura.class);
        if (killAura != null) {
            ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-baritone")).set(false);

            // Sync KillAura's entity filter with the brain's target selection.
            // KillAura defaults to PLAYER-only — without this it would never swing
            // at the mobs the brain is targeting (observed: enabled but no attacks).
            // Build the union: brain's target-entities + PLAYER when target-players
            // is on, always preserving KillAura's own configured entities.
            Set<EntityType<?>> killAuraEntities = new java.util.HashSet<>(
                ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).get()
            );
            killAuraEntities.addAll(targetEntities.get());
            if (targetPlayers.get()) killAuraEntities.add(EntityType.PLAYER);
            ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).set(killAuraEntities);
        }

        enableModule(KillAura.class);
        enableModule(AutoArmor.class);
        enableModule(AutoWeapon.class);

        if (criticals.get()) enableModule(Criticals.class);
    }

    private void disableCombatModules() {
        disableModule(KillAura.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);

        disableModule(Criticals.class);
    }

    private void disableAllManagedModules() {
        disableModule(KillAura.class);
        disableModule(AutoArmor.class);
        disableModule(AutoWeapon.class);
        disableModule(Criticals.class);
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

        // Hit-and-run strike cycle
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

        if (strikePhase == StrikePhase.STRIKE) {
            if (strikeTimer >= strikeDurationTicks.get()) {
                // Strike phase finished -> retreat back to safety bubble
                strikePhase = StrikePhase.BUBBLE;
                strikeTimer = 0;
                bubbleTimer = 0;
                if (followController != null && dist > targetDistance + 0.5) {
                    followController.follow(currentTarget, targetDistance);
                }
            } else {
                // Inside strike phase -> follow at strike distance inside attack range
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
                strikeTimer += 2;
            }
        } else { // BUBBLE phase
            if (dist <= targetDistance + 0.5 && bubbleTimer >= retreatCooldownTicks.get()) {
                // Reached bubble and retreat cooldown elapsed -> dart in for strike
                strikePhase = StrikePhase.STRIKE;
                strikeTimer = 0;
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
            } else {
                // Stay in safety bubble phase
                if (dist > targetDistance + 0.5 && followController != null) {
                    followController.follow(currentTarget, targetDistance);
                }
                bubbleTimer += 2;
            }
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
        switch (emergencyMode.get()) {
            case DISCONNECT -> {
                if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(Component.literal("CombatBrain: Emergency Log triggered."));
                }
                disableAllManagedModules();
            }
            case BURROW -> {
                info("Emergency: burrowing");
                disableModule(KillAura.class);
                disableModule(ArrowDodge.class);
                enableModule(Surround.class);
                enableModule(HoleFiller.class);
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
            }
        }
        state = BrainState.IDLE;
        toggle(); // Disable CombatBrain module after emergency action
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

    @Override
    public String getInfoString() {
        if (currentTarget != null) {
            return currentTarget.getName().getString() + " (" + state.name() + "/" + combatMode.name() + "/" + strikePhase.name() + ")";
        }
        return state.name() + "/" + combatMode.name();
    }
}
