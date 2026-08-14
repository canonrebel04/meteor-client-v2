/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import net.minecraft.world.entity.Entity;

public class SmartCombatHud extends HudElement {
    private static final Color GREEN = new Color(15, 255, 15);
    private static final Color RED = new Color(255, 15, 15);

    public static final HudElementInfo<SmartCombatHud> INFO = new HudElementInfo<>(Hud.GROUP, "smart-combat", "Displays SmartCombat state: current target, mode, and timers.", SmartCombatHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScale = settings.createGroup("Scale");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    // General

    private final Setting<SettingColor> primaryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("primary-color")
        .description("Primary text color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> secondaryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("secondary-color")
        .description("Secondary text color for labels.")
        .defaultValue(new SettingColor(175, 175, 175))
        .build()
    );

    private final Setting<SettingColor> activeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("active-color")
        .description("Color for 'Enabled' status text.")
        .defaultValue(new SettingColor(15, 255, 15))
        .build()
    );

    private final Setting<SettingColor> inactiveColor = sgGeneral.add(new ColorSetting.Builder()
        .name("inactive-color")
        .description("Color for 'Disabled' status text.")
        .defaultValue(new SettingColor(255, 15, 15))
        .build()
    );

    // Scale

    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies a custom scale.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    // Background

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays background.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color used for the background.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    public SmartCombatHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);

        if (cb == null && module == null) {
            setSize(renderer.textWidth("SmartCombat", false, getScale()), renderer.textHeight(false, getScale()));
            return;
        }

        String title = (cb != null && cb.isActive()) ? "CombatBrain" : "SmartCombat";
        String status = (cb != null && cb.isActive()) ? "Enabled" : (module != null && module.isActive() ? "Enabled" : "Disabled");
        String mode = "Mode: " + ((cb != null && cb.isActive()) ? cb.getCombatMode().name() : (module != null ? module.getCombatStrategy().name() : "N/A"));
        String targetLine = "Target: none";
        String timerLine = "Timer: --";

        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
            timerLine = "Attack in: 5 ticks";
        } else if (cb != null && cb.isActive()) {
            targetLine = "Brain: " + cb.getInfoString();
            timerLine = "State: Active";
        } else if (module != null) {
            Entity target = module.getTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            }
            timerLine = "Attack in: " + module.getAttackTimer() + " ticks";
        }

        double width = 0;
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        width = Math.max(width, renderer.textWidth(title, false, getScale()));
        width = Math.max(width, renderer.textWidth(status, false, getScale()));
        width = Math.max(width, renderer.textWidth(mode, false, getScale()));
        width = Math.max(width, renderer.textWidth(targetLine, false, getScale()));
        width = Math.max(width, renderer.textWidth(timerLine, false, getScale()));

        double height = 5 * lineHeight;

        setSize(width, height);
    }

    @Override
    public void render(HudRenderer renderer) {
        double x = this.x;
        double y = this.y + 2;

        if (background.get()) {
            renderer.quad(this.x, this.y, getWidth(), getHeight(), backgroundColor.get());
        }

        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        String title = (cb != null && cb.isActive()) ? "CombatBrain" : "SmartCombat";
        renderer.text(title, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        if (cb == null && module == null) {
            renderer.text("Module not loaded", x, y, RED, false, getScale());
            return;
        }

        // Status
        boolean active = (cb != null && cb.isActive()) || (module != null && module.isActive());
        Color statusColor = active ? activeColor.get() : inactiveColor.get();
        String status = active ? "Enabled" : "Disabled";
        renderer.text(status, x, y, statusColor, false, getScale());
        y += lineHeight;

        // Mode
        String modeName = (cb != null && cb.isActive()) ? cb.getCombatMode().name() : (module != null ? module.getCombatStrategy().name() : "N/A");
        renderer.text("Mode: " + modeName, x, y, secondaryColor.get(), false, getScale());
        y += lineHeight;

        // Target / Brain line
        String targetLine;
        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
        } else if (cb != null && cb.isActive()) {
            targetLine = "Brain: " + cb.getInfoString();
        } else if (module != null) {
            Entity target = module.getTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            } else {
                targetLine = "Target: none";
            }
        } else {
            targetLine = "Target: none";
        }
        renderer.text(targetLine, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        // Timer / State line
        String timerLine;
        if (isInEditor()) {
            timerLine = "Attack in: 5 ticks";
        } else if (cb != null && cb.isActive()) {
            timerLine = "State: Active";
        } else if (module != null) {
            timerLine = "Attack in: " + module.getAttackTimer() + " ticks";
        } else {
            timerLine = "Timer: --";
        }
        renderer.text(timerLine, x, y, secondaryColor.get(), false, getScale());
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }
}