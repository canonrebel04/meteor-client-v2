/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Makes every hit a sprint hit (extra knockback). Packet mode re-sends the sprint start before the
 * attack; W-Tap mode briefly releases forward after a hit, like a manual W-tap.
 */
public class SuperKnockback extends Module {
    public enum Mode { Packet, WTap }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Packet: resend sprint before each hit. WTap: release forward briefly after a hit.").defaultValue(Mode.Packet).build());

    private final Setting<Integer> wtapTicks = sgGeneral.add(new IntSetting.Builder()
        .name("wtap-ticks").description("Ticks to release forward in W-Tap mode.").defaultValue(2).range(1, 6).sliderRange(1, 6).visible(() -> mode.get() == Mode.WTap).build());

    private final Setting<Boolean> onlyWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-moving").description("Only apply while moving forward.").defaultValue(true).build());

    private int releaseTicks;

    public SuperKnockback() {
        super(Categories.Combat, "super-knockback", "Every melee hit deals sprint knockback.");
    }

    /** Pure: sprint packets to send before an attack in Packet mode. */
    public static List<ServerboundPlayerCommandPacket.Action> packetSequence(boolean sprinting) {
        return sprinting
            ? List.of(ServerboundPlayerCommandPacket.Action.STOP_SPRINTING, ServerboundPlayerCommandPacket.Action.START_SPRINTING)
            : List.of(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
    }

    @Override
    public void onDeactivate() {
        if (releaseTicks > 0 && mc.options != null) mc.options.keyUp.setDown(isForwardPhysicallyDown());
        releaseTicks = 0;
    }

    private boolean isForwardPhysicallyDown() {
        return meteordevelopment.meteorclient.utils.misc.input.Input.isPressed(mc.options.keyUp);
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || mc.getConnection() == null || !(event.entity instanceof LivingEntity)) return;
        if (onlyWhenMoving.get() && mc.player.zza <= 0) return;

        if (mode.get() == Mode.Packet) {
            for (ServerboundPlayerCommandPacket.Action a : packetSequence(mc.player.isSprinting())) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, a));
            }
            mc.player.setSprinting(true);
        } else {
            releaseTicks = wtapTicks.get();
            mc.options.keyUp.setDown(false);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (releaseTicks <= 0) return;
        if (--releaseTicks > 0) mc.options.keyUp.setDown(false);
        else mc.options.keyUp.setDown(isForwardPhysicallyDown());
    }
}
