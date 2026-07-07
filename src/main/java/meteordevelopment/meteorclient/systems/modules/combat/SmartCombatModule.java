/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Set;

public class SmartCombatModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // General

    private final Setting<CombatMode> combatMode = sgGeneral.add(new EnumSetting.Builder<CombatMode>()
        .name("combat-mode")
        .description("Combat strategy: SMART adapts, AGGRESSIVE prioritizes attack speed, DEFENSIVE prioritizes safety.")
        .defaultValue(CombatMode.SMART)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> targets = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("targets")
        .description("Entity types to target.")
        .onlyAttackable()
        .defaultValue(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER)
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum attack range.")
        .defaultValue(4.5)
        .min(1)
        .max(8)
        .build()
    );

    // Timing

    private final Setting<Integer> delay = sgTiming.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between attacks.")
        .defaultValue(10)
        .min(1)
        .max(40)
        .build()
    );

    private Entity currentTarget;
    private int attackTimer;

    public SmartCombatModule() {
        super(Categories.Combat, "smart-combat", "Centralized combat config: target selection, attack timing, and combat strategy.");
    }

    @Override
    public void onActivate() {
        currentTarget = null;
        attackTimer = 0;
    }

    @Override
    public void onDeactivate() {
        currentTarget = null;
        attackTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        if (attackTimer > 0) {
            attackTimer--;
        }

        if (currentTarget == null || !currentTarget.isAlive() || (currentTarget instanceof LivingEntity living && living.isDeadOrDying())) {
            currentTarget = TargetUtils.get(this::isValidTarget, SortPriority.ClosestAngle);
        }

        if (currentTarget == null) return;

        Rotations.rotate(Rotations.getYaw(currentTarget), Rotations.getPitch(currentTarget, Target.Body));

        double dist = mc.player.distanceTo(currentTarget);
        if (attackTimer <= 0 && dist <= range.get()) {
            mc.gameMode.attack(mc.player, currentTarget);
            mc.player.swing(InteractionHand.MAIN_HAND);
            attackTimer = delay.get();
        }
    }

    private boolean isValidTarget(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if (!entity.isAlive()) return false;
        if (entity instanceof LivingEntity living && living.isDeadOrDying()) return false;
        if (!targets.get().contains(entity.getType())) return false;
        return PlayerUtils.isWithin(entity, range.get());
    }

    public Entity getTarget() {
        return currentTarget;
    }

    public CombatMode getCombatMode() {
        return combatMode.get();
    }

    public double getRange() {
        return range.get();
    }

    public int getDelay() {
        return delay.get();
    }

    public int getAttackTimer() {
        return attackTimer;
    }

    public enum CombatMode {
        SMART,
        AGGRESSIVE,
        DEFENSIVE
    }
}