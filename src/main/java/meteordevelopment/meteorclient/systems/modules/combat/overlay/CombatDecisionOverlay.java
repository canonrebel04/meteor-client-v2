/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.overlay;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule;
import meteordevelopment.meteorclient.systems.modules.combat.TacticalBrain;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;

public class CombatDecisionOverlay extends Module {
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color GRAY = new Color(175, 175, 175);
    private static final Color BACKGROUND = new Color(0, 0, 0, 100);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the overlay text.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    private final Setting<Integer> padding = sgGeneral.add(new IntSetting.Builder()
        .name("padding")
        .description("Padding around the overlay box.")
        .defaultValue(4)
        .min(0)
        .max(20)
        .build()
    );

    private final Setting<SettingColor> bgColor = sgColors.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color for the overlay.")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build()
    );

    private final Setting<SettingColor> titleColor = sgColors.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Title text color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> infoColor = sgColors.add(new ColorSetting.Builder()
        .name("info-color")
        .description("Info text color.")
        .defaultValue(new SettingColor(175, 175, 175))
        .build()
    );

    public CombatDecisionOverlay() {
        super(Categories.Combat, "combat-decision-overlay", "Shows AI decision state, target info, and timers for the SmartCombat system.");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        SmartCombatModule sc = Modules.get().get(SmartCombatModule.class);
        TacticalBrain brain = Modules.get().get(TacticalBrain.class);

        if (sc == null) return;

        // Build text lines
        String title = "Combat Decision";
        String mode = "Mode: " + (sc.isActive() ? sc.getCombatMode().name() : "DISABLED");
        String status = "Status: " + (sc.isActive() ? "Running" : "Idle");

        String targetLine;
        Entity target = sc.getTarget();
        if (target != null && mc.player != null) {
            double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
            targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
        } else {
            targetLine = "Target: none";
        }

        String timerLine = "Attack in: " + sc.getAttackTimer() + " ticks";
        String rangeLine = "Range: " + sc.getRange() + "m";

        String brainAction = "AI Action: " + (brain != null && brain.isActive() ? brain.getInfoString() : "N/A");

        TextRenderer text = TextRenderer.get();
        text.begin(scale.get());

        double lineHeight = text.getHeight() + 2;
        double pad = padding.get();

        // Calculate width
        double width = 0;
        width = Math.max(width, text.getWidth(title));
        width = Math.max(width, text.getWidth(mode));
        width = Math.max(width, text.getWidth(status));
        width = Math.max(width, text.getWidth(targetLine));
        width = Math.max(width, text.getWidth(timerLine));
        width = Math.max(width, text.getWidth(rangeLine));
        width = Math.max(width, text.getWidth(brainAction));

        double boxWidth = width + pad * 2;
        double boxHeight = lineHeight * 7 + pad * 2;

        // Position top-right
        double boxX = event.screenWidth - boxWidth - 2;
        double boxY = 2;

        // Draw background
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(boxX, boxY, boxWidth, boxHeight, bgColor.get());
        Renderer2D.COLOR.render();

        // Draw text
        double textX = boxX + pad;
        double textY = boxY + pad;
        double line = lineHeight;

        text.render(title, textX, textY, titleColor.get());
        textY += line;

        text.render(mode, textX, textY, infoColor.get());
        textY += line;

        text.render(status, textX, textY, infoColor.get());
        textY += line;

        text.render(targetLine, textX, textY, WHITE);
        textY += line;

        text.render(timerLine, textX, textY, infoColor.get());
        textY += line;

        text.render(rangeLine, textX, textY, infoColor.get());
        textY += line;

        text.render(brainAction, textX, textY, WHITE);

        text.end();
    }
}
