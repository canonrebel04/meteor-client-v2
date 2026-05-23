/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.pathing.Favoring;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatPathManager {
    private final IBaritone baritone;
    private final CombatProcess process;
    
    private boolean active = false;
    private Goal activeGoal = null;
    private PathingCommandType activeCommandType = null;
    
    private Player activeTarget = null;
    private BlockPos activeHole = null;

    public CombatPathManager() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.process = new CombatProcess();
        this.baritone.getPathingControlManager().registerProcess(process);
    }

    public void startRetreat(BlockPos hole, Player enemy) {
        this.activeTarget = enemy;
        this.activeHole = hole;
        this.activeGoal = new GoalGetToBlock(hole);
        this.activeCommandType = PathingCommandType.SET_GOAL_AND_PATH;
        this.active = true;
        enableFavoring();
        enableCombatMode(true);
    }

    public void startFlee(Player enemy, double distance) {
        this.activeTarget = enemy;
        this.activeHole = null;
        
        // Calculate flee direction away from enemy
        double dx = mc.player.getX() - enemy.getX();
        double dz = mc.player.getZ() - enemy.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0) {
            dx = (dx / len) * distance;
            dz = (dz / len) * distance;
        } else {
            dx = distance;
            dz = 0;
        }
        
        BlockPos fleeTarget = mc.player.blockPosition().offset((int) dx, 0, (int) dz);
        this.activeGoal = new GoalXZ(fleeTarget.getX(), fleeTarget.getZ());
        this.activeCommandType = PathingCommandType.SET_GOAL_AND_PATH;
        this.active = true;
        enableFavoring();
        enableCombatMode(true);
    }

    public void pausePathing() {
        this.activeTarget = null;
        this.activeHole = null;
        this.activeGoal = null;
        this.activeCommandType = PathingCommandType.REQUEST_PAUSE;
        this.active = true;
        disableFavoring();
        enableCombatMode(false);
    }

    public void stop() {
        this.active = false;
        this.activeGoal = null;
        this.activeCommandType = null;
        this.activeTarget = null;
        this.activeHole = null;
        disableFavoring();
        enableCombatMode(false);
        baritone.getPathingBehavior().cancelEverything();
    }

    public boolean isActive() {
        return active;
    }

    private void enableCombatMode(boolean enabled) {
        // disabled
    }

    private void enableFavoring() {
        // disabled
    }

    private void disableFavoring() {
        // disabled
    }

    private class CombatProcess implements IBaritoneProcess {
        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public PathingCommand onTick(boolean b, boolean b1) {
            if (activeCommandType == PathingCommandType.REQUEST_PAUSE) {
                baritone.getInputOverrideHandler().clearAllKeys();
            }
            return new PathingCommand(activeGoal, activeCommandType);
        }

        @Override
        public boolean isTemporary() {
            return true;
        }

        @Override
        public void onLostControl() {
        }

        @Override
        public double priority() {
            return 100.0;
        }

        @Override
        public String displayName0() {
            return "Combat Pathing";
        }
    }
}
