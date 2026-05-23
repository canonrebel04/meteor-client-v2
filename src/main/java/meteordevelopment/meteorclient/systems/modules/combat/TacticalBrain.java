/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

public class TacticalBrain extends Module {
    public enum PvPMode {
        Melee,
        Crystal
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<PvPMode> pvpMode = sgGeneral.add(new EnumSetting.Builder<PvPMode>()
        .name("pvp-mode")
        .description("The preferred style of PvP engagement.")
        .defaultValue(PvPMode.Melee)
        .build()
    );

    private final Setting<Double> threatThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("threat-threshold")
        .description("The threat level (0-1) above which the brain starts retreating.")
        .defaultValue(0.6)
        .min(0.0)
        .max(1.0)
        .build()
    );

    private final Setting<Integer> minTotems = sgGeneral.add(new IntSetting.Builder()
        .name("min-totems")
        .description("The minimum total totems in inventory before initiating retreat.")
        .defaultValue(2)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Double> fleeDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("flee-distance")
        .description("Distance to flee from threat if no holes are nearby.")
        .defaultValue(8.0)
        .min(2.0)
        .sliderMax(20.0)
        .build()
    );

    private CombatPathManager pathManager;
    private TacticalAction currentAction = TacticalAction.IDLE;

    public TacticalBrain() {
        super(Categories.Combat, "tactical-brain", "Manages combat action transitions and humanized Baritone integration.");
    }

    @Override
    public void onActivate() {
        if (pathManager == null) {
            pathManager = new CombatPathManager();
        }
        currentAction = TacticalAction.IDLE;
    }

    @Override
    public void onDeactivate() {
        if (pathManager != null) {
            pathManager.stop();
        }
        currentAction = TacticalAction.IDLE;
        teardownAllCombatModules();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (pathManager == null) {
            pathManager = new CombatPathManager();
        }

        // 1. Gather Threat & Terrain Profiles
        ThreatSnapshot threat = new ThreatSnapshot(15.0);
        TerrainProfile terrain = new TerrainProfile(5);

        // 2. Compute Target TacticalAction
        TacticalAction nextAction = computeNextAction(threat, terrain);

        // 3. Apply state change if target action differs
        if (nextAction != currentAction) {
            transitionTo(nextAction, threat, terrain);
        }
    }

    private TacticalAction computeNextAction(ThreatSnapshot threat, TerrainProfile terrain) {
        if (mc.player == null) return TacticalAction.IDLE;

        // AutoLog check (Emergency log)
        float health = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        int totems = countTotems();
        if (health <= 4.0f && totems == 0 && threat.getThreatLevel() > 0.8) {
            return TacticalAction.EMERGENCY_LOG;
        }

        // Check if threat is too high, requiring retreat
        if (threat.getThreatLevel() >= threatThreshold.get() || totems < minTotems.get()) {
            BlockPos safeHole = terrain.getNearestSafeHole();
            if (safeHole != null) {
                return TacticalAction.EMERGENCY_HOLE;
            } else {
                return TacticalAction.RETREAT_PARKOUR;
            }
        }

        // If target is present and threat is low, engage
        if (threat.getTarget() != null) {
            return pvpMode.get() == PvPMode.Crystal ? TacticalAction.ENGAGE_CRYSTAL : TacticalAction.ENGAGE_MELEE;
        }

        return TacticalAction.IDLE;
    }

    private void transitionTo(TacticalAction newAction, ThreatSnapshot threat, TerrainProfile terrain) {
        info("Transitioning combat state: " + currentAction + " -> " + newAction);
        
        // 1. Teardown modules belonging to the old state
        teardownStateModules(currentAction);

        // 2. Setup modules belonging to the new state
        setupStateModules(newAction, threat, terrain);

        currentAction = newAction;
    }

    private void setupStateModules(TacticalAction action, ThreatSnapshot threat, TerrainProfile terrain) {
        switch (action) {
            case ENGAGE_MELEE:
                pathManager.stop();
                enableModule(KillAura.class);
                enableModule(Criticals.class);
                enableModule(AutoWeapon.class);
                break;
            case ENGAGE_CRYSTAL:
                pathManager.stop();
                enableModule(CrystalAura.class);
                enableModule(AutoTotem.class);
                break;
            case RETREAT_PARKOUR:
                if (threat.getTarget() != null) {
                    pathManager.startFlee(threat.getTarget(), fleeDistance.get());
                }
                enableModule(AutoTotem.class);
                break;
            case EMERGENCY_HOLE:
                BlockPos hole = terrain.getNearestSafeHole();
                if (hole != null && threat.getTarget() != null) {
                    pathManager.startRetreat(hole, threat.getTarget());
                }
                enableModule(Surround.class);
                enableModule(AutoTotem.class);
                break;
            case EMERGENCY_LOG:
                pathManager.stop();
                if (mc.getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(Component.literal("TacticalBrain: Emergency Log trigger."));
                }
                break;
            case IDLE:
            default:
                pathManager.stop();
                break;
        }
    }

    private void teardownStateModules(TacticalAction action) {
        switch (action) {
            case ENGAGE_MELEE:
                disableModule(KillAura.class);
                disableModule(Criticals.class);
                disableModule(AutoWeapon.class);
                break;
            case ENGAGE_CRYSTAL:
                disableModule(CrystalAura.class);
                break;
            case RETREAT_PARKOUR:
                pathManager.stop();
                break;
            case EMERGENCY_HOLE:
                pathManager.stop();
                disableModule(Surround.class);
                break;
            case EMERGENCY_LOG:
            case IDLE:
            default:
                break;
        }
    }

    private void teardownAllCombatModules() {
        disableModule(KillAura.class);
        disableModule(Criticals.class);
        disableModule(AutoWeapon.class);
        disableModule(CrystalAura.class);
        disableModule(Surround.class);
        disableModule(Scaffold.class);
    }

    private void enableModule(Class<? extends Module> klass) {
        Module module = Modules.get().get(klass);
        if (module != null) {
            module.enable();
        }
    }

    private void disableModule(Class<? extends Module> klass) {
        Module module = Modules.get().get(klass);
        if (module != null) {
            module.disable();
        }
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        if (mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
            count++;
        }
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String getInfoString() {
        return currentAction.name();
    }
}
