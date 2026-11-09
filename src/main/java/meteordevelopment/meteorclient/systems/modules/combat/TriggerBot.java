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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Random;
import java.util.Set;

/** Legit-style attack: only hits what is already under your crosshair, on full cooldown. */
public class TriggerBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities").description("Entities to attack.").onlyAttackable().defaultValue(EntityTypes.PLAYER).build());

    private final Setting<Double> cooldown = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-cooldown").description("Attack once the weapon charge reaches this fraction.").defaultValue(1.0).range(0.1, 1).sliderRange(0.1, 1).build());

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-reaction-ticks").description("Minimum ticks the target must stay under the crosshair before the first hit.").defaultValue(1).range(0, 20).sliderRange(0, 10).build());

    private final Setting<Integer> randomDelay = sgGeneral.add(new IntSetting.Builder()
        .name("random-extra-ticks").description("Random extra reaction ticks added on top.").defaultValue(2).range(0, 20).sliderRange(0, 10).build());

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends").description("Never hit friends.").defaultValue(true).build());

    private final Setting<Boolean> onlyWhenHolding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-holding-attack").description("Only trigger while the attack key is held.").defaultValue(false).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("Don't attack while using an item (eating, blocking).").defaultValue(true).build());

    private final Random random = new Random();
    private Entity lastTarget;
    private int onTargetTicks;
    private int requiredTicks;

    public TriggerBot() {
        super(Categories.Combat, "trigger-bot", "Attacks the entity under your crosshair once your weapon is charged.");
    }

    /** Pure: whether to swing this tick. */
    public static boolean shouldAttack(float charge, double minCharge, int onTargetTicks, int requiredTicks) {
        return charge >= minCharge && onTargetTicks >= requiredTicks;
    }

    @Override
    public void onActivate() {
        lastTarget = null;
        onTargetTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null || mc.gui.screen() != null) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (onlyWhenHolding.get() && !mc.options.keyAttack.isDown()) return;

        Entity target = mc.hitResult instanceof EntityHitResult ehr ? ehr.getEntity() : null;
        if (!valid(target)) {
            lastTarget = null;
            onTargetTicks = 0;
            return;
        }

        if (target != lastTarget) {
            lastTarget = target;
            onTargetTicks = 0;
            requiredTicks = minDelay.get() + (randomDelay.get() > 0 ? random.nextInt(randomDelay.get() + 1) : 0);
        }
        onTargetTicks++;

        if (shouldAttack(mc.player.getAttackStrengthScale(0.5f), cooldown.get(), onTargetTicks, requiredTicks)) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private boolean valid(Entity e) {
        if (e == null || e == mc.player || !e.isAlive()) return false;
        if (!entities.get().contains(e.getType())) return false;
        if (e instanceof LivingEntity le && le.isDeadOrDying()) return false;
        if (ignoreFriends.get() && e instanceof Player p && !Friends.get().shouldAttack(p)) return false;
        return true;
    }
}
