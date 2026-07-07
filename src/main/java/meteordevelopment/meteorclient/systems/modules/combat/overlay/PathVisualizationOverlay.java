/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.overlay;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.combat.CombatIntegrationBridge;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;

public class PathVisualizationOverlay extends Module {
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color BACKGROUND = new Color(0, 0, 0, 100);
    private static final Color LINE_COLOR = new Color(0, 255, 200, 200);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Double> textScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("text-scale")
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

    private final Setting<Boolean> draw3DLine = sgGeneral.add(new BoolSetting.Builder()
        .name("draw-3d-line")
        .description("Draws a 3D line from the player to the goal position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgColors.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the 3D path line.")
        .defaultValue(new SettingColor(0, 255, 200))
        .build()
    );

    private final Setting<SettingColor> bgColor = sgColors.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color for the overlay text.")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .build()
    );

    private final Setting<SettingColor> textColor = sgColors.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Text color for the overlay.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private CombatIntegrationBridge bridge;

    public PathVisualizationOverlay() {
        super(Categories.Combat, "path-visualization-overlay", "Renders the current Baritone combat path goal as a 3D line and text overlay.");
    }

    @Override
    public void onActivate() {
        bridge = new CombatIntegrationBridge();
    }

    @Override
    public void onDeactivate() {
        bridge = null;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!draw3DLine.get() || bridge == null || mc.player == null) return;

        Goal goal = bridge.getCurrentGoal();
        if (goal == null) return;

        BlockPos goalPos = extractGoalPos(goal);
        if (goalPos == null) return;

        double px = mc.player.getX();
        double py = mc.player.getY() + mc.player.getEyeHeight();
        double pz = mc.player.getZ();

        double gx = goalPos.getX() + 0.5;
        double gy = goalPos.getY() + 0.5;
        double gz = goalPos.getZ() + 0.5;

        Color c = lineColor.get();
        event.renderer.line(px, py, pz, gx, gy, gz, c);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (bridge == null || mc.player == null) return;

        Goal goal = bridge.getCurrentGoal();
        if (goal == null) return;

        BlockPos goalPos = extractGoalPos(goal);
        if (goalPos == null) return;

        double dist = Math.sqrt(mc.player.distanceToSqr(goalPos.getX() + 0.5, goalPos.getY() + 0.5, goalPos.getZ() + 0.5));
        String distStr = String.format("%.1f", dist);

        String line1 = "Path Goal: " + goalPos.getX() + ", " + goalPos.getY() + ", " + goalPos.getZ();
        String line2 = "Distance: " + distStr + " blocks";

        TextRenderer text = TextRenderer.get();
        text.begin(textScale.get());

        double lineHeight = text.getHeight() + 2;
        double pad = padding.get();

        double width = Math.max(text.getWidth(line1), text.getWidth(line2));
        double boxWidth = width + pad * 2;
        double boxHeight = lineHeight * 2 + pad * 2;

        // Position top-right
        double boxX = event.screenWidth - boxWidth - 2;
        double boxY = 2;

        // Background
        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(boxX, boxY, boxWidth, boxHeight, bgColor.get());
        Renderer2D.COLOR.render();

        // Text
        double textX = boxX + pad;
        double textY = boxY + pad;

        text.render(line1, textX, textY, textColor.get());
        textY += lineHeight;
        text.render(line2, textX, textY, WHITE);

        text.end();
    }

    private BlockPos extractGoalPos(Goal goal) {
        if (goal instanceof GoalBlock gb) {
            return new BlockPos(gb.x, gb.y, gb.z);
        }
        if (goal instanceof GoalNear gn) {
            return gn.getGoalPos();
        }
        if (goal instanceof GoalXZ gxz) {
            return new BlockPos(gxz.getX(), mc.player != null ? (int) mc.player.getY() : 64, gxz.getZ());
        }
        return null;
    }
}