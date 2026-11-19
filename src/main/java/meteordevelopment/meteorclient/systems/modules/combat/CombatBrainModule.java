/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.utils.debug.DebugLog;
import meteordevelopment.meteorclient.systems.modules.combat.model.DisengageLadder;
import meteordevelopment.meteorclient.systems.modules.combat.model.EscapeSelector;
import meteordevelopment.meteorclient.systems.modules.combat.model.HungerPlanner;
import meteordevelopment.meteorclient.systems.modules.combat.model.PingModel;
import meteordevelopment.meteorclient.systems.modules.combat.model.PursuitTracker;
import meteordevelopment.meteorclient.systems.modules.combat.model.MatchupAssessment;
import meteordevelopment.meteorclient.systems.modules.combat.model.OpponentMemory;
import meteordevelopment.meteorclient.systems.modules.combat.model.OpponentModel;
import meteordevelopment.meteorclient.systems.modules.combat.model.RegearPlan;
import meteordevelopment.meteorclient.systems.modules.combat.model.RespawnPolicy;
import meteordevelopment.meteorclient.systems.modules.combat.model.VerdictGate;
import meteordevelopment.meteorclient.systems.modules.combat.model.WeaponKind;
import baritone.api.BaritoneAPI;
import baritone.api.process.IRestockProcess;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.state.BlockState;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.utils.misc.perf.TickBudget;
import meteordevelopment.meteorclient.utils.player.CameraArbiter;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.entity.Target;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Creeper;
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
        EMERGENCY_HOLE,  // Navigate to nearest safe 1x1 hole then surround
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
            EntityTypes.PLAYER,
            EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER, EntityTypes.ENDERMAN,
            EntityTypes.PIGLIN, EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.DROWNED, EntityTypes.HUSK, EntityTypes.STRAY,
            EntityTypes.WITHER_SKELETON, EntityTypes.SLIME, EntityTypes.MAGMA_CUBE, EntityTypes.BLAZE, EntityTypes.GHAST,
            EntityTypes.WITCH, EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.RAVAGER, EntityTypes.EVOKER,
            EntityTypes.WARDEN, EntityTypes.WITHER, EntityTypes.ENDER_DRAGON, EntityTypes.BREEZE, EntityTypes.BOGGED
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

    private final Setting<Double> targetSameLevelWeight = sgScoring.add(new DoubleSetting.Builder()
        .name("same-level-weight")
        .description("Bonus score for targets at the same Y-level as the player. Higher values strongly prefer targets reachable without digging.")
        .defaultValue(0.35)
        .min(0.0)
        .max(1.0)
        .sliderMax(1.0)
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
        .defaultValue(2.2)
        .min(1.0)
        .max(6.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Double> bubbleDistance = sgEngagement.add(new DoubleSetting.Builder()
        .name("bubble-distance")
        .description("Safe distance to hold between strikes, OUTSIDE the target's melee reach (zombie/skeleton ~3, creeper blast ~3.5). The bot retreats to this bubble instead of standing in range and trading hits.")
        .defaultValue(4.5)
        .min(3.0)
        .max(10.0)
        .sliderMax(10.0)
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

    private final Setting<Boolean> creeperDefense = sgEngagement.add(new BoolSetting.Builder()
        .name("creeper-defense")
        .description("Detect swelling creepers, face them directly, raise shield to block explosions, or sprint away if no shield.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> bhop = sgEngagement.add(new BoolSetting.Builder()
        .name("bhop")
        .description("Jump-sprint (bunny hop) while moving forward on open ground to gain momentum and emulate player movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> projectileDeflect = sgEngagement.add(new BoolSetting.Builder()
        .name("projectile-deflect")
        .description("Melee-swing to deflect incoming Ghast fireballs and Breeze wind charges back at senders.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> wTapSprintReset = sgEngagement.add(new BoolSetting.Builder()
        .name("w-tap-sprint-reset")
        .description("Reset sprint on attack impact to re-apply the sprint first-hit knockback bonus.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> circleStrafe = sgEngagement.add(new BoolSetting.Builder()
        .name("circle-strafe")
        .description("Orbit around single melee opponents in 1v1 engagements to avoid their forward swing cone.")
        .defaultValue(true)
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

    final Setting<Boolean> witherMilk = sgAutomator.add(new BoolSetting.Builder()
        .name("wither-milk")
        .description("Auto-drink milk when afflicted with Wither effect.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> fireResPotion = sgAutomator.add(new BoolSetting.Builder()
        .name("fire-res-potion")
        .description("Auto-drink Fire Resistance potion when fighting fire mobs (Blaze/Ghast/Magma Cube) or in the Nether.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> wardenCounter = sgAutomator.add(new BoolSetting.Builder()
        .name("warden-counter")
        .description("Auto-drink Resistance potion and stealthily sneak when a Warden is nearby.")
        .defaultValue(true)
        .build()
    );

    final Setting<Boolean> noFallClutch = sgAutomator.add(new BoolSetting.Builder()
        .name("nofall-clutch")
        .description("Auto-enable NoFall bucket clutch when launched airborne with high fall velocity.")
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
        .defaultValue(2.2)
        .min(1.0)
        .max(10.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Boolean> dynamicFollow = sgAnalysis.add(new BoolSetting.Builder()
        .name("dynamic-follow")
        .description("Dynamically compute follow distance from target reach, potion effects, and weapon type.")
        .defaultValue(false)
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

    // --- Survival (P1: verdict-driven fight-or-flight, escapes, defense, regear, learning) ---

    private final SettingGroup sgSurvival = settings.createGroup("Survival");

    private final Setting<MatchupAssessment.AggressionProfile> aggression = sgSurvival.add(new EnumSetting.Builder<MatchupAssessment.AggressionProfile>()
        .name("aggression")
        .description("How good the matchup must look before fighting. COWARD only takes clear wins, BERSERK takes risky trades.")
        .defaultValue(MatchupAssessment.AggressionProfile.BALANCED)
        .build()
    );

    private final Setting<Boolean> verdictFlee = sgSurvival.add(new BoolSetting.Builder()
        .name("verdict-flee")
        .description("Flee when the matchup verdict is LOSE/HOPELESS and only fight then if cornered. Off = legacy threat thresholds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> announceVerdict = sgSurvival.add(new BoolSetting.Builder()
        .name("announce-verdict")
        .description("Print the matchup verdict and its reasons in chat when it changes (also: .combat-brain why).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> terrainFlee = sgSurvival.add(new BoolSetting.Builder()
        .name("terrain-flee")
        .description("Pick flee destinations by distance gain, cover, line-of-sight breaks, hazards and dead ends instead of a straight line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shakePursuers = sgSurvival.add(new BoolSetting.Builder()
        .name("shake-pursuers")
        .description("Irregular re-plans and bearing changes while fleeing so a chaser can't pre-aim the path.")
        .defaultValue(true)
        .visible(terrainFlee::get)
        .build()
    );

    private final Setting<Boolean> pearlEscape = sgSurvival.add(new BoolSetting.Builder()
        .name("pearl-escape")
        .description("Throw an ender pearl to escape a lost fight (validated landing). Risky on anticheat servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> windChargeEscape = sgSurvival.add(new BoolSetting.Builder()
        .name("wind-charge-escape")
        .description("Wind-charge jump to escape. Risky on anticheat servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> elytraEscape = sgSurvival.add(new BoolSetting.Builder()
        .name("elytra-escape")
        .description("Take off with elytra + fireworks to escape (needs open sky). Risky on anticheat servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> burrowEscape = sgSurvival.add(new BoolSetting.Builder()
        .name("burrow-escape")
        .description("When cornered with no safe hole, place blocks around you (Surround). Risky on anticheat servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> waterClutch = sgSurvival.add(new BoolSetting.Builder()
        .name("water-clutch")
        .description("Place a water bucket to survive a lethal fall while escaping (skipped while NoFall is on).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> softLog = sgSurvival.add(new BoolSetting.Builder()
        .name("soft-log")
        .description("Last rung of the disengage ladder: disconnect when hopeless, cornered and critical after disengage/shelter/heal failed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> shieldAxeAware = sgSurvival.add(new BoolSetting.Builder()
        .name("shield-axe-aware")
        .description("Don't hold the shield up against a held axe (it gets disabled); space instead.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> learnOpponents = sgSurvival.add(new BoolSetting.Builder()
        .name("learn-opponents")
        .description("Remember opponents' skill and fight outcomes in meteor-client/combat-learning.json and bias future verdicts (clamped).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRegear = sgSurvival.add(new BoolSetting.Builder()
        .name("auto-regear")
        .description("After death or with a short kit, avoid player fights and restock from the stash container.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BlockPos> stashPos = sgSurvival.add(new BlockPosSetting.Builder()
        .name("stash-pos")
        .description("Stash container (chest/barrel/shulker) used by auto-regear. Set it with .combat-brain stash while looking at it.")
        .defaultValue(BlockPos.ZERO)
        .visible(autoRegear::get)
        .build()
    );

    private final Setting<Boolean> stashSet = sgSurvival.add(new BoolSetting.Builder()
        .name("stash-set")
        .description("Whether stash-pos has been configured.")
        .defaultValue(false)
        .visible(autoRegear::get)
        .build()
    );

    private final Setting<List<net.minecraft.world.item.Item>> regearExtras = sgSurvival.add(new ItemListSetting.Builder()
        .name("regear-extras")
        .description("Extra items to take one stack of when regearing (armor, weapon, totems, gaps and food are automatic).")
        .defaultValue(List.of())
        .visible(autoRegear::get)
        .build()
    );

    private final Setting<Integer> regearTotems = sgSurvival.add(new IntSetting.Builder()
        .name("regear-totems")
        .description("Totems to carry after a regear.")
        .defaultValue(4)
        .range(0, 36)
        .sliderRange(0, 12)
        .visible(autoRegear::get)
        .build()
    );

    private final Setting<Integer> regearClickDelay = sgSurvival.add(new IntSetting.Builder()
        .name("regear-click-delay")
        .description("Ticks between stash shift-clicks. 0 = auto: Baritone's shared GUI pacing (follows Baritone's look profile, cursor travel, open/close delays, lag aware).")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 20)
        .visible(autoRegear::get)
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
    /** Manual override from `.combat-brain mode <name>`; null = automatic mode loop. */
    private CombatMode forcedMode = null;
    private double lastFollowDistance = Double.NaN;
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

    // AllowBreak saved value
    private boolean savedAllowBreak = true;
    private int allowBreakCheckTimer = 0;

    // Emergency hole state
    private BlockPos emergencyHoleTarget = null;
    private int emergencyHoleTimer = 0;

    // Group awareness and tactical positioning
    private CombatGroupAwareness.GroupSnapshot lastGroupSnapshot = null;
    private CombatTacticalPositioner tacticalPositioner = null;
    private CombatPathManager combatPathManager = null;
    private int groupAwarenessTimer = 0;

    // KillAura settings save/restore state
    private boolean savedKillAura = false;
    private boolean savedKillAuraState = false;
    private boolean savedPauseBaritone = false;
    private boolean savedAutoSwitch = false;
    private boolean savedSwapBack = false;
    private boolean savedAutoShieldBreak = true;
    private KillAura.ShieldMode savedShieldMode = KillAura.ShieldMode.None;
    private int savedMaxTargets = 1;
    private double savedRange = 4.5;
    private KillAura.RotationMode savedRotate = KillAura.RotationMode.Always;
    private Set<EntityType<?>> savedEntities = null;

    // Creeper and Melee shield defense state
    private boolean shieldRaisedForCreeper = false;
    private boolean shieldRaisedForMelee = false;
    private int shieldDwellTicks = 0;
    private int shieldCooldownTicks = 0;

    // Last engaged target memory (LOS-gate exception after retreat, cave case)
    private UUID lastTargetUuid = null;
    private int lastTargetTick = -9999;

    // Target tracking statistics
    private int trackedEntityCount = 0;
    private int killedInCombat = 0;
    private int killsInARow = 0;
    private int lastKillTick = -999;

    // P1 survival state
    private final OpponentTracker opponentTracker = new OpponentTracker();
    private final EscapeController escapeController = new EscapeController();
    private final RegearController regearController = new RegearController();
    private final VerdictGate verdictGate = new VerdictGate(2, 10);
    private final DisengageLadder disengageLadder = new DisengageLadder();
    private MatchupAssessment.Result lastMatchup = null;
    private MatchupAssessment.Verdict lastAnnouncedVerdict = null;
    private OpponentModel fightOpponent = null;
    private boolean wasDead = false;
    private long respawnTick = -100000;
    private boolean corneredFight = false;

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
        shieldRaisedForCreeper = false;
        shieldRaisedForMelee = false;
        shieldDwellTicks = 0;
        shieldCooldownTicks = 0;
        followController = new CombatFollowController();
        terrainGrid = new CombatTerrainGrid();
        automator = new ModuleAutomator(this);
        tacticalPositioner = new CombatTacticalPositioner();
        if (combatPathManager == null) {
            combatPathManager = new CombatPathManager();
        }
        lastGroupSnapshot = null;
        groupAwarenessTimer = 0;
        stealthAntiDetectionEnabled = false;
        lastAttackedTimestamps.clear();
        stuckHealTicks = 0;
        emergencyHoleTarget = null;
        emergencyHoleTimer = 0;
        opponentTracker.clear();
        if (learnOpponents.get()) opponentTracker.load();
        escapeController.reset();
        verdictGate.reset();
        lastMatchup = null;
        lastAnnouncedVerdict = null;
        fightOpponent = null;
        corneredFight = false;
        regearController.resetAttempts();

        // Save and apply AllowBreak based on tool availability
        savedAllowBreak = BaritoneAPI.getSettings().allowBreak.value;
        allowBreakCheckTimer = 0;
        updateAllowBreak();

        // Immediate target acquisition on activation so pathing starts instantly
        if (mc.player != null && mc.level != null) {
            currentTarget = findBestTarget();
            if (currentTarget != null) {
                transitionTo(BrainState.ANALYZING);
            }
        }

        info("Combat Mode AI enabled");
    }

    @Override
    public void onDeactivate() {
        regearController.cancel();
        if (learnOpponents.get()) opponentTracker.save();
        if (combatPathManager != null) combatPathManager.stop();
        if (followController != null) followController.stop();
        if (tacticalPositioner != null) tacticalPositioner.stop();
        if (automator != null) automator.shutdown();
        if (stealthAntiDetectionEnabled) {
            disableModule(AntiDetectionModule.class);
            stealthAntiDetectionEnabled = false;
        }
        if (shieldRaisedForCreeper || shieldRaisedForMelee) {
            mc.options.keyUse.setDown(false);
            shieldRaisedForCreeper = false;
            shieldRaisedForMelee = false;
        }
        shieldDwellTicks = 0;
        shieldCooldownTicks = 0;
        disableAllManagedModules();
        state = BrainState.IDLE;
        combatMode = CombatMode.AGGRESSIVE;
        modeHoldTimer = 0;
        forcedMode = null;
        lastFollowDistance = Double.NaN;
        currentTarget = null;
        followController = null;
        tacticalPositioner = null;
        lastGroupSnapshot = null;
        automator = null;
        lastStateExitPass.clear();
        passCounter = 0;
        killsInARow = 0;
        trackedEntityCount = 0;
        emergencyHoleTarget = null;

        // Restore AllowBreak to saved value
        BaritoneAPI.getSettings().allowBreak.value = savedAllowBreak;
    }

    /**
     * Dedicated Creeper Defense: detects swelling/exploding creepers nearby (< 7m).
     * Automatically equips shield, rotates player to face the blast directly (180° shield arc),
     * and holds right-click to block 100% of explosion damage, or flees if no shield is available.
     */
    private void handleCreeperDefense() {
        if (mc.level == null || mc.player == null) return;
        if (!creeperDefense.get()) {
            if (shieldRaisedForCreeper) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForCreeper = false;
            }
            return;
        }

        Creeper imminentCreeper = null;
        double closestDist = Double.MAX_VALUE;

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity instanceof Creeper creeper && creeper.isAlive()) {
                double dist = creeper.distanceTo(mc.player);
                if (dist > 7.0) continue;

                boolean isSwelling = creeper.getSwellDir() > 0 || creeper.getSwelling(0.0f) > 0.05f || creeper.isIgnited();
                if (isSwelling || dist < 3.2) {
                    if (dist < closestDist) {
                        closestDist = dist;
                        imminentCreeper = creeper;
                    }
                }
            }
        }

        if (imminentCreeper == null) {
            if (shieldRaisedForCreeper) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForCreeper = false;
            }
            return;
        }

        // 1. Ensure a usable (non-broken) shield is equipped in offhand
        boolean hasShieldEquipped = (mc.player.getOffhandItem().getItem() == Items.SHIELD
            && mc.player.getOffhandItem().getMaxDamage() - mc.player.getOffhandItem().getDamageValue() > 0)
            || (mc.player.getMainHandItem().getItem() == Items.SHIELD
            && mc.player.getMainHandItem().getMaxDamage() - mc.player.getMainHandItem().getDamageValue() > 0);

        if (!hasShieldEquipped) {
            FindItemResult shield = InvUtils.find(itemStack -> itemStack.getItem() == Items.SHIELD
                && itemStack.getMaxDamage() - itemStack.getDamageValue() > 0);
            if (shield.found()) {
                InvUtils.move().from(shield.slot()).toOffhand();
                hasShieldEquipped = true;
            }
        }

        // 2. Smoothly rotate player to face the creeper directly so the shield block arc covers the blast
        double yaw = Rotations.getYaw(imminentCreeper);
        double pitch = Rotations.getPitch(imminentCreeper, Target.Body);
        // Through the camera arbiter (plan S15): smoothed by the active look profile, GCD-quantized
        CameraArbiter.claim(this, CameraArbiter.PRIORITY_DEFENSE, (float) yaw, (float) pitch, true);

        if (hasShieldEquipped) {
            // 3. Raise shield
            mc.options.keyUse.setDown(true);
            shieldRaisedForCreeper = true;

            // Step back slightly while keeping shield raised to avoid point-blank fuse acceleration
            if (closestDist < 3.0 && followController != null) {
                followController.follow(imminentCreeper, 3.5);
            }
        } else {
            // 4. No shield: sprint / flee away from the creeper immediately
            if (followController != null) {
                followController.flee(imminentCreeper, 7.0);
            }
        }
    }

    /**
     * Human-like Smooth Camera & Aim Tracking:
     * Eliminates robotic snapping, 180° snap-backs, and jerky camera flips.
     * Continuously and smoothly turns the player's view towards the active combat target.
     */
    private void handleSmoothCameraAim() {
        if (mc.player == null || mc.level == null) return;
        if (currentTarget == null || !currentTarget.isAlive()) return;

        // While digging, Baritone aims at the mined block — never fight its camera.
        if (isCurrentlyDigging()) return;

        // When traveling or pathing beyond close melee range (> 3.5m),
        // let Baritone's LookBehavior smoothly guide the camera along the path!
        if (currentTarget.distanceTo(mc.player) > 3.5) return;

        double targetYaw = Rotations.getYaw(currentTarget);
        double targetPitch = Rotations.getPitch(currentTarget, Target.Body);

        // Through the camera arbiter (plan S15): the active look profile (Legit-Smooth by default) times and
        // shapes the turn, GCD-quantized and interpolated per frame.
        CameraArbiter.claim(this, CameraArbiter.PRIORITY_COMBAT_AIM, (float) targetYaw, (float) targetPitch, true);
    }

    /**
     * Active Melee & PvP Shield Defense with Anti-Cheat Dwell Timing:
     * When fighting an active melee opponent (especially Players, Vindicators, Ravagers, Skeletons):
     * 1. If shield is available, auto-equip to offhand.
     * 2. When taking damage or under active melee pressure (< 4.0m):
     *    Raise the shield with a human-like dwell time (at least 10 ticks) so it properly blocks hits
     *    and does not spam use-item packets.
     * 3. When attack meter is fully charged (>= 0.9 scale) and dwell time has elapsed:
     *    Lower shield cleanly, counter-attack, and enforce a cooldown before raising again.
     */
    private void handleActiveMeleeShield() {
        if (mc.player == null || mc.level == null) return;
        if (!shieldSwap.get()) return;
        if (shieldRaisedForCreeper) return; // Creeper explosion defense takes priority

        if (shieldCooldownTicks > 0) {
            shieldCooldownTicks--;
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            if (shieldRaisedForMelee) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForMelee = false;
                shieldDwellTicks = 0;
            }
            return;
        }

        double dist = currentTarget.distanceTo(mc.player);
        // Only consider shield in close melee range (<= 2.5m). When closing distance,
        // NEVER hold shield because using an item slows movement by 80% and cancels sprinting!
        if (dist > 2.5) {
            if (shieldRaisedForMelee) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForMelee = false;
                shieldDwellTicks = 0;
            }
            return;
        }

        boolean hasShieldInOffhand = mc.player.getOffhandItem().is(Items.SHIELD)
            && mc.player.getOffhandItem().getMaxDamage() - mc.player.getOffhandItem().getDamageValue() > 0;
        if (!hasShieldInOffhand) {
            FindItemResult shield = InvUtils.find(itemStack -> itemStack.getItem() == Items.SHIELD
                && itemStack.getMaxDamage() - itemStack.getDamageValue() > 0);
            if (shield.found()) {
                InvUtils.move().from(shield.slot()).toOffhand();
                hasShieldInOffhand = true;
            }
        }

        if (!hasShieldInOffhand) {
            if (shieldRaisedForMelee) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForMelee = false;
                shieldDwellTicks = 0;
            }
            return;
        }

        float attackCooldown = mc.player.getAttackStrengthScale(0.5f);
        boolean isTakingDamage = mc.player.hurtTime > 0;
        boolean isTargetAttacking = currentTarget.isSwinging();

        // C51: never block a held axe in melee range - the shield gets disabled for 5 s; space instead
        if (shieldAxeAware.get() && WeaponKind.fromItemId(net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(currentTarget.getMainHandItem().getItem()).toString()) == WeaponKind.AXE) {
            if (shieldRaisedForMelee) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForMelee = false;
                shieldDwellTicks = 0;
            }
            return;
        }

        if (shieldRaisedForMelee) {
            shieldDwellTicks++;
            // Lower shield when attack is charged (>= 0.85) or no longer taking damage
            if (shieldDwellTicks >= 8 && attackCooldown >= 0.85f && !isTakingDamage) {
                mc.options.keyUse.setDown(false);
                shieldRaisedForMelee = false;
                shieldDwellTicks = 0;
                shieldCooldownTicks = 6; // 6-tick cooldown before shield can be re-raised
            }
        } else {
            // Only raise shield if not on cooldown AND taking damage or enemy is swinging during our recharge
            if (shieldCooldownTicks == 0 && (isTakingDamage || (isTargetAttacking && attackCooldown < 0.85f))) {
                mc.options.keyUse.setDown(true);
                shieldRaisedForMelee = true;
                shieldDwellTicks = 0;
            }
        }
    }

    /**
     * Dedicated Projectile Deflection: detects incoming Ghast fireballs,
     * Breeze wind charges, dragon fireballs, etc. heading towards the player,
     * locks onto them, and performs a melee swing to reflect them back to sender.
     */
    private void handleProjectileDeflection() {
        if (mc.level == null || mc.player == null) return;
        if (!projectileDeflect.get()) return;

        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!entity.isAlive()) continue;

            EntityType<?> et = entity.getType();
            boolean isDeflectable = et == EntityTypes.FIREBALL
                || et == EntityTypes.SMALL_FIREBALL
                || et == EntityTypes.DRAGON_FIREBALL
                || et == EntityTypes.WIND_CHARGE
                || et == EntityTypes.BREEZE_WIND_CHARGE;

            if (!isDeflectable) continue;

            double distSq = entity.distanceToSqr(mc.player);
            if (distSq <= 17.64) {
                var delta = entity.getDeltaMovement();
                var relPos = mc.player.position().subtract(entity.position());
                double dot = delta.x * relPos.x + delta.y * relPos.y + delta.z * relPos.z;
                if (dot > 0 || distSq <= 9.0) {
                    double yaw = Rotations.getYaw(entity);
                    double pitch = Rotations.getPitch(entity);
                    float deltaYaw = Mth.wrapDegrees((float) (yaw - mc.player.getYRot()));
                    float deltaPitch = (float) (pitch - mc.player.getXRot());
                    // Urgent: the swing below goes out this tick, so the arbiter applies this step right away
                    // (GCD-quantized) instead of smoothing it over the next ticks
                    CameraArbiter.claim(this, CameraArbiter.PRIORITY_URGENT,
                        mc.player.getYRot() + Mth.clamp(deltaYaw * 0.6f, -45.0f, 45.0f),
                        mc.player.getXRot() + Mth.clamp(deltaPitch * 0.6f, -30.0f, 30.0f), false);

                    if (mc.gameMode != null) {
                        mc.gameMode.attack(mc.player, entity);
                    }
                    PlayerUtils.punch(true);
                    break;
                }
            }
        }
    }

    /**
     * Evoker Fang Telegraph Dodging: detects when an Evoker is actively casting spell fangs,
     * and triggers a quick lateral strafe key press.
     */
    private void handleEvokerFangDodge() {
        if (mc.level == null || mc.player == null) return;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (entity.getType() == EntityTypes.EVOKER && entity instanceof LivingEntity evoker && evoker.isAlive()) {
                if (evoker.distanceToSqr(mc.player) <= 100.0) { // ⚡ Bolt: Use distanceToSqr to avoid Math.sqrt overhead
                    mc.options.keyLeft.setDown(true);
                    break;
                }
            }
        }
    }

    private int wTapTicks = 0;

    private void handleWTap() {
        if (!wTapSprintReset.get() || mc.player == null) return;
        if (wTapTicks > 0) {
            wTapTicks--;
            if (wTapTicks == 0) {
                // Re-request sprint via the key (vanilla handles ground/mid-air legality)
                if (mc.options != null) mc.options.keySprint.setDown(true);
            }
        }
    }

    @EventHandler
    private void onAttackEntity(AttackEntityEvent event) {
        if (wTapSprintReset.get() && mc.player != null && mc.player.isSprinting() && mc.player.onGround()) {
            if (mc.options != null) mc.options.keySprint.setDown(false);
            wTapTicks = 2;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.level == null || !(event.packet instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != 35) return;

        Entity entity = packet.getEntity(mc.level);
        if (!(entity instanceof Player player) || player == mc.player) return;
        opponentTracker.onPop(player, tickCounter);

        PopCounter popCounter = Modules.get().get(PopCounter.class);
        if (popCounter != null && !popCounter.isActive()) {
            popCounter.recordPop(player);
        }
    }

    private boolean isRestocking() {
        try {
            IRestockProcess restock = BaritoneAPI.getProvider().getPrimaryBaritone().getRestockProcess();
            return restock != null && restock.isActive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private int bhopJumpCooldown = 0;

    /**
     * Post-tick event: tick follow controller, execute creeper defense,
     * smooth camera aim, active melee shield, projectile deflection, evoker dodging, w-tap sprint reset, and situational BHop.
     */
    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null) return;

        // Real-server desync hold: while the server is teleporting us back
        // (rubberband), do NOT push movement keys into the rejected position
        // (that keeps the setback loop alive) and do NOT write raw rotation
        // (that keeps flagging the rotation check). Stand down until KillAura's
        // setback detector clears.
        if (Modules.get().get(KillAura.class).isDesyncHeld()) {
            if (followController != null) followController.releaseDirectKeys();
            return;
        }

        if (followController != null) followController.tick();
        escapeController.tick();

        handleCreeperDefense();
        handleSmoothCameraAim();
        handleActiveMeleeShield();
        handleProjectileDeflection();
        handleEvokerFangDodge();
        handleWTap();

        if (bhopJumpCooldown > 0) {
            bhopJumpCooldown--;
            if (bhopJumpCooldown == 2) {
                mc.options.keyJump.setDown(false);
            }
        }

        // Warden stealth check: suppress jumping/sprinting near unalerted Warden to avoid vibrations
        boolean wardenNear = false;
        if (wardenCounter.get() && mc.level != null) {
            for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
                // ⚡ Bolt: Use squared distance check to avoid JNI Math.sqrt overhead per entity
                if (entity.getType() == EntityTypes.WARDEN && entity.distanceToSqr(mc.player) <= 256.0) {
                    wardenNear = true;
                    break;
                }
            }
        }

        // Situational BHop: uses legitimate keyJump keypresses to prevent server anticheat rubberbanding
        if (canSituationalBHop(wardenNear)) {
            boolean isSprint = mc.player.isSprinting() || BaritoneAPI.getSettings().allowSprint.value;
            if (isSprint && mc.player.onGround()) {
                mc.options.keyJump.setDown(true);
                bhopJumpCooldown = 4; // 4-tick jump cadence to prevent spam-jumping and runaway momentum
            }
        }
    }

    /**
     * Checks if Baritone is currently executing a long, straight, flat path segment.
     * If the path has an upcoming turn, elevation change, or non-traverse movement within the next 4 nodes,
     * or is within 5 nodes of the destination, returns false to prevent overshooting the path.
     */
    private boolean isBaritonePathStraightAndFlat(baritone.api.IBaritone baritone) {
        if (baritone == null || !baritone.getPathingBehavior().isPathing()) return true;

        var current = baritone.getPathingBehavior().getCurrent();
        if (current == null || current.getPath() == null) return false;

        int posIdx = current.getPosition();
        var positions = current.getPath().positions();
        var movements = current.getPath().movements();

        // If near the end of the path segment (< 5 nodes left), suppress bhop so we stop cleanly on the goal
        if (positions.size() - posIdx < 5) return false;

        // Check the current movement and next 3 movements
        for (int i = posIdx; i < Math.min(posIdx + 4, movements.size()); i++) {
            var mov = movements.get(i);
            String movName = mov.getClass().getSimpleName();
            // Only purely flat straight traversal is safe for high-speed bhopping
            if (!movName.equals("MovementTraverse")) {
                return false;
            }
        }

        // Verify that the next 4 positions are in a straight collinear line on the same Y-axis
        if (posIdx + 3 < positions.size()) {
            var p0 = positions.get(posIdx);
            var p1 = positions.get(posIdx + 1);
            var p2 = positions.get(posIdx + 2);
            var p3 = positions.get(posIdx + 3);

            // Same Y level
            if (p0.y != p1.y || p1.y != p2.y || p2.y != p3.y) return false;

            // Same Direction vector (collinear)
            int dx0 = p1.x - p0.x;
            int dz0 = p1.z - p0.z;
            int dx1 = p2.x - p1.x;
            int dz1 = p2.z - p1.z;
            int dx2 = p3.x - p2.x;
            int dz2 = p3.z - p2.z;

            if (dx0 != dx1 || dx1 != dx2 || dz0 != dz1 || dz1 != dz2) {
                return false; // Turn ahead in the path! Suppress bhop so we turn cleanly
            }
        }

        return true;
    }

    /**
     * Situational BHop validator:
     * Only allows bunny hopping when moving forward in straight corridors/tunnels (2x1, 3x1, 2x2, 3x3)
     * or charging straight towards a distant enemy across flat ground without upcoming turns.
     * Strictly suppresses BHop when:
     * 1. On cadence cooldown (prevents spam jumping).
     * 2. Baritone path has an upcoming turn, step, or is nearing the destination (prevents path overshooting).
     * 3. Actively mining, digging down, or strip mining (prevents jumping over holes).
     * 4. Fighting in close melee range (< 5.5 blocks) to maintain attack precision.
     * 5. Climbing steep mountains/slopes or near parkour gaps/cliff edges (> 3 blocks).
     * 6. Near unalerted Warden (vibration avoidance).
     */
    private boolean canSituationalBHop(boolean wardenNear) {
        if (mc.player == null || mc.level == null) return false;
        if (!bhop.get()) return false;
        if (wardenNear) return false;
        if (bhopJumpCooldown > 0) return false;
        if (!mc.player.onGround() || mc.player.isInWater() || mc.player.isCrouching()) return false;

        // 1. SUPPRESSION: Mining / Digging / Breaking blocks & Parkour Movements
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return false;
        if (Modules.get() != null && Modules.get().isActive(meteordevelopment.meteorclient.systems.modules.movement.Parkour.class)) {
            return false;
        }

        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) {
            if (baritone.getMineProcess().isActive() || baritone.getBuilderProcess().isActive()) {
                return false;
            }
            var currentMovement = baritone.getPathingBehavior().getCurrent();
            if (currentMovement != null) {
                String movementName = currentMovement.getClass().getSimpleName();
                if (movementName.contains("Parkour")
                    || movementName.contains("Downward")
                    || movementName.contains("Pillar")
                    || movementName.contains("Descend")
                    || movementName.contains("Fall")
                    || movementName.contains("Diagonal")
                    || movementName.contains("Ascend")) {
                    return false;
                }
            }

            // 2. Baritone Path Straightness: if there is an upcoming turn or goal in the next 4 nodes, suppress BHop!
            if (!isBaritonePathStraightAndFlat(baritone)) {
                return false;
            }
        }

        // 3. Must be moving forward with forward momentum
        boolean isMovingForward = mc.player.zza > 0.1 || (mc.player.getDeltaMovement().horizontalDistanceSqr() > 0.05);
        if (!isMovingForward) return false;
        if (mc.player.horizontalCollision) return false;

        // 4. SUPPRESSION: Close-Quarters Melee Combat (< 5.5m) to maintain stability and not overshoot target
        if (currentTarget != null && currentTarget.isAlive()) {
            double dist = mc.player.distanceTo(currentTarget);
            if (dist <= 5.5) {
                return false;
            }
        }

        // 5. Terrain & Safety Analysis Ahead
        BlockPos feet = mc.player.blockPosition();
        float yaw = mc.player.getYRot();
        double rad = Math.toRadians(yaw);
        int dx = -Mth.floor(Mth.sin((float) rad) + 0.5f);
        int dz = Mth.floor(Mth.cos((float) rad) + 0.5f);

        BlockPos inFront1 = feet.offset(dx, 0, dz);
        BlockPos inFront2 = feet.offset(dx * 2, 0, dz * 2);
        BlockPos inFront3 = feet.offset(dx * 3, 0, dz * 3);

        // Check for parkour gap ahead (1-to-3 block gap with solid landing platform across).
        boolean inFront1Empty = !mc.level.getBlockState(inFront1.below()).isSolidRender()
            && !mc.level.getBlockState(inFront1).isSolidRender();
        boolean inFront2Solid = mc.level.getBlockState(inFront2.below()).isSolidRender()
            || mc.level.getBlockState(inFront2).isSolidRender();
        boolean inFront3Solid = mc.level.getBlockState(inFront3.below()).isSolidRender()
            || mc.level.getBlockState(inFront3).isSolidRender();
        if (inFront1Empty && (inFront2Solid || inFront3Solid)) {
            return false;
        }

        // Check for cliff / drop-off hazard (> 3 blocks drop in front)
        boolean hasFloor1 = mc.level.getBlockState(inFront1.below()).isSolidRender()
            || mc.level.getBlockState(inFront1).isSolidRender();
        boolean hasFloor2 = mc.level.getBlockState(inFront2.below()).isSolidRender()
            || mc.level.getBlockState(inFront2.below(2)).isSolidRender();

        if (!hasFloor1 || !hasFloor2) {
            boolean isDropOff = !mc.level.getBlockState(inFront1.below()).isSolidRender()
                && !mc.level.getBlockState(inFront1.below(2)).isSolidRender()
                && !mc.level.getBlockState(inFront1.below(3)).isSolidRender();
            if (isDropOff) return false;
        }

        // Check for steep mountain / cliff ascend (2-block steep climb ahead)
        boolean isSteepClimb = mc.level.getBlockState(inFront1).isSolidRender()
            && mc.level.getBlockState(inFront1.above()).isSolidRender();
        if (isSteepClimb) return false;

        // 6. ALLOW CONDITIONS:
        // A) Tunnel Detection (2x1, 3x1, 2x2, 3x3 corridors):
        BlockPos left = feet.offset(-dz, 0, dx);
        BlockPos right = feet.offset(dz, 0, -dx);
        boolean hasSideWalls = mc.level.getBlockState(left).isSolidRender()
            || mc.level.getBlockState(right).isSolidRender();
        boolean hasCeiling = mc.level.getBlockState(feet.above(2)).isSolidRender()
            || mc.level.getBlockState(feet.above(3)).isSolidRender();
        boolean isTunnel = (hasSideWalls || hasCeiling) && (hasFloor1 && hasFloor2);
        if (isTunnel) return true;

        // B) Straight Travel to distant enemy (> 5.5m) on fairly level ground:
        if (currentTarget != null && currentTarget.isAlive()) {
            double dist = mc.player.distanceTo(currentTarget);
            double yDiff = Math.abs(currentTarget.getY() - mc.player.getY());
            if (dist > 5.5 && yDiff <= 1.5 && hasFloor1 && hasFloor2) {
                return true;
            }
        }

        // C) Flat open ground travel:
        boolean isFlatAhead = mc.level.getBlockState(inFront1.below()).isSolidRender()
            && mc.level.getBlockState(inFront2.below()).isSolidRender()
            && !mc.level.getBlockState(inFront1).isSolidRender()
            && !mc.level.getBlockState(inFront2).isSolidRender();

        return isFlatAhead;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        trackDeathAndRespawn();
        if (regearController.active()) {
            regearController.tick(regearClickDelay.get(), regearExtras.get(), new RegearPlan.Targets(regearTotems.get(), 8, 32));
            if (regearController.phase() == RegearController.Phase.DONE) {
                info("Regear complete.");
                if (autoArmor.get()) enableModule(AutoArmor.class);
            } else if (regearController.phase() == RegearController.Phase.FAILED) {
                warning("Regear failed: " + regearController.failure());
            }
        }

        // Run state machine every 2 ticks to reduce CPU
        if (tickCounter % 2 != 0) return;

        passCounter++;

        // Check health / threat
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int totems = countTotems();
        double threat = computeThreatLevel();

        // Pre-warm the time-sliced emergency-hole search while health is getting low (plan S86), so a result is
        // ready the moment health drops below the emergency threshold
        if (health >= 6.0f && health < 10.0f && state != BrainState.EMERGENCY_HOLE) {
            pollSafeHole(SAFE_HOLE_RADIUS);
        }

        // Tactical combat-mode evaluation
        if (forcedMode != null) {
            if (combatMode != forcedMode) {
                combatMode = forcedMode;
                modeHoldTimer = 0;
            } else {
                modeHoldTimer += 2;
            }
        } else if (modeSwitch.get()) {
            CombatMode proposed = CombatMode.evaluateCombatMode(
                threat,
                engageThreshold.get(),
                fleeThreshold.get(),
                health,
                totems,
                currentTarget,
                terrainGrid,
                InvUtils.find(Items.END_CRYSTAL).found(),
                InvUtils.find(Items.RESPAWN_ANCHOR).found(),
                crystal.get()
            );
            CombatMode prevMode = combatMode;
            combatMode = CombatMode.holdMode(combatMode, proposed, modeHoldTimer, modeHysteresisTicks.get());
            if (combatMode != prevMode) {
                modeHoldTimer = 0;
            } else {
                modeHoldTimer += 2;
            }

        }

        // STEALTH mode wiring: enable AntiDetectionModule while hidden so
        // rotation jitter + sneak cycling mask bot-like behavior. Disable
        // and release when leaving STEALTH. Runs for both auto and forced modes.
        if (combatMode == CombatMode.STEALTH) {
            if (!stealthAntiDetectionEnabled) {
                enableModule(AntiDetectionModule.class);
                stealthAntiDetectionEnabled = true;
            }
        } else if (stealthAntiDetectionEnabled) {
            disableModule(AntiDetectionModule.class);
            stealthAntiDetectionEnabled = false;
        }

        // Emergency check
        if (health <= 4.0f && totems == 0 && threat > 0.8f) {
            transitionTo(BrainState.EMERGENCY_LOG);
            return;
        }

        // Target kill tracking
        if (currentTarget != null && !currentTarget.isAlive()) {
            if (fightOpponent != null && learnOpponents.get()) opponentTracker.recordOutcome(fightOpponent, OpponentMemory.Outcome.WIN, true);
            fightOpponent = null;
            killedInCombat++;
            killsInARow++;
            lastKillTick = tickCounter;
            currentTarget = null;
        }

        // P1 opponent models + pursuit sampling (C11-C20, C42)
        if (currentTarget != null && currentTarget.isAlive()) {
            OpponentModel model = opponentTracker.track(currentTarget, tickCounter);
            if (state == BrainState.ENGAGING) fightOpponent = model;
            escapeController.samplePursuit(currentTarget, tickCounter);
        }
        opponentTracker.endTick(tickCounter);
        maybeStartRegear();

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

                if (verdictFlee.get() && viabilityCheck.get() && !isInvincible()) {
                    // P1: the verdict decides (decision 1: flee on LOSE/HOPELESS, fight only if cornered). A fresh
                    // target starts a fresh hysteresis window; a re-entry cooldown never forces an engage.
                    verdictGate.reset();
                    escapeController.reset();
                    MatchupAssessment.Result r = evaluateMatchup(currentTarget);
                    if (r.shouldEngage() && mayEngage(currentTarget, r)) {
                        corneredFight = r.mustFightBecauseCornered();
                        transitionTo(BrainState.ENGAGING);
                    } else {
                        enterFlee(r.verdict());
                    }
                    break;
                }
                boolean isUndergeared = viabilityCheck.get() && CombatTargetAnalyzer.undergeared(currentTarget, undergearRatio.get());
                boolean canWin = isInvincible() || !viabilityCheck.get() || (assessViability(currentTarget) && !isUndergeared);
                // Legacy (verdict-flee off): dwell gate / reentry check engages instead of re-entering RETREATING
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

                // Check if threat got too high and health is low (prevents state-flapping back and forth during active combat)
                // Shield-aware: without a usable shield, retreat MUCH sooner — no shield = no trading blows.
                float retreatHealthThreshold = hasUsableShield() ? 7.0f : 10.0f;
                if (!isInvincible() && threat >= fleeThreshold.get() && health < retreatHealthThreshold) {
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

                // P1: re-check the verdict while fighting (hysteresis in VerdictGate); worse -> disengage
                if (verdictFlee.get() && viabilityCheck.get() && !isInvincible() && passCounter % 2 == 0) {
                    // a cornered fight keeps looking for an opening to run
                    if (corneredFight && passCounter % 10 == 0) {
                        escapeController.plan(currentTarget, lastGroupSnapshot != null ? lastGroupSnapshot.targets() : List.of(),
                            tickCounter, shakePursuers.get(), true);
                    }
                    MatchupAssessment.Result r = evaluateMatchup(currentTarget);
                    corneredFight = r.mustFightBecauseCornered();
                    if (!r.shouldEngage() || !mayEngage(currentTarget, r)) {
                        enterFlee(r.verdict());
                        break;
                    }
                }

                // Maintain modules
                if (autoModules.get()) enableCombatModules();
                doEngageTick();
                break;

            case RETREATING:
                if (verdictFlee.get() && !isInvincible() && fleeExitCheck()) break;
                if (!verdictFlee.get() && (isInvincible() || threat < engageThreshold.get())) {
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

                // Critically low health: try emergency hole first, then heal
                if (health < 6.0f && !isReentryCooldownActive(BrainState.EMERGENCY_HOLE)) {
                    // Only enter EMERGENCY_HOLE if a safe hole actually exists nearby. The search is time-sliced
                    // (and pre-warmed below 10 HP), so wait the few ticks it may still need.
                    if (!pollSafeHole(SAFE_HOLE_RADIUS)) break;
                    BlockPos hole = safeHoleResult();
                    if (hole != null) {
                        emergencyHoleTarget = hole;
                        emergencyHoleTimer = 0;
                        transitionTo(BrainState.EMERGENCY_HOLE);
                        break;
                    }
                }

                // Try to heal
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
                // P1: while still being chased keep running/healing (no exit on a momentary threat dip)
                boolean stillChased = verdictFlee.get() && health < 14.0f && !isInvincible() && currentTarget != null
                    && isTargetValid(currentTarget) && !escapeController.pursuit().complete(verdictGate.current(), safeFleeDistance());
                if (!stillChased && (health >= 14.0f || threat < engageThreshold.get() || verdictFlee.get())) {
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
                if (verdictFlee.get() && !isInvincible()) {
                    if (fleeExitCheck()) break;
                } else if (threat < fleeThreshold.get() && health >= 10.0f) {
                    transitionTo(BrainState.SCANNING);
                    break;
                }

                doFleeTick();
                break;

            case EMERGENCY_HOLE:
                if (currentTarget == null || !isTargetValid(currentTarget)) {
                    // Target gone, exit to scanning
                    emergencyHoleTarget = null;
                    transitionTo(BrainState.SCANNING);
                    break;
                }
                // Exit when threat drops
                if (isInvincible() || threat < engageThreshold.get()) {
                    emergencyHoleTarget = null;
                    transitionTo(BrainState.SCANNING);
                    break;
                }
                doEmergencyHoleTick();
                break;

            case EMERGENCY_LOG:
                doEmergencyLog();
                break;
        }

        // Every 20 ticks, re-evaluate AllowBreak based on tool inventory
        allowBreakCheckTimer += 2;
        if (allowBreakCheckTimer >= 20) {
            allowBreakCheckTimer = 0;
            updateAllowBreak();
        }

        // Stuck detection: if same state > 200 ticks with no progress, reset
        boolean activelyFleeing = verdictFlee.get() && (state == BrainState.RETREATING || state == BrainState.FLEEING || state == BrainState.HEALING)
            && currentTarget != null && isTargetValid(currentTarget);
        if (stateTimer > 200 && !activelyFleeing) {
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

    // --- P1 survival helpers ---

    /** Matchup with the opponent-model corrections and learned bias (C11-C20, decision 4). */
    private MatchupAssessment.Result evaluateMatchup(LivingEntity target) {
        OpponentModel model = opponentTracker.get(target);
        OpponentModel.Corrections corr = model == null ? null
            : model.corrections(tickCounter, learnOpponents.get() ? opponentTracker.learnedBias(model) : 1.0);
        boolean los = mc.player != null && mc.player.hasLineOfSight(target);
        MatchupAssessment.Result r = CombatTargetAnalyzer.assessMatchup(target, aggression.get(), escapeController.cornered(), los, corr, model);
        MatchupAssessment.Verdict gated = verdictGate.offer(r.verdict());
        if (gated != null && gated != r.verdict() && !r.mustFightBecauseCornered()) {
            // hysteresis holds the previous verdict for now
            r = new MatchupAssessment.Result(gated, r.confidence(), r.myDps(), r.theirDps(), r.myTtk(), r.theirTtk(),
                r.killRatio(), r.matchupThreat(), r.mustFightBecauseCornered(), r.reason() + " (held: hysteresis)");
        }
        lastMatchup = r;
        if (announceVerdict.get() && r.verdict() != lastAnnouncedVerdict) {
            info(target.getName().getString() + ": " + r.explain());
        }
        lastAnnouncedVerdict = r.verdict();
        return r;
    }

    /** Respawn grace / ungeared kit blocks player fights unless cornered (C60). */
    private boolean mayEngage(LivingEntity target, MatchupAssessment.Result r) {
        if (!(target instanceof Player)) return true;
        return RespawnPolicy.mayEngagePlayer(respawnMode(), r.mustFightBecauseCornered());
    }

    /** Minimum kit to take player fights / not trigger a regear: some armor and a weapon (C60). */
    private static final RespawnPolicy.Requirement KIT_MIN = new RespawnPolicy.Requirement(2, true, 0, 0, 0);

    private RespawnPolicy.Mode respawnMode() {
        if (mc.player == null) return RespawnPolicy.Mode.NORMAL;
        return RespawnPolicy.mode(tickCounter - respawnTick, currentKit(), KIT_MIN,
            stashSet.get(), autoRegear.get());
    }

    private RespawnPolicy.Kit currentKit() {
        if (mc.player == null) return new RespawnPolicy.Kit(0, false, 0, 0, 0);
        int armor = 0;
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
            net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            if (!mc.player.getItemBySlot(slot).isEmpty()) armor++;
        }
        boolean weapon = InvUtils.find(st -> {
            if (st.isEmpty()) return false;
            WeaponKind k = WeaponKind.fromItemId(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
            return k == WeaponKind.SWORD || k == WeaponKind.AXE || k == WeaponKind.MACE;
        }).found();
        int food = InvUtils.find(st -> !st.isEmpty() && st.has(net.minecraft.core.component.DataComponents.FOOD)).count();
        return new RespawnPolicy.Kit(armor, weapon, countTotems(), InvUtils.find(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE).count(), food);
    }

    private void enterFlee(MatchupAssessment.Verdict verdict) {
        BrainState want = verdict == MatchupAssessment.Verdict.HOPELESS ? BrainState.FLEEING : BrainState.RETREATING;
        if (isReentryCooldownActive(want)) {
            want = want == BrainState.FLEEING ? BrainState.RETREATING : BrainState.FLEEING;
        }
        if (state != BrainState.RETREATING && state != BrainState.FLEEING && state != BrainState.HEALING) {
            escapeController.reset();
            if (fightOpponent != null && learnOpponents.get() && state == BrainState.ENGAGING) {
                opponentTracker.recordOutcome(fightOpponent, OpponentMemory.Outcome.ESCAPED, true);
            }
        }
        transitionTo(want);
    }

    private double safeFleeDistance() {
        double range = 3.0;
        double speed = 0.28;
        if (currentTarget != null) {
            OpponentModel m = opponentTracker.get(currentTarget);
            if (m != null) {
                range = m.player ? (m.gear != null && m.gear.mainHand().ranged() ? 16 : 3) : m.mob.threatRange();
                if (!m.player) range = Math.max(range, m.mob.safeDistance() - 6.0);
                speed = Math.max(speed, m.motion.horizontalSpeed());
                return PursuitTracker.safeDistance(fleeDistance.get(), range,
                    PingModel.reachMargin(opponentTracker.myPing(), m.ping, speed));
            }
        }
        return PursuitTracker.safeDistance(fleeDistance.get(), range, 0);
    }

    /**
     * Verdict-driven exit from RETREATING/FLEEING (C42): back to the fight when the verdict improved (or cornered),
     * done when safe distance + broken LOS + pursuer not gaining. Returns true when it transitioned.
     */
    private boolean fleeExitCheck() {
        if (currentTarget == null || !isTargetValid(currentTarget)) {
            transitionTo(BrainState.IDLE);
            return true;
        }
        MatchupAssessment.Result r = passCounter % 2 == 0 || lastMatchup == null ? evaluateMatchup(currentTarget) : lastMatchup;
        // don't bounce back into a fight we just left for low health (unless cornered)
        boolean healthy = mc.player != null && mc.player.getHealth() + mc.player.getAbsorptionAmount() >= (hasUsableShield() ? 7.0f : 10.0f);
        if (r.shouldEngage() && mayEngage(currentTarget, r) && (healthy || r.mustFightBecauseCornered())) {
            corneredFight = r.mustFightBecauseCornered();
            if (corneredFight) info("Cornered: fighting back.");
            transitionTo(BrainState.ENGAGING);
            return true;
        }
        if (escapeController.pursuit().complete(r.verdict(), safeFleeDistance())) {
            transitionTo(BrainState.IDLE);
            return true;
        }
        return false;
    }

    private EscapeSelector.Flags escapeFlags() {
        return new EscapeSelector.Flags(pearlEscape.get(), windChargeEscape.get(), elytraEscape.get(), burrowEscape.get(), softLog.get());
    }

    /** One flee step: optional escape methods (opt-in), else terrain-scored running (C41-C50). */
    private void fleeStep(double distance) {
        if (currentTarget == null || mc.player == null) return;
        MatchupAssessment.Verdict verdict = lastMatchup != null ? lastMatchup.verdict() : MatchupAssessment.Verdict.LOSE;
        if (verdictFlee.get()) {
            float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            boolean holeKnown = (escapeController.cornered() || hp < 10) && pollSafeHole(SAFE_HOLE_RADIUS) && safeHoleResult() != null;
            boolean clutchOk = waterClutch.get() && !Modules.get().isActive(meteordevelopment.meteorclient.systems.modules.movement.NoFall.class);
            EscapeSelector.Choice choice = EscapeSelector.choose(verdict, escapeController.situation(currentTarget, holeKnown, clutchOk), escapeFlags());
            DisengageLadder.Rung rung = disengageLadder.update(tickCounter, verdict, false, holeKnown && escapeController.cornered(), hp, softLog.get());
            switch (choice.method()) {
                case CLUTCH -> {
                    escapeController.clutch();
                    return;
                }
                case PEARL -> {
                    if (escapeController.pearl(currentTarget)) info("Escape: pearl.");
                }
                case ELYTRA -> {
                    if (escapeController.elytra(currentTarget)) return;
                }
                case WIND_CHARGE -> escapeController.windCharge();
                case HOLE -> {
                    BlockPos hole = safeHoleResult();
                    if (hole != null && !isReentryCooldownActive(BrainState.EMERGENCY_HOLE)) {
                        emergencyHoleTarget = hole;
                        emergencyHoleTimer = 0;
                        transitionTo(BrainState.EMERGENCY_HOLE);
                        return;
                    }
                }
                case BURROW -> {
                    if (hasPlaceableBlocksInHotbar(4)) enableModule(Surround.class);
                }
                case LOG -> {
                    if (rung == DisengageLadder.Rung.LOG && softLog.get() && mc.getConnection() != null) {
                        mc.getConnection().getConnection().disconnect(Component.literal("CombatBrain: soft-log (hopeless, cornered)."));
                        return;
                    }
                }
                case FIGHT_CORNERED -> {
                    corneredFight = true;
                    transitionTo(BrainState.ENGAGING);
                    return;
                }
                default -> {
                }
            }
            if (terrainFlee.get() && combatPathManager != null) {
                List<LivingEntity> threats = lastGroupSnapshot != null ? lastGroupSnapshot.targets() : List.of();
                BlockPos dest = escapeController.plan(currentTarget, threats, tickCounter, shakePursuers.get(), true);
                if (dest != null) {
                    combatPathManager.startFleeTo(currentTarget, dest);
                    if (HungerPlanner.canSprint(mc.player.getFoodData().getFoodLevel()) && mc.options != null) {
                        mc.options.keySprint.setDown(true);
                    }
                    return;
                }
            }
        }
        if (combatPathManager != null) {
            combatPathManager.startFlee(currentTarget, distance, lastGroupSnapshot);
        } else if (followController != null) {
            followController.flee(currentTarget, distance);
        }
    }

    /** Death -> loss outcome; respawn -> grace + regear eligibility (C60). */
    private void trackDeathAndRespawn() {
        if (mc.player == null) return;
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            if (fightOpponent != null && learnOpponents.get()) opponentTracker.recordOutcome(fightOpponent, OpponentMemory.Outcome.LOSS, true);
            fightOpponent = null;
            regearController.cancel();
        }
        if (!dead && wasDead) {
            respawnTick = tickCounter;
            regearController.resetAttempts();
            escapeController.reset();
            verdictGate.reset();
        }
        wasDead = dead;
    }

    private void maybeStartRegear() {
        if (!autoRegear.get() || !stashSet.get() || regearController.active() || passCounter % 10 != 0) return;
        if (regearController.attempts() >= 3) return;
        if (state != BrainState.IDLE && state != BrainState.SCANNING) return;
        if (respawnMode() != RespawnPolicy.Mode.REGEAR) return;
        info("Kit is short: regearing from the stash at " + stashPos.get().toShortString() + ".");
        regearController.start(stashPos.get());
    }

    /** {@code .combat-brain why}: last verdict and its reasons. */
    public String explainVerdict() {
        StringBuilder sb = new StringBuilder();
        sb.append("State ").append(state);
        if (lastMatchup != null) sb.append(" | ").append(lastMatchup.explain());
        else sb.append(" | no matchup evaluated yet");
        if (escapeController.lastWhy() != null) sb.append(" | flee: ").append(escapeController.lastWhy());
        if (escapeController.cornered()) sb.append(" | CORNERED");
        sb.append(" | kit: ").append(respawnMode());
        if (regearController.phase() != RegearController.Phase.IDLE) sb.append(" | regear: ").append(regearController.phase());
        return sb.toString();
    }

    /** {@code .combat-brain stash}: use the block under the crosshair as the regear stash. */
    public boolean setStashHere() {
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) || mc.level == null) return false;
        if (mc.level.getBlockEntity(bhr.getBlockPos()) == null) return false;
        stashPos.set(bhr.getBlockPos());
        stashSet.set(true);
        return true;
    }

    /** {@code .combat-brain regear}: start a regear run now. */
    public boolean regearNow() {
        if (!stashSet.get()) return false;
        regearController.resetAttempts();
        regearController.start(stashPos.get());
        return true;
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
            case IDLE:
            case SCANNING:
                if (combatPathManager != null) combatPathManager.stop();
                break;
            case ENGAGING:
                if (combatPathManager != null) combatPathManager.releaseControl();
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
            case EMERGENCY_HOLE:
                info("Emergency: pathing to safe hole");
                if (autoModules.get()) disableCombatModules(); // Pause attacking while retreating to hole
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
            case EMERGENCY_HOLE:
                // Reset the hole timer and target when leaving emergency hole state
                emergencyHoleTimer = 0;
                // Don't reset emergencyHoleTarget here - doEmergencyHoleTick does that
                break;
            default:
                break;
        }
    }

    // --- Target selection & multi-target scoring ---

    /**
     * True while the player is actively mining/breaking blocks — either baritone's
     * mine or builder process, or the client's own destroy-in-progress state.
     * Used to scope the LOS acquisition gate: while digging we must not acquire
     * mobs behind cave walls (it interrupts the dig and swings at stone), but when
     * idle the brain should hunt targets through walls via baritone pathing.
     */
    private boolean isCurrentlyDigging() {
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return true;
        var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        return baritone != null && (baritone.getMineProcess().isActive() || baritone.getBuilderProcess().isActive());
    }

    /**
     * True if the player has a usable shield: equipped in the offhand (or main
     * hand) with durability left, or anywhere in the inventory (shield-swap will
     * move it). A broken (0-durability) shield does NOT count — the brain must
     * switch to hit-and-run tactics instead of trading hits shieldless.
     */
    private boolean hasUsableShield() {
        if (mc.player == null) return false;
        ItemStack off = mc.player.getOffhandItem();
        if (off.getItem() == Items.SHIELD && off.getMaxDamage() - off.getDamageValue() > 0) return true;
        ItemStack main = mc.player.getMainHandItem();
        if (main.getItem() == Items.SHIELD && main.getMaxDamage() - main.getDamageValue() > 0) return true;
        return InvUtils.find(itemStack ->
            itemStack.getItem() == Items.SHIELD && itemStack.getMaxDamage() - itemStack.getDamageValue() > 0
        ).found();
    }

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

            // Wall awareness: while actively digging/mining, only acquire targets we can
            // actually see — engaging a mob through a cave wall stops the dig and ends in
            // swinging at stone. When NOT digging (idle/hunting), acquire by range even
            // through walls: baritone paths around them, so the brain hunts instead of
            // standing still. Exception: the target we were JUST fighting stays
            // re-acquirable through walls for a grace window (~5s) — the LOS gate exists
            // to protect digging from NEW targets, not to strand the brain after a
            // retreat around a cave corner. (A target already acquired stays valid behind
            // cover mid-chase -- see isTargetValid.)
            if (isCurrentlyDigging() && !PlayerUtils.canSeeEntity(entity)) {
                boolean isRecentCombatTarget = entity.getUUID().equals(lastTargetUuid)
                    && tickCounter - lastTargetTick < 300;
                if (!isRecentCombatTarget) return false;
            }

            Set<EntityType<?>> types = entities.get();
            if (types.isEmpty()) {
                types = Set.of(
                    EntityTypes.PLAYER,
                    EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER, EntityTypes.ENDERMAN,
                    EntityTypes.PIGLIN, EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.DROWNED, EntityTypes.HUSK, EntityTypes.STRAY,
                    EntityTypes.WITHER_SKELETON, EntityTypes.SLIME, EntityTypes.MAGMA_CUBE, EntityTypes.BLAZE, EntityTypes.GHAST,
                    EntityTypes.WITCH, EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.RAVAGER, EntityTypes.EVOKER,
                    EntityTypes.WARDEN, EntityTypes.WITHER, EntityTypes.ENDER_DRAGON, EntityTypes.BREEZE, EntityTypes.BOGGED
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
     * Incorporates a Y-level proximity bonus: targets at the same level as the player
     * (within ±2 blocks) are heavily preferred to avoid Baritone digging underground.
     */
    private double scoreTarget(LivingEntity target) {
        CombatTargetAnalyzer.TargetAnalysis analysis = CombatTargetAnalyzer.analyze(target);
        if (analysis == null) return 0.0;
        double baseScore = CombatTargetAnalyzer.targetScore(
            analysis,
            targetDistanceWeight.get(),
            targetHealthWeight.get(),
            targetDefenseWeight.get(),
            targetWeaponWeight.get(),
            acquireRange.get()
        );

        // Y-level proximity bonus: strongly prefer targets at the same height.
        // This prevents the brain from Baritone-digging down to a zombie 3 floors
        // below while a player at eye level is nearby.
        double yDiff = Math.abs(target.getY() - mc.player.getY());
        double sameLevelBonus;
        if (yDiff <= 2.0) {
            sameLevelBonus = targetSameLevelWeight.get(); // Full bonus within 2 blocks
        } else if (yDiff <= 6.0) {
            // Linear decay from 2→6 blocks vertical
            sameLevelBonus = targetSameLevelWeight.get() * (1.0 - (yDiff - 2.0) / 4.0);
        } else {
            // Penalty beyond 6 blocks vertical: 0.05 per extra block
            sameLevelBonus = -0.05 * (yDiff - 6.0);
        }

        // Mob-specific tactical priorities:
        // Heavily prioritize Evoker (+2.5) to stop it from spawning infinite Vexes.
        // Lower priority on Vexes (-0.5) when higher-value targets exist.
        double mobPriorityBonus = 0.0;
        EntityType<?> type = target.getType();
        if (type == EntityTypes.EVOKER) {
            mobPriorityBonus += 2.5;
        } else if (type == EntityTypes.VEX) {
            mobPriorityBonus -= 0.5;
        } else if (type == EntityTypes.GHAST || type == EntityTypes.BLAZE) {
            mobPriorityBonus += 0.5;
        }

        return baseScore + sameLevelBonus + mobPriorityBonus;
    }

    private boolean isTargetValid(LivingEntity entity) {
        if (entity == null || !entity.isAlive()) return false;
        // Chase range is much larger than acquisition range: once a target is
        // acquired, the brain follows it (via baritone) up to max-chase-range
        // instead of abandoning the chase at target-range + 2.
        double maxChase = maxChaseRange.get();
        if (entity.distanceToSqr(mc.player) > maxChase * maxChase) return false; // ⚡ Bolt: Use distanceToSqr to avoid Math.sqrt overhead
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
        // ⚡ Bolt: Replace Math.pow with an iterative loop to avoid JNI and floating-point overhead
        double totemExposure = 1.0;
        for (int i = 0; i < totems; i++) totemExposure *= 0.4;

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
                } else if (entity.getType() == EntityTypes.END_CRYSTAL) {
                    double dist = entity.distanceTo(mc.player);
                    if (dist < 12.0) {
                        crystalPressure += (12.0 - dist) / 12.0;
                    }
                }
            }
        }

        double envThreat = Math.min(1.0, envPressure * 0.8);

        double coverFactor = 1.0;
        if (coverModifier.get() && terrainGrid != null && terrainGrid.getGridSize() > 0 && mc.level != null) {
            // AwarenessCube cover: solid cells in the 2-block shell at feet/head height.
            coverFactor = AwarenessMath.coverThreatFactor(terrainGrid.getCoverCount());
        } else if (coverModifier.get() && mc.level != null) {
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
            savedAutoShieldBreak = killAura.autoShieldBreak.get();
            savedShieldMode = ((Setting<KillAura.ShieldMode>) (Setting<?>) killAura.settings.get("shield-mode")).get();
            savedEntities = new java.util.HashSet<>(
                ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).get()
            );
            savedKillAura = true;
        }

        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-baritone")).set(false);
        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("auto-switch")).set(true);
        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("swap-back")).set(false);
        killAura.autoShieldBreak.set(true);

        Set<EntityType<?>> killAuraEntities = new java.util.HashSet<>(entities.get());
        if (targetPlayers.get()) killAuraEntities.add(EntityTypes.PLAYER);
        ((Setting<Set<EntityType<?>>>) (Setting<?>) killAura.settings.get("entities")).set(killAuraEntities);
        // Set max-targets to the full group size so KillAura attacks ALL threats,
        // not just 1 or 3. Cap at 10 to avoid absurd values.
        int groupSize = (lastGroupSnapshot != null && !lastGroupSnapshot.targets().isEmpty())
            ? Math.min(10, Math.max(3, lastGroupSnapshot.targets().size()))
            : 3;
        ((Setting<Integer>) (Setting<?>) killAura.settings.get("max-targets")).set(groupSize);

        // Set KillAura attack range to 3.8 (standard PvP reach ensuring all swings connect reliably)
        ((Setting<Double>) (Setting<?>) killAura.settings.get("range")).set(3.8);

        // Add 1-tick switch delay so swapping items doesn't trigger FastSwitch checks
        ((Setting<Integer>) (Setting<?>) killAura.settings.get("switch-delay")).set(1);

        // Enable Shield breaking mode on KillAura so axes automatically disable enemy shields
        ((Setting<KillAura.ShieldMode>) (Setting<?>) killAura.settings.get("shield-mode")).set(KillAura.ShieldMode.Break);

        // Set KillAura RotationMode to Always so KillAura aims at targets when in range
        ((Setting<KillAura.RotationMode>) (Setting<?>) killAura.settings.get("rotate")).set(KillAura.RotationMode.Always);

        // Wall & digging awareness: only attack visible targets (walls-range 0) and
        // pause attacks while mining/using items, so combat never swings through cave
        // walls nor fights the left-click with baritone's digging.
        ((Setting<Double>) (Setting<?>) killAura.settings.get("walls-range")).set(0.0);
        ((Setting<Boolean>) (Setting<?>) killAura.settings.get("pause-on-use")).set(true);
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
            killAura.autoShieldBreak.set(savedAutoShieldBreak);
            ((Setting<KillAura.ShieldMode>) (Setting<?>) killAura.settings.get("shield-mode")).set(savedShieldMode);
        }
        savedKillAura = false;
    }

    private void enableCombatModules() {
        syncKillAura();

        enableModule(KillAura.class);
        if (autoArmor.get()) enableModule(AutoArmor.class);
        if (autoWeapon.get()) enableModule(AutoWeapon.class);
        enableModule(AutoTool.class);

        // Only enable ShieldAutoSwap if the player actually has a shield somewhere
        // in their inventory — enabling it with no shield is a silent no-op that
        // confuses the user (module shows active, does nothing).
        if (shieldSwap.get()) {
            boolean hasShield = InvUtils.find(itemStack -> itemStack.getItem() == Items.SHIELD).found();
            if (hasShield) enableModule(ShieldAutoSwapModule.class);
        }

        if (criticals.get()) enableModule(Criticals.class);
    }

    private void disableCombatModules() {
        // If an enemy is actively within 5.5 blocks pursuing us, DO NOT disarm!
        // Keep KillAura, AutoWeapon, AutoArmor, and Shield active so the bot fights back and knocks the pursuer away.
        // ⚡ Bolt: Use distanceToSqr to avoid Math.sqrt overhead
        if (currentTarget != null && currentTarget.isAlive() && mc.player != null && mc.player.distanceToSqr(currentTarget) <= 5.5 * 5.5) {
            syncKillAura();
            enableModule(KillAura.class);
            if (autoArmor.get()) enableModule(AutoArmor.class);
            if (autoWeapon.get()) enableModule(AutoWeapon.class);
            if (shieldSwap.get()) {
                boolean hasShield = InvUtils.find(itemStack -> itemStack.getItem() == Items.SHIELD).found();
                if (hasShield) enableModule(ShieldAutoSwapModule.class);
            }
            return;
        }

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

        // Remember the target we're fighting so a retreat/re-acquire around a cave
        // corner doesn't strand the brain in SCANNING (see findBestTarget LOS gate).
        lastTargetUuid = currentTarget.getUUID();
        lastTargetTick = tickCounter;

        // While baritone is actively digging, YIELD all movement to the dig. The
        // hit-and-run phase machine must not run here: mining resets the attack
        // cooldown, so attackReady flickers and flips STRIKE/BUBBLE every few
        // ticks, and every flip re-dispatches baritone follow with a different
        // distance → constant path recalculation that also steals control from
        // the dig. KillAura (walls-range 0, pause-on-use true) still swings at
        // mobs that come into actual reach.
        if (isCurrentlyDigging() || isRestocking()) {
            if (followController != null) followController.releaseDirectKeys();
            if (combatPathManager != null && isRestocking()) combatPathManager.releaseControl();
            return;
        }

        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int totems = countTotems();
        // Shield-aware risk: only trade hits while a usable shield is up (or invincible).
        // Without a shield the bot must NOT bum-rush — it hit-and-runs from the bubble.
        boolean canTakeRisk = isInvincible()
            || (hasUsableShield() && (totems > 0 || health >= 14.0f || (combatMode == CombatMode.AGGRESSIVE && health >= 10.0f)));

        // --- Gather the full threat group every 4 ticks ---
        refreshGroupAwarenessAndFavoring(true);

        // --- Re-analyze primary target periodically ---
        if (lastAnalysis == null
            || lastAnalysis.entity() != currentTarget
            || followRecalcTimer >= followRecalcTicks.get()) {
            lastAnalysis = CombatTargetAnalyzer.analyze(currentTarget);
            followRecalcTimer = 0;
        }
        followRecalcTimer++;

        // Resolve follow / strike distance
        double targetDistance = followDistance.get();
        if (dynamicFollow.get() && lastAnalysis != null) {
            targetDistance = CombatTargetAnalyzer.computeDynamicFollowDistance(lastAnalysis, CombatMode.modePadding(combatMode));
        }
        lastFollowDistance = targetDistance;
        double effectiveStrikeDist = Math.min(strikeDistance.get(), targetDistance);

        // --- Tactical positioning: group-aware bubble movement, 1v1 circle strafing & ranged kiting ---
        CombatTacticalPositioner.TacticalMode tacticalMode = CombatTacticalPositioner.TacticalMode.IDLE;
        if (tacticalPositioner != null && lastGroupSnapshot != null && !lastGroupSnapshot.targets().isEmpty()) {
            tacticalMode = tacticalPositioner.tick(
                (net.minecraft.client.player.LocalPlayer) mc.player,
                followController,
                lastGroupSnapshot,
                canTakeRisk,
                effectiveStrikeDist,
                targetDistance,
                combatMode
            );
        }

        if (tacticalMode == CombatTacticalPositioner.TacticalMode.SPEAR_SWEET_SPOT) {
            if (terrainGrid != null) terrainGrid.update(currentTarget);
            return;
        }

        // --- Ranged Kite Combat Execution ---
        if (combatMode == CombatMode.RANGED_KITE) {
            // Don't draw or fire at a target behind cover: the bow is useless through a wall
            // and the draw would just reset forever. Mode selection gates RANGED_KITE on
            // visibility, but hysteresis can keep the mode active briefly after the target
            // steps behind cover, so re-check LOS here every tick.
            boolean targetVisible = currentTarget != null && terrainGrid != null && terrainGrid.isTargetVisible(currentTarget);

            FindItemResult ranged = InvUtils.find(Items.BOW, Items.CROSSBOW, Items.TRIDENT);
            if (targetVisible && ranged.found() && !mc.player.getMainHandItem().is(Items.BOW)
                && !mc.player.getMainHandItem().is(Items.CROSSBOW)
                && !mc.player.getMainHandItem().is(Items.TRIDENT)) {
                InvUtils.swap(ranged.slot(), false);
            }

            if (mc.player.getMainHandItem().is(Items.BOW)) {
                if (!targetVisible) {
                    mc.options.keyUse.setDown(false);
                    mc.player.stopUsingItem();
                    return;
                }
                double yaw = Rotations.getYaw(currentTarget);
                double pitch = Rotations.getPitch(currentTarget, Target.Head);
                Rotations.rotate(yaw, pitch, 100, true, null);

                if (mc.player.getTicksUsingItem() >= 20) {
                    mc.options.keyUse.setDown(false);
                    // stopUsingItem() only cancels the draw client-side and never fires;
                    // the server-synced release is what actually shoots the arrow
                    mc.gameMode.releaseUsingItem(mc.player);
                } else {
                    mc.options.keyUse.setDown(true);
                }
            }
            return;
        }

        if (terrainGrid != null) {
            terrainGrid.update(currentTarget);
        }
        double dist = mc.player.distanceTo(currentTarget);

        // Post-pop finish push: keep AGGRESSIVE_STRIKE active without bouncing to bubble
        if (tacticalMode == CombatTacticalPositioner.TacticalMode.AGGRESSIVE_STRIKE
            && CombatTargetAnalyzer.isRecentlyPopped(currentTarget, 3500L)) {
            strikePhase = StrikePhase.STRIKE;
            strikeTimer = 0;
            if (followController != null) {
                followController.follow(currentTarget, Math.min(2.4, effectiveStrikeDist));
            }
            return;
        }

        // If hit-and-run is disabled, maintain direct follow
        if (!hitAndRun.get()) {
            if (canTakeRisk) {
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
            } else if (followController != null) {
                // No shield: hold the safety bubble instead of standing in melee range
                followController.maintainDistance(currentTarget, bubbleDistance.get(), bubbleDistance.get() + 1.0);
            }
            return;
        }

        // Walled off (but not digging): the STRIKE dart-in cannot physically reach,
        // so running the phase machine just flips phases and re-dispatches baritone
        // every few ticks → constant path recalculation. Keep a single stable
        // baritone follow at bubble distance; the fight resumes when the path opens.
        if (followController != null && !followController.isDirectPathClear(currentTarget)) {
            followController.follow(currentTarget, bubbleDistance.get());
            return;
        }

        // --- Hit-and-run strike cycle (damage-aware) ---
        double myAtkSpeed = 1.6;
        try {
            myAtkSpeed = mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        } catch (Exception e) {
            DebugLog.debug("CombatBrain", "Could not read own ATTACK_SPEED, using {}", myAtkSpeed, e);
        }
        int optimalStrikeTicks = (int) Math.ceil(20.0 / myAtkSpeed) + 4;
        int activeStrikeDuration = Math.max(optimalStrikeTicks, strikeDurationTicks.get());

        boolean attackReady = mc.player.getAttackStrengthScale(0.5f) >= 0.85f;
        boolean takingDamage = mc.player.hurtTime > 0;
        boolean cooldownReady = bubbleTimer >= retreatCooldownTicks.get();
        // Dart in only when NOT taking damage AND (the mob is already inside reach,
        // or the attack is charged after the bubble cooldown). The old `|| attackReady`
        // made shouldStrike true ~90% of the time, so BUBBLE re-struck instantly after
        // every hit → rapid STRIKE/BUBBLE oscillation that looked like the bot was
        // randomly selecting and dropping targets.
        boolean shouldStrike = !takingDamage && (dist <= 3.2 || (cooldownReady && attackReady));

        if (strikePhase == StrikePhase.STRIKE) {
            // Damage feedback: taking hits without a shield (or critically low) ends the
            // strike immediately — retreat to the bubble instead of trading blows.
            boolean losingTrade = takingDamage && !canTakeRisk;
            if ((strikeTimer >= activeStrikeDuration && !canTakeRisk) || losingTrade) {
                strikePhase = StrikePhase.BUBBLE;
                strikeTimer = 0;
                bubbleTimer = 0;
                if (followController != null) {
                    followController.maintainDistance(currentTarget, bubbleDistance.get(), bubbleDistance.get() + 1.0);
                }
            } else {
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
                strikeTimer += 2;
            }
        } else { // BUBBLE phase
            if (shouldStrike && !takingDamage) {
                strikePhase = StrikePhase.STRIKE;
                strikeTimer = 0;
                if (followController != null) {
                    followController.follow(currentTarget, effectiveStrikeDist);
                }
            } else {
                // Hold the safety bubble OUTSIDE the target's reach: back off when the
                // mob closes in, approach only when it backs off.
                if (followController != null) {
                    followController.maintainDistance(currentTarget, bubbleDistance.get(), bubbleDistance.get() + 1.0);
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

    private void refreshGroupAwarenessAndFavoring(boolean allowTargetSwitch) {
        if (mc.level == null || mc.player == null) return;
        groupAwarenessTimer += 2;
        if (lastGroupSnapshot != null && groupAwarenessTimer < 4) return;

        groupAwarenessTimer = 0;
        java.util.List<LivingEntity> allThreats = new java.util.ArrayList<>();
        double acquireRangeSq = acquireRange.get() * acquireRange.get();
        for (net.minecraft.world.entity.Entity e : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
            if (le.distanceToSqr(mc.player) > acquireRangeSq) continue;
            Set<EntityType<?>> types = entities.get();
            if (!types.isEmpty() && !types.contains(le.getType())) continue;
            if (le instanceof Player p) {
                if (!targetPlayers.get() || p.isCreative() || !Friends.get().shouldAttack(p)) continue;
            }
            allThreats.add(le);
        }
        allThreats.sort((a, b) -> Double.compare(scoreTarget(b), scoreTarget(a)));
        lastGroupSnapshot = CombatGroupAwareness.compute(
            (net.minecraft.client.player.LocalPlayer) mc.player, allThreats);

        if (allowTargetSwitch && lastGroupSnapshot != null && lastGroupSnapshot.frontlineTarget() != null) {
            LivingEntity front = lastGroupSnapshot.frontlineTarget();
            if (isTargetValid(front)) {
                currentTarget = front;
            }
        }
        if (combatPathManager != null) {
            combatPathManager.updateThreatFavoring(currentTarget, allThreats);
        }
    }

    private void doRetreatTick() {
        if (isRestocking()) {
            if (followController != null) followController.releaseDirectKeys();
            if (combatPathManager != null) combatPathManager.releaseControl();
            return;
        }
        refreshGroupAwarenessAndFavoring(false);
        // Move away from threat using group-aware Baritone flee
        if (followController != null) followController.releaseDirectKeys();
        fleeStep(fleeDistance.get());
    }

    private void doHealTick() {
        if (isRestocking()) {
            if (followController != null) followController.releaseDirectKeys();
            if (combatPathManager != null) combatPathManager.releaseControl();
            return;
        }
        refreshGroupAwarenessAndFavoring(false);
        // Sprint away from threats along largest gap bearing while healing
        if (followController != null) followController.releaseDirectKeys();
        fleeStep(fleeDistance.get() * 1.5);
        if (mc.player != null && mc.player.getHealth() < mc.player.getMaxHealth() && mc.options != null) {
            mc.options.keySprint.setDown(true);
        }
    }

    private void doFleeTick() {
        if (isRestocking()) {
            if (followController != null) followController.releaseDirectKeys();
            if (combatPathManager != null) combatPathManager.releaseControl();
            return;
        }
        refreshGroupAwarenessAndFavoring(false);
        // Sprint away from everything along safest open vector
        if (mc.player != null && mc.options != null) {
            mc.options.keySprint.setDown(true);
        }
        if (followController != null) followController.releaseDirectKeys();
        fleeStep(fleeDistance.get());
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
                // Only enable block-placement modules if blocks are available
                disableModule(KillAura.class);
                disableModule(ArrowDodge.class);
                if (hasPlaceableBlocksInHotbar(4)) {
                    enableModule(Surround.class);
                    enableModule(HoleFiller.class);
                } else {
                    info("Burrow skipped: no blocks in hotbar");
                }
                transitionTo(BrainState.HEALING);
            }
            case FLEE -> {
                info("Emergency: fleeing");
                disableAllManagedModules();
                if (mc.player != null) {
                    mc.player.setSprinting(true);
                }
                if (followController != null) followController.releaseDirectKeys();
                if (combatPathManager != null && currentTarget != null) {
                    combatPathManager.startFlee(currentTarget, fleeDistance.get() * 2.0, lastGroupSnapshot);
                } else if (followController != null && currentTarget != null) {
                    followController.flee(currentTarget, fleeDistance.get() * 2.0);
                }
                transitionTo(BrainState.FLEEING);
            }
        }
    }

    /**
     * Tick handler for EMERGENCY_HOLE state: navigate to the nearest safe 1x1
     * bedrock/obsidian hole and enable Surround once inside. Falls back to FLEEING
     * if no hole is found within the search radius or after 100 ticks.
     */
    private void doEmergencyHoleTick() {
        emergencyHoleTimer += 2;

        // Timeout: if we haven't reached a hole in 100 ticks, flee instead
        if (emergencyHoleTimer > 100) {
            emergencyHoleTarget = null;
            transitionTo(BrainState.FLEEING);
            return;
        }

        // Search for or reuse the hole target
        if (emergencyHoleTarget == null) {
            // Time-sliced search: keep waiting (the timeout above still applies) until it has an answer
            if (!pollSafeHole(SAFE_HOLE_RADIUS)) return;
            emergencyHoleTarget = safeHoleResult();
            if (emergencyHoleTarget == null) {
                // No safe hole nearby — just flee
                transitionTo(BrainState.FLEEING);
                return;
            }
            info("Emergency hole found at " + emergencyHoleTarget.toShortString());
        }

        // Check if we're inside the hole
        if (mc.player != null) {
            BlockPos playerPos = mc.player.blockPosition();
            if (playerPos.equals(emergencyHoleTarget) || playerPos.equals(emergencyHoleTarget.above())) {
                // Inside the hole: enable Surround if blocks available, then heal
                if (combatPathManager != null) combatPathManager.releaseControl();
                if (hasPlaceableBlocksInHotbar(4)) {
                    enableModule(Surround.class);
                } else {
                    info("In hole but no blocks for Surround");
                }
                emergencyHoleTarget = null;
                transitionTo(BrainState.HEALING);
                return;
            }
        }

        // Path to the hole using CombatPathManager (or CustomGoalProcess fallback)
        if (combatPathManager != null) {
            if (followController != null) followController.releaseDirectKeys();
            combatPathManager.startRetreat(emergencyHoleTarget, currentTarget);
        } else {
            baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(
                    emergencyHoleTarget.getX(), emergencyHoleTarget.getY(), emergencyHoleTarget.getZ()));
        }
    }

    private static final int[] HOLE_DX = {0, 0, 1, -1};
    private static final int[] HOLE_DZ = {-1, 1, 0, 0};
    private static final int SAFE_HOLE_RADIUS = 16;

    /** Time-sliced emergency-hole search (plan S86/S88): spread over ticks, cached while fresh. */
    private final SafeHoleSearch holeSearch = new SafeHoleSearch(40, 3);
    private final BlockPos.MutableBlockPos holeProbePos = new BlockPos.MutableBlockPos();
    private final SafeHoleSearch.Probe holeProbe = this::scoreSafeHole;

    /**
     * Advances the time-sliced search for the best safe 1x1 hole (surrounded by bedrock or obsidian on all
     * 4 sides) within {@code radius} blocks of the player. Candidates are scored by distance, vertical climb
     * penalty, bedrock wall bonus and enemy intercept margin.
     *
     * @return true once a result for the current position is ready; read it with {@link #safeHoleResult()}
     */
    private boolean pollSafeHole(int radius) {
        if (mc.player == null || mc.level == null) return false;
        BlockPos p = mc.player.blockPosition();
        return holeSearch.tick(mc.player.tickCount, p.getX(), p.getY(), p.getZ(), radius, TickBudget.game(), holeProbe);
    }

    /**
     * The ready search result re-validated against the current world (the cached hole may have been filled or
     * taken since the scan), or null when there is no usable hole.
     */
    private BlockPos safeHoleResult() {
        if (!holeSearch.found()) return null;
        int x = holeSearch.x(), y = holeSearch.y(), z = holeSearch.z();
        if (!isSafeHoleAt(x, y, z) || isHoleOccupied(x, y, z)) {
            holeSearch.invalidate();
            return null;
        }
        return new BlockPos(x, y, z);
    }

    /** Score of a candidate hole floor cell for {@link SafeHoleSearch} (lower is better, NaN when unusable). */
    private double scoreSafeHole(int x, int y, int z) {
        if (mc.player == null || !isSafeHoleAt(x, y, z) || isHoleOccupied(x, y, z)) return Double.NaN;
        BlockPos playerPos = mc.player.blockPosition();
        double ddx = x - playerPos.getX(), ddy = y - playerPos.getY(), ddz = z - playerPos.getZ();
        double playerDistSq = ddx * ddx + ddy * ddy + ddz * ddz;
        double dist = Math.sqrt(playerDistSq);
        int dy = y - playerPos.getY();
        double verticalPenalty = dy > 0 ? dy * 3.5 : Math.abs(dy) * 0.5;
        double bedrockBonus = countBedrockSurroundingsAt(x, y, z) * 1.1;

        double interceptPenalty = 0.0;
        if (currentTarget != null) {
            BlockPos t = currentTarget.blockPosition();
            double tdx = x - t.getX(), tdy = y - t.getY(), tdz = z - t.getZ();
            double targetDistSq = tdx * tdx + tdy * tdy + tdz * tdz;
            if (targetDistSq < playerDistSq) {
                double margin = dist - Math.sqrt(targetDistSq);
                interceptPenalty += 12.0 + margin * 2.0;
            }
            if (targetDistSq < 9.0) {
                interceptPenalty += 4.0;
            }
        }
        return dist + verticalPenalty - bedrockBonus + interceptPenalty;
    }

    private boolean isHoleOccupied(int x, int y, int z) {
        if (mc.level == null) return false;
        double cx = x + 0.5;
        double cz = z + 0.5;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isDeadOrDying()) continue;
            if (Math.abs(player.getX() - cx) < 0.8 && Math.abs(player.getZ() - cz) < 0.8 && Math.abs(player.getY() - y) <= 1.2) {
                return true;
            }
        }
        return false;
    }

    private int countBedrockSurroundingsAt(int x, int y, int z) {
        if (mc.level == null) return 0;
        BlockPos.MutableBlockPos m = holeProbePos;
        int count = 0;
        if (mc.level.getBlockState(m.set(x, y - 1, z)).getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) count++;
        for (int i = 0; i < 4; i++) {
            if (mc.level.getBlockState(m.set(x + HOLE_DX[i], y, z + HOLE_DZ[i])).getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) count++;
            if (mc.level.getBlockState(m.set(x + HOLE_DX[i], y + 1, z + HOLE_DZ[i])).getBlock() == net.minecraft.world.level.block.Blocks.BEDROCK) count++;
        }
        return count;
    }

    /**
     * Returns true if the cell is a valid safe hole: it and the block above are air, the cell above that has no
     * collision (entry opening), and all 4 cardinal sides (2 high) plus the floor are blast-resistant.
     * Allocation free (plan S88).
     */
    private boolean isSafeHoleAt(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockPos.MutableBlockPos m = holeProbePos;
        // Cheapest rejections first: the hole itself must be air (2 blocks tall)
        if (!mc.level.getBlockState(m.set(x, y, z)).isAir()) return false;
        if (!mc.level.getBlockState(m.set(x, y + 1, z)).isAir()) return false;
        if (!isBlastResistant(mc.level.getBlockState(m.set(x, y - 1, z)))) return false;
        for (int i = 0; i < 4; i++) {
            if (!isBlastResistant(mc.level.getBlockState(m.set(x + HOLE_DX[i], y, z + HOLE_DZ[i])))) return false;
            if (!isBlastResistant(mc.level.getBlockState(m.set(x + HOLE_DX[i], y + 1, z + HOLE_DZ[i])))) return false;
        }
        m.set(x, y + 2, z);
        return mc.level.getBlockState(m).getCollisionShape(mc.level, m).isEmpty();
    }

    private boolean isBlastResistant(BlockState state) {
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block == net.minecraft.world.level.block.Blocks.BEDROCK
            || block == net.minecraft.world.level.block.Blocks.OBSIDIAN
            || block == net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN
            || block == net.minecraft.world.level.block.Blocks.REINFORCED_DEEPSLATE;
    }

    /**
     * Checks if the player has at least {@code minCount} total solid placeable
     * blocks in hotbar slots 0-8.
     */
    private boolean hasPlaceableBlocksInHotbar(int minCount) {
        if (mc.player == null) return false;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                net.minecraft.world.level.block.Block b = net.minecraft.world.level.block.Block.byItem(stack.getItem());
                if (b != net.minecraft.world.level.block.Blocks.AIR) {
                    count += stack.getCount();
                    if (count >= minCount) return true;
                }
            }
        }
        return false;
    }

    /**
     * Updates BaritoneAPI.allowBreak based on whether the player has a digging
     * tool (pickaxe, axe, or shovel) in their hotbar or main inventory.
     * Prevents Baritone from slowly hand-mining stone or obsidian.
     */
    private void updateAllowBreak() {
        if (mc.player == null) return;
        boolean hasTool = hasDiggingTool();
        BaritoneAPI.getSettings().allowBreak.value = hasTool;
    }

    /**
     * Returns true if the player has a digging tool (pickaxe, axe, shovel, or
     * hoe) anywhere in their inventory. Uses direct item identity checks since
     * PickaxeItem/ShovelItem classes are not exposed in this MC version.
     */
    private boolean hasDiggingTool() {
        if (mc.player == null) return false;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            net.minecraft.world.item.Item item = stack.getItem();
            if (isDiggingTool(item)) return true;
        }
        return false;
    }

    private static boolean isDiggingTool(net.minecraft.world.item.Item item) {
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE
            || item == Items.IRON_PICKAXE   || item == Items.GOLDEN_PICKAXE
            || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE
            || item == Items.WOODEN_SHOVEL   || item == Items.STONE_SHOVEL
            || item == Items.IRON_SHOVEL     || item == Items.GOLDEN_SHOVEL
            || item == Items.DIAMOND_SHOVEL  || item == Items.NETHERITE_SHOVEL
            || item.getDefaultInstance().is(net.minecraft.tags.ItemTags.AXES);
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

    /** Force a combat mode (null = automatic). Used by `.combat-brain mode`. */
    public void setForcedMode(CombatMode mode) {
        this.forcedMode = mode;
    }

    public CombatMode getForcedMode() {
        return forcedMode;
    }

    /** Last resolved follow/strike distance in blocks, or NaN if not engaged yet. */
    public double getFollowDistance() {
        return lastFollowDistance;
    }

    public CombatTerrainGrid getTerrainGrid() {
        return terrainGrid;
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
