/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.notifications;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class CombatNotificationManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notifyKills = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-kills")
        .description("Notify when you kill something.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyDeaths = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-deaths")
        .description("Notify when you die.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyTargetSwitches = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-target-switches")
        .description("Notify when SmartCombat target changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hudToasts = sgGeneral.add(new BoolSetting.Builder()
        .name("hud-toasts")
        .description("Show HUD toast notifications.")
        .defaultValue(true)
        .build()
    );

    private Entity lastTarget;
    private int killCount;
    private boolean wasDead;
    private final Map<Integer, Boolean> trackedEntities = new HashMap<>();

    public CombatNotificationManager() {
        super(Categories.Combat, "combat-notifications", "Sends chat and HUD notifications for combat events: kills, deaths, and target switches.");
    }

    @Override
    public void onActivate() {
        lastTarget = null;
        killCount = 0;
        wasDead = mc.player != null && (mc.player.isDeadOrDying() || mc.player.getHealth() <= 0);
        trackedEntities.clear();
    }

    @Override
    public void onDeactivate() {
        trackedEntities.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        SmartCombatModule sc = Modules.get().get(SmartCombatModule.class);

        // --- Kill detection via entity tracking ---
        if (notifyKills.get()) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity le
                    && !le.equals(mc.player)
                    && !(le instanceof Player)
                    && mc.player.distanceToSqr(le) <= 36.0) {

                    int id = le.getId();
                    Boolean wasAlive = trackedEntities.get(id);

                    if (wasAlive != null && wasAlive && (!le.isAlive() || le.isDeadOrDying())) {
                        killCount++;
                        String name = le.getName().getString();
                        ChatUtils.info("(highlight)Combat (default)Killed: (highlight)%s (default)[%d]", name, killCount);

                        if (hudToasts.get()) {
                            MeteorToast toast = new MeteorToast.Builder("Kill!")
                                .text(name + " [" + killCount + "]")
                                .icon(Items.DIAMOND_SWORD)
                                .build();
                            mc.getToastManager().addToast(toast);
                        }
                    }

                    trackedEntities.put(id, le.isAlive() && !le.isDeadOrDying());
                }
            }
        }

        // --- Target switch detection ---
        if (notifyTargetSwitches.get() && sc.isActive()) {
            Entity currentTarget = sc.getTarget();
            if (currentTarget != null && !currentTarget.equals(lastTarget)) {
                String name = currentTarget instanceof LivingEntity living
                    ? living.getName().getString()
                    : currentTarget.getType().getDescription().getString();
                ChatUtils.info("(highlight)Combat (default)Target switched to: (highlight)%s", name);

                if (hudToasts.get()) {
                    MeteorToast toast = new MeteorToast.Builder("Target Switched")
                        .text(name)
                        .icon(Items.ENDER_EYE)
                        .build();
                    mc.getToastManager().addToast(toast);
                }
            }
            lastTarget = currentTarget;
        }

        // --- Death detection ---
        if (notifyDeaths.get()) {
            boolean isDead = mc.player.isDeadOrDying() || mc.player.getHealth() <= 0;
            if (isDead && !wasDead) {
                ChatUtils.info("(highlight)Combat (default)You died. (highlight)%d (default)total kills.", killCount);

                if (hudToasts.get()) {
                    MeteorToast toast = new MeteorToast.Builder("You Died")
                        .text("Total kills: " + killCount)
                        .icon(Items.WITHER_ROSE)
                        .build();
                    mc.getToastManager().addToast(toast);
                }
            }
            wasDead = isDead;
        }
    }
}