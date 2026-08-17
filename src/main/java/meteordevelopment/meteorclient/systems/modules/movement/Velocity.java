/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Combat velocity upgrade: Grim damage-window bypass (Cancel / CancelWithMask), JumpReset mode,
 * Lag mode, reduction chance, and PauseOnFlag auto-backoff. Grim bypass pattern ported from
 * Huntress Client (GPL-3.0, JBWolfFlow/huntress-hacked-client).
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientboundSetEntityMotionPacketAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class Velocity extends Module {
    public enum GrimMode {
        Off,
        Cancel,
        CancelWithMask
    }

    public enum JumpResetMode {
        Off,
        OnHit,
        ByHits
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBypass = settings.createGroup("Bypass");

    public final Setting<Boolean> knockback = sgGeneral.add(new BoolSetting.Builder()
        .name("knockback")
        .description("Modifies the amount of knockback you take from attacks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> knockbackHorizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("knockback-horizontal")
        .description("How much horizontal knockback you will take.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(knockback::get)
        .build()
    );

    public final Setting<Double> knockbackVertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("knockback-vertical")
        .description("How much vertical knockback you will take.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(knockback::get)
        .build()
    );

    public final Setting<Boolean> explosions = sgGeneral.add(new BoolSetting.Builder()
        .name("explosions")
        .description("Modifies your knockback from explosions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> explosionsHorizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("explosions-horizontal")
        .description("How much velocity you will take from explosions horizontally.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(explosions::get)
        .build()
    );

    public final Setting<Double> explosionsVertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("explosions-vertical")
        .description("How much velocity you will take from explosions vertically.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(explosions::get)
        .build()
    );

    public final Setting<Boolean> liquids = sgGeneral.add(new BoolSetting.Builder()
        .name("liquids")
        .description("Modifies the amount you are pushed by flowing liquids.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> liquidsHorizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("liquids-horizontal")
        .description("How much velocity you will take from liquids horizontally.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(liquids::get)
        .build()
    );

    public final Setting<Double> liquidsVertical = sgGeneral.add(new DoubleSetting.Builder()
        .name("liquids-vertical")
        .description("How much velocity you will take from liquids vertically.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(liquids::get)
        .build()
    );

    public final Setting<Boolean> entityPush = sgGeneral.add(new BoolSetting.Builder()
        .name("entity-push")
        .description("Modifies the amount you are pushed by entities.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> entityPushAmount = sgGeneral.add(new DoubleSetting.Builder()
        .name("entity-push-amount")
        .description("How much you will be pushed.")
        .defaultValue(0)
        .sliderMax(1)
        .visible(entityPush::get)
        .build()
    );

    public final Setting<Boolean> blocks = sgGeneral.add(new BoolSetting.Builder()
        .name("blocks")
        .description("Prevents you from being pushed out of blocks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> sinking = sgGeneral.add(new BoolSetting.Builder()
        .name("sinking")
        .description("Prevents you from sinking in liquids.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> fishing = sgGeneral.add(new BoolSetting.Builder()
        .name("fishing")
        .description("Prevents you from being pulled by fishing rods.")
        .defaultValue(false)
        .build()
    );

    // -- Bypass group --

    public final Setting<Integer> chance = sgBypass.add(new IntSetting.Builder()
        .name("chance")
        .description("Percentage chance to actually reduce the velocity. Lower = less pattern.")
        .defaultValue(100)
        .range(1, 100)
        .sliderRange(25, 100)
        .build()
    );

    public final Setting<GrimMode> grimMode = sgBypass.add(new EnumSetting.Builder<GrimMode>()
        .name("grim-mode")
        .description("Grim anti-cheat velocity bypass. Cancel removes the knockback packet inside Grim's post-damage window; CancelWithMask also sends masking packets.")
        .defaultValue(GrimMode.Off)
        .build()
    );

    public final Setting<Integer> grimDelay = sgBypass.add(new IntSetting.Builder()
        .name("grim-delay")
        .description("Ticks to wait before sending the masking packets after cancelling.")
        .defaultValue(1)
        .min(0)
        .max(5)
        .visible(() -> grimMode.get() == GrimMode.CancelWithMask)
        .build()
    );

    public final Setting<JumpResetMode> jumpReset = sgBypass.add(new EnumSetting.Builder<JumpResetMode>()
        .name("jump-reset")
        .description("Abuses the vanilla jump knockback-negation mechanic. OnHit jumps on every hit, ByHits jumps every Nth hit.")
        .defaultValue(JumpResetMode.Off)
        .build()
    );

    public final Setting<Integer> jumpResetChance = sgBypass.add(new IntSetting.Builder()
        .name("jump-reset-chance")
        .description("Chance in percent to perform the jump reset.")
        .defaultValue(100)
        .range(1, 100)
        .sliderRange(25, 100)
        .visible(() -> jumpReset.get() != JumpResetMode.Off)
        .build()
    );

    public final Setting<Integer> jumpResetHits = sgBypass.add(new IntSetting.Builder()
        .name("jump-reset-hits")
        .description("Jump every Nth received hit.")
        .defaultValue(2)
        .range(1, 10)
        .sliderRange(1, 5)
        .visible(() -> jumpReset.get() == JumpResetMode.ByHits)
        .build()
    );

    public final Setting<Integer> jumpResetDelay = sgBypass.add(new IntSetting.Builder()
        .name("jump-reset-delay")
        .description("Randomized tick delay before jumping (0 = instant). Randomization breaks pattern detection.")
        .defaultValue(1)
        .range(0, 5)
        .visible(() -> jumpReset.get() != JumpResetMode.Off)
        .build()
    );

    public final Setting<Integer> lagTicks = sgBypass.add(new IntSetting.Builder()
        .name("lag-ticks")
        .description("Hold incoming knockback for this many ticks before releasing it (Blink-style delayed velocity). 0 = off.")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    public final Setting<Boolean> pauseOnFlag = sgBypass.add(new BoolSetting.Builder()
        .name("pause-on-flag")
        .description("Pauses all velocity handling for 2 seconds after the server corrects your position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pauseTicks = sgBypass.add(new IntSetting.Builder()
        .name("pause-ticks")
        .description("How long to pause after a flag.")
        .defaultValue(40)
        .range(5, 200)
        .visible(pauseOnFlag::get)
        .build()
    );

    private final Random random = new Random();

    // Grim damage window
    private boolean canCancelVelocity = false;
    private int damageTickCounter = 0;

    // Jump reset scheduling
    private int jumpHoldTicks = 0;
    private int jumpDelayTicks = 0;

    // Lag scheduling
    private int lagTicksLeft = 0;
    private Vec3 lagVelocity = null;

    // Mask packet deferral
    private int maskDelayTicks = 0;

    // Pause on flag
    private int flagPauseTicks = 0;

    // Hit counting for ByHits
    private int hitsReceived = 0;

    public Velocity() {
        super(Categories.Movement, "velocity", "Prevents you from being moved by external forces.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
        releaseKeys();
    }

    private void resetState() {
        canCancelVelocity = false;
        damageTickCounter = 0;
        jumpHoldTicks = 0;
        jumpDelayTicks = 0;
        lagTicksLeft = 0;
        lagVelocity = null;
        maskDelayTicks = 0;
        flagPauseTicks = 0;
        hitsReceived = 0;
    }

    private void releaseKeys() {
        if (mc.options != null && mc.options.keyJump.isDown() && jumpHoldTicks <= 0) {
            mc.options.keyJump.setDown(false);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        // Sinking behavior (legacy)
        if (sinking.get()) {
            if (!(mc.options.keyJump.isDown() || mc.options.keyShift.isDown())
                && (mc.player.isInWater() || mc.player.isInLava())
                && mc.player.getDeltaMovement().y < 0) {
                ((IVec3) mc.player.getDeltaMovement()).meteor$setY(0);
            }
        }

        // Grim damage window expiry
        if (canCancelVelocity) {
            damageTickCounter++;
            if (damageTickCounter > 5) {
                canCancelVelocity = false;
                damageTickCounter = 0;
            }
        }

        // Flag pause countdown
        if (flagPauseTicks > 0) flagPauseTicks--;

        // Jump reset scheduling
        if (jumpDelayTicks > 0) {
            jumpDelayTicks--;
            if (jumpDelayTicks == 0) {
                if (mc.player.onGround()) {
                    jumpHoldTicks = 2;
                }
                // Airborne: drop the reset (airborne kb can't be jump-negated)
            }
        }
        if (jumpHoldTicks > 0) {
            mc.options.keyJump.setDown(true);
            jumpHoldTicks--;
            if (jumpHoldTicks == 0) mc.options.keyJump.setDown(false);
        }

        // Lag release
        if (lagTicksLeft > 0) {
            lagTicksLeft--;
            if (lagTicksLeft == 0 && lagVelocity != null) {
                mc.player.setDeltaMovement(lagVelocity);
                lagVelocity = null;
            }
        }

        // Deferred mask packets
        if (maskDelayTicks > 0) {
            maskDelayTicks--;
            if (maskDelayTicks == 0) sendMaskPacketsNow();
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;

        // Track Grim's post-damage window: a hurt animation for the player precedes the velocity packet.
        if (grimMode.get() != GrimMode.Off && event.packet instanceof ClientboundHurtAnimationPacket packet) {
            if (packet.id() == mc.player.getId()) {
                canCancelVelocity = true;
                damageTickCounter = 0;
            }
        }

        // PauseOnFlag: server position correction = suspected flag
        if (pauseOnFlag.get() && event.packet instanceof ClientboundPlayerPositionPacket) {
            flagPauseTicks = pauseTicks.get();
        }

        if (knockback.get() && event.packet instanceof ClientboundSetEntityMotionPacket packet
            && packet.id() == mc.player.getId()) {

            if (flagPauseTicks > 0) return;

            // Grim bypass: cancel the velocity inside the damage window
            if (grimMode.get() != GrimMode.Off && canCancelVelocity) {
                event.cancel();
                canCancelVelocity = false;
                damageTickCounter = 0;

                if (grimMode.get() == GrimMode.CancelWithMask) {
                    sendMaskPackets();
                }
                return;
            }

            // Jump reset: don't touch the packet, just schedule the jump (vanilla mechanic)
            if (jumpReset.get() != JumpResetMode.Off) {
                hitsReceived++;
                if (jumpDelayTicks == 0 && jumpHoldTicks == 0 && shouldJumpReset()) {
                    jumpDelayTicks = 1 + (jumpResetDelay.get() == 0 ? 0 : random.nextInt(jumpResetDelay.get()));
                }
                return;
            }

            // Lag mode: buffer and release the velocity later
            if (lagTicks.get() > 0) {
                event.cancel();
                lagVelocity = packet.movement();
                lagTicksLeft = lagTicks.get();
                return;
            }

            // Chance gate for the multiplier path
            if (random.nextInt(100) >= chance.get()) return;

            double velX = (packet.movement().x() - mc.player.getDeltaMovement().x) * knockbackHorizontal.get();
            double velY = (packet.movement().y() - mc.player.getDeltaMovement().y) * knockbackVertical.get();
            double velZ = (packet.movement().z() - mc.player.getDeltaMovement().z) * knockbackHorizontal.get();
            ((ClientboundSetEntityMotionPacketAccessor) (Object) packet).meteor$setMovement(
                new Vec3(velX + mc.player.getDeltaMovement().x, velY + mc.player.getDeltaMovement().y, velZ + mc.player.getDeltaMovement().z)
            );
        }
    }

    private boolean shouldJumpReset() {
        if (random.nextInt(100) >= jumpResetChance.get()) return false;
        return jumpReset.get() != JumpResetMode.ByHits || hitsReceived % jumpResetHits.get() == 0;
    }

    /**
     * Send position + action packets that mask the cancelled velocity as a legitimate
     * client-server exchange (ported from Huntress, tick-scheduled instead of threaded).
     */
    private void sendMaskPackets() {
        if (grimDelay.get() > 0) {
            maskDelayTicks = grimDelay.get();
        } else {
            sendMaskPacketsNow();
        }
    }

    private void sendMaskPacketsNow() {
        if (mc.player == null || mc.getConnection() == null) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
            mc.player.getX(),
            mc.player.getY(),
            mc.player.getZ(),
            mc.player.getYRot(),
            mc.player.getXRot(),
            mc.player.onGround(),
            mc.player.horizontalCollision
        ));

        mc.getConnection().send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
            mc.player.blockPosition(),
            Direction.DOWN
        ));
    }

    public double getHorizontal(Setting<Double> setting) {
        return isActive() ? setting.get() : 1;
    }

    public double getVertical(Setting<Double> setting) {
        return isActive() ? setting.get() : 1;
    }
}
