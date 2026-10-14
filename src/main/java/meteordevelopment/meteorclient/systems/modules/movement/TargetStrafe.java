/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;

/** Circles the current KillAura target. Runs after Speed so it can reuse Speed's horizontal velocity. */
public class TargetStrafe extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Orbit radius around the target.")
        .defaultValue(2.5)
        .range(0.5, 6)
        .sliderRange(0.5, 6)
        .build()
    );

    private final Setting<Double> maxRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Only strafe when the target is within this distance.")
        .defaultValue(6)
        .range(1, 16)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Boolean> useCurrentSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("use-current-speed")
        .description("Keep your current horizontal speed (works with Speed). Off = use the speed setting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Blocks per tick when not using current speed.")
        .defaultValue(0.28)
        .range(0.05, 1)
        .sliderRange(0.05, 1)
        .visible(() -> !useCurrentSpeed.get())
        .build()
    );

    private final Setting<Boolean> requireJump = sgGeneral.add(new BoolSetting.Builder()
        .name("require-jump-key")
        .description("Only strafe while holding jump.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> switchOnCollision = sgGeneral.add(new BoolSetting.Builder()
        .name("switch-on-collision")
        .description("Reverse direction when hitting a wall.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> manualSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("strafe-keys-switch")
        .description("Holding left/right strafe keys picks the direction.")
        .defaultValue(true)
        .build()
    );

    private int direction = 1;

    public TargetStrafe() {
        super(Categories.Movement, "target-strafe", "Circles around your KillAura target.");
    }

    @Override
    public void onActivate() {
        direction = 1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (switchOnCollision.get() && mc.player.horizontalCollision) direction = -direction;
        if (manualSwitch.get()) {
            if (mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown()) direction = 1;
            else if (mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown()) direction = -1;
        }
    }

    private Entity target() {
        KillAura ka = Modules.get().get(KillAura.class);
        if (!ka.isActive()) return null;
        Entity t = ka.getTarget();
        if (t == null || !t.isAlive()) return null;
        if (mc.player.distanceTo(t) > maxRange.get()) return null;
        return t;
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onMove(PlayerMoveEvent event) {
        if (event.type != MoverType.SELF || mc.player == null) return;
        if (mc.player.isFallFlying() || mc.player.isPassenger()) return;
        if (requireJump.get() && !mc.options.keyJump.isDown()) return;
        Entity t = target();
        if (t == null) return;

        double s = useCurrentSpeed.get()
            ? Math.max(Math.hypot(event.movement.x, event.movement.z), 0.2)
            : speed.get();
        double[] v = TargetStrafeMath.orbit(mc.player.getX(), mc.player.getZ(), t.getX(), t.getZ(), radius.get(), direction, s);
        ((IVec3) event.movement).meteor$set(v[0], event.movement.y, v[1]);
    }
}
