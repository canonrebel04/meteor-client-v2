/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.overlay;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ThreatIndicatorModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range to detect threats.")
        .defaultValue(32.0)
        .min(1)
        .sliderRange(1, 128)
        .build()
    );

    private final Setting<Boolean> onlyMonsters = sgGeneral.add(new BoolSetting.Builder()
        .name("only-monsters")
        .description("Only show indicators for hostile mobs (monsters).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Also show indicators for other players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> triangleSize = sgGeneral.add(new DoubleSetting.Builder()
        .name("triangle-size")
        .description("Size of the threat indicator triangles.")
        .defaultValue(8.0)
        .min(2)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Double> opacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("opacity")
        .description("Opacity of the threat indicators.")
        .defaultValue(0.9)
        .min(0.1)
        .sliderRange(0.1, 1.0)
        .build()
    );

    private final Setting<SettingColor> threatColor = sgColors.add(new ColorSetting.Builder()
        .name("threat-color")
        .description("Color of the threat indicator for monsters.")
        .defaultValue(new SettingColor(255, 50, 50))
        .build()
    );

    private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Color for player threats.")
        .defaultValue(new SettingColor(255, 200, 50))
        .build()
    );

    private final List<ThreatEntry> threats = new ArrayList<>();

    public ThreatIndicatorModule() {
        super(Categories.Combat, "threat-indicator", "Draws screen-edge indicators pointing toward nearby hostile entities.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        threats.clear();
        if (mc.player == null || mc.level == null) return;

        double r = range.get();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity == mc.getCameraEntity()) continue;
            if (!entity.isAlive()) continue;
            if (entity instanceof LivingEntity living && living.isDeadOrDying()) continue;
            if (!isThreat(entity)) continue;
            if (mc.player.distanceTo(entity) > r) continue;

            threats.add(new ThreatEntry(entity));
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (threats.isEmpty() || mc.player == null || mc.getCameraEntity() == null) return;

        double sw = event.screenWidth;
        double sh = event.screenHeight;
        double halfW = sw / 2.0;
        double halfH = sh / 2.0;
        double size = triangleSize.get();
        double margin = size * 2.0;

        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        Vec3 cameraLook = mc.getCameraEntity().getViewVector(1.0f);
        float camYaw = mc.gameRenderer.getMainCamera().yRot();
        float camPitch = mc.gameRenderer.getMainCamera().xRot();

        for (ThreatEntry entry : threats) {
            Entity entity = entry.entity;

            // Direction from camera to entity
            Vec3 toEntity = entity.getEyePosition().subtract(cameraPos).normalize();

            // Calculate horizontal angle difference (yaw)
            double lookYawRad = Math.toRadians(camYaw);
            double toEntityYaw = Math.atan2(-toEntity.x, toEntity.z);
            double yawDiff = angleDiff(toEntityYaw, lookYawRad);

            // Calculate vertical angle difference (pitch)
            double lookPitchRad = Math.toRadians(camPitch);
            double horizontalDist = Math.sqrt(toEntity.x * toEntity.x + toEntity.z * toEntity.z);
            double toEntityPitch = -Math.atan2(toEntity.y, horizontalDist);
            double pitchDiff = toEntityPitch - lookPitchRad;

            // Clamp pitch difference (don't show if too far above/below)
            pitchDiff = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitchDiff));

            // Map to screen coordinates
            // FOV approximation: use horizontal FOV for width, vertical for height
            double hFov = Math.toRadians(90.0); // reasonable horizontal FOV for calculations
            double vFov = hFov * sh / sw;

            double screenX = halfW + (yawDiff / (hFov / 2)) * halfW;
            double screenY = halfH + (pitchDiff / (vFov / 2)) * halfH;

            // Check if actually on screen
            boolean onScreen = screenX >= 0 && screenX <= sw && screenY >= 0 && screenY <= sh;

            // For on-screen targets that are in front of camera, skip (entity is visible)
            if (onScreen) {
                // Check if entity is in front (dot product of look and direction > 0)
                double dot = cameraLook.dot(toEntity);
                if (dot > 0.1) continue;
            }

            // Clamp to screen edge
            screenX = clamp(screenX, margin, sw - margin);
            screenY = clamp(screenY, margin, sh - margin);

            // Angle of the arrow: from screen center toward the clamped position
            double angle = Math.atan2(screenY - halfH, screenX - halfW);

            Color c = isPlayer(entity) ? playerColor.get() : threatColor.get();

            // Apply opacity
            int alpha = (int) (opacity.get() * 255);
            c = new Color(c.r, c.g, c.b, Math.min(c.a, alpha));

            drawTriangle(screenX, screenY, angle, size, c);
        }
    }

    private void drawTriangle(double x, double y, double angle, double size, Color color) {
        double halfSize = size / 2;
        double tipDist = size;

        double tipX = x + Math.cos(angle) * tipDist;
        double tipY = y + Math.sin(angle) * tipDist;

        double baseX1 = x + Math.cos(angle + Math.PI * 0.75) * halfSize;
        double baseY1 = y + Math.sin(angle + Math.PI * 0.75) * halfSize;
        double baseX2 = x + Math.cos(angle - Math.PI * 0.75) * halfSize;
        double baseY2 = y + Math.sin(angle - Math.PI * 0.75) * halfSize;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.triangle(baseX1, baseY1, baseX2, baseY2, tipX, tipY, color);
        Renderer2D.COLOR.render();
    }

    private boolean isThreat(Entity entity) {
        if (isPlayer(entity)) return showPlayers.get();
        if (onlyMonsters.get()) return entity.getType().getCategory() == MobCategory.MONSTER;
        // If not filtering by monster only, show all non-player hostile categories
        MobCategory cat = entity.getType().getCategory();
        return cat == MobCategory.MONSTER;
    }

    private boolean isPlayer(Entity entity) {
        return entity.getType() == net.minecraft.world.entity.EntityType.PLAYER;
    }

    private static double angleDiff(double a, double b) {
        double diff = a - b;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        return diff;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private record ThreatEntry(Entity entity) {}
}