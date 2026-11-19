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
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import meteordevelopment.meteorclient.utils.render.anim.Anim;
import meteordevelopment.meteorclient.utils.render.anim.Tween;
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

    private final Setting<Boolean> animate = sgRender.add(new BoolSetting.Builder()
        .name("animate")
        .description("Fade in on a new target and pulse while striking (off with reduce-motion or low frame rate).")
        .defaultValue(true)
        .build()
    );

    private final Tween fadeIn = new Tween(Anim.NORMAL);
    private Entity shownTarget;
    private long lastFrame;
    private final Color sideColor = new Color();
    private final Color lineColor = new Color();

    private Entity target;
    private boolean attacking;
    private boolean inRange;

    public TargetESP() {
        super(Categories.Combat, "target-esp", "Renders an ESP box around the SmartCombat module's current target.", "brain-target-esp", "target-highlight");
    }

    @Override
    public void onDeactivate() {
        target = null;
        attacking = false;
        inRange = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        if (cb == null || !cb.isActive()) {
            target = null;
            attacking = false;
            inRange = false;
            return;
        }

        target = cb.getCurrentTarget();
        attacking = cb.getStrikePhase() == CombatBrainModule.StrikePhase.STRIKE;
        inRange = target != null && mc.player != null && mc.player.distanceTo(target) <= 4.5;
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

        double alpha = 1;
        if (animate.get() && Anim.quality >= 0.75) {
            long now = java.lang.System.nanoTime();
            double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
            lastFrame = now;
            if (target != shownTarget) {
                shownTarget = target;
                fadeIn.set(0);
            }
            fadeIn.target(1);
            alpha = fadeIn.update(dt);
            if (attacking) alpha *= 0.65 + 0.35 * Anim.pulse(now / 1e9, 0.6);
        }
        sideColor.set(color).a((int) (color.a * alpha));
        lineColor.set(color).a((int) (255 * Math.max(0.3, alpha)));

        double x = Mth.lerp(event.tickDelta, target.xOld, target.getX()) - target.getX();
        double y = Mth.lerp(event.tickDelta, target.yOld, target.getY()) - target.getY();
        double z = Mth.lerp(event.tickDelta, target.zOld, target.getZ()) - target.getZ();

        AABB box = target.getBoundingBox();
        event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColor, lineColor, shapeMode.get(), 0);
    }
}
