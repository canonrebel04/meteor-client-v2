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
        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
        if (module == null) {
            setSize(renderer.textWidth("SmartCombat", false, getScale()), renderer.textHeight(false, getScale()));
            return;
        }

        String title = "SmartCombat";
        String status = module.isActive() ? "Enabled" : "Disabled";
        String mode = "Mode: " + module.getCombatMode().name();
        String targetLine = "Target: none";
        String timerLine = "Timer: --";

        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
            timerLine = "Attack in: 5 ticks";
        } else {
            Entity target = module.getTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            }
            timerLine = "Attack in: " + module.getAttackTimer() + " ticks";
        }

        double width = 0;
        double height = 0;
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        width = Math.max(width, renderer.textWidth(title, false, getScale()));
        width = Math.max(width, renderer.textWidth(status, false, getScale()));
        width = Math.max(width, renderer.textWidth(mode, false, getScale()));
        width = Math.max(width, renderer.textWidth(targetLine, false, getScale()));
        width = Math.max(width, renderer.textWidth(timerLine, false, getScale()));

        height = 5 * lineHeight;

        setSize(width, height);
    }

    @Override
    public void render(HudRenderer renderer) {
        double x = this.x;
        double y = this.y + 2;

        if (background.get()) {
            renderer.quad(this.x, this.y, getWidth(), getHeight(), backgroundColor.get());
        }

        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        // Title
        renderer.text("SmartCombat", x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        if (module == null) {
            renderer.text("Module not loaded", x, y, RED, false, getScale());
            return;
        }

        // Status
        Color statusColor = module.isActive() ? activeColor.get() : inactiveColor.get();
        String status = module.isActive() ? "Enabled" : "Disabled";
        renderer.text(status, x, y, statusColor, false, getScale());
        y += lineHeight;

        // Mode
        renderer.text("Mode: " + module.getCombatMode().name(), x, y, secondaryColor.get(), false, getScale());
        y += lineHeight;

        // Target
        String targetLine;
        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
        } else {
            Entity target = module.getTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            } else {
                targetLine = "Target: none";
            }
        }
        renderer.text(targetLine, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        // Timer
        String timerLine;
        if (isInEditor()) {
            timerLine = "Attack in: 5 ticks";
        } else {
            timerLine = "Attack in: " + module.getAttackTimer() + " ticks";
        }
        renderer.text(timerLine, x, y, secondaryColor.get(), false, getScale());
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }
}