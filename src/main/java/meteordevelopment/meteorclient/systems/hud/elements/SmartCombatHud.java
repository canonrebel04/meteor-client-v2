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

        if (cb == null) {
            setSize(renderer.textWidth("Combat Mode", false, getScale()), renderer.textHeight(false, getScale()));
            return;
        }

        String title = "Combat Mode";
        String status = cb.isActive() ? "Enabled" : "Disabled";
        String mode = "Mode: " + (cb.isActive() ? cb.getCombatMode().name() : "N/A");
        String targetLine = "Target: none";
        String stateLine = "State: " + (cb.isActive() ? cb.getState().name() : "IDLE");

        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
            stateLine = "State: ENGAGING (STRIKE)";
        } else if (cb.isActive()) {
            Entity target = cb.getCurrentTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            }
            stateLine = "State: " + cb.getState().name() + " (" + cb.getStrikePhase().name() + ")";
        }

        double width = 0;
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        width = Math.max(width, renderer.textWidth(title, false, getScale()));
        width = Math.max(width, renderer.textWidth(status, false, getScale()));
        width = Math.max(width, renderer.textWidth(mode, false, getScale()));
        width = Math.max(width, renderer.textWidth(targetLine, false, getScale()));
        width = Math.max(width, renderer.textWidth(stateLine, false, getScale()));

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
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        String title = "Combat Mode";
        renderer.text(title, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        if (cb == null) {
            renderer.text("Module not loaded", x, y, RED, false, getScale());
            return;
        }

        // Status
        boolean active = cb.isActive();
        Color statusColor = active ? activeColor.get() : inactiveColor.get();
        String status = active ? "Enabled" : "Disabled";
        renderer.text(status, x, y, statusColor, false, getScale());
        y += lineHeight;

        // Mode
        String modeName = active ? cb.getCombatMode().name() : "N/A";
        renderer.text("Mode: " + modeName, x, y, secondaryColor.get(), false, getScale());
        y += lineHeight;

        // Target
        String targetLine;
        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
        } else if (active) {
            Entity target = cb.getCurrentTarget();
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

        // State / Phase
        String stateLine;
        if (isInEditor()) {
            stateLine = "State: ENGAGING (STRIKE)";
        } else if (active) {
            stateLine = "State: " + cb.getState().name() + " (" + cb.getStrikePhase().name() + ")";
        } else {
            stateLine = "State: IDLE";
        }
        renderer.text(stateLine, x, y, secondaryColor.get(), false, getScale());
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }
}