/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.overlay;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public class TargetESP extends Module {
    private static final Color GREEN = new Color(15, 255, 15, 180);
    private static final Color RED = new Color(255, 15, 15, 180);
    private static final Color YELLOW = new Color(255, 255, 15, 180);

    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the ESP box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> targetedColor = sgRender.add(new ColorSetting.Builder()
        .name("targeted-color")
        .description("Color when a target is selected and in range.")
        .defaultValue(new SettingColor(15, 255, 15, 100))
        .build()
    );

    private final Setting<SettingColor> attackingColor = sgRender.add(new ColorSetting.Builder()
        .name("attacking-color")
        .description("Color when actively attacking the target.")
        .defaultValue(new SettingColor(255, 15, 15, 100))
        .build()
    );

    private final Setting<SettingColor> trackingColor = sgRender.add(new ColorSetting.Builder()
        .name("tracking-color")
        .description("Color when tracking the target but out of range.")
        .defaultValue(new SettingColor(255, 255, 15, 100))
        .build()
    );

    private final Color sideColor = new Color();
    private final Color lineColor = new Color();

    private Entity target;
    private boolean attacking;
    private boolean inRange;

    public TargetESP() {
        super(Categories.Combat, "target-esp", "Renders an ESP box around the SmartCombat module's current target.");
    }

    @Override
    public void onDeactivate() {
        target = null;
        attacking = false;
        inRange = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        SmartCombatModule sc = Modules.get().get(SmartCombatModule.class);
        if (sc == null || !sc.isActive()) {
            target = null;
            attacking = false;
            inRange = false;
            return;
        }

        target = sc.getTarget();
        attacking = sc.getAttackTimer() > 0;
        inRange = target != null && mc.player != null && mc.player.distanceTo(target) <= sc.getRange();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (target == null) return;

        Color color;
        if (attacking) {
            color = attackingColor.get();
        } else if (inRange) {
            color = targetedColor.get();
        } else {
            color = trackingColor.get();
        }

        sideColor.set(color);
        lineColor.set(color).a(255);

        double x = Mth.lerp(event.tickDelta, target.xOld, target.getX()) - target.getX();
        double y = Mth.lerp(event.tickDelta, target.yOld, target.getY()) - target.getY();
        double z = Mth.lerp(event.tickDelta, target.zOld, target.getZ()) - target.getZ();

        AABB box = target.getBoundingBox();
        event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColor, lineColor, shapeMode.get(), 0);
    }
}
