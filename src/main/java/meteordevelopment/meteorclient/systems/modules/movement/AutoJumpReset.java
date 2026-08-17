/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Standalone auto jump-reset: presses jump right after taking knockback, abusing the vanilla
 * mechanic that jumping negates most horizontal knockback. Independent of the Velocity module.
 * (Reference: LiquidBounce JumpReset velocity mode; System client AutoJumpReset.)
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

import java.util.Random;

public class AutoJumpReset extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> chance = sgGeneral.add(new IntSetting.Builder()
        .name("chance")
        .description("Chance in percent to jump-reset when hit.")
        .defaultValue(100)
        .range(1, 100)
        .sliderRange(25, 100)
        .build()
    );

    private final Setting<Integer> hits = sgGeneral.add(new IntSetting.Builder()
        .name("hits")
        .description("Only jump-reset every Nth received hit. 1 = every hit.")
        .defaultValue(1)
        .range(1, 10)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Randomized tick delay before jumping (0 = next tick). Randomization breaks pattern detection.")
        .defaultValue(1)
        .range(0, 5)
        .build()
    );

    private final Setting<Boolean> onlyWhenHeld = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-holding-weapon")
        .description("Only jump-reset while holding a sword or axe (combat only).")
        .defaultValue(true)
        .build()
    );

    private final Random random = new Random();
    private int jumpDelayTicks = 0;
    private int jumpHoldTicks = 0;
    private int hitsReceived = 0;

    public AutoJumpReset() {
        super(Categories.Movement, "auto-jump-reset", "Automatically jumps right after taking knockback to negate it via the vanilla jump mechanic.");
    }

    @Override
    public void onDeactivate() {
        jumpDelayTicks = 0;
        jumpHoldTicks = 0;
        hitsReceived = 0;
        if (mc.options != null && mc.options.keyJump.isDown()) mc.options.keyJump.setDown(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        if (jumpDelayTicks > 0) {
            jumpDelayTicks--;
            if (jumpDelayTicks == 0 && mc.player.onGround()) {
                jumpHoldTicks = 2;
            }
        }

        if (jumpHoldTicks > 0) {
            mc.options.keyJump.setDown(true);
            jumpHoldTicks--;
            if (jumpHoldTicks == 0) mc.options.keyJump.setDown(false);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        if (event.packet instanceof ClientboundSetEntityMotionPacket packet
            && packet.id() == mc.player.getId()) {

            if (jumpDelayTicks > 0 || jumpHoldTicks > 0) return;

            hitsReceived++;
            if (hitsReceived % hits.get() != 0) return;
            if (random.nextInt(100) >= chance.get()) return;
            if (onlyWhenHeld.get() && !isHoldingWeapon()) return;

            jumpDelayTicks = 1 + (delay.get() == 0 ? 0 : random.nextInt(delay.get()));
        }
    }

    private boolean isHoldingWeapon() {
        // MC 26.x removed SwordItem as a class (data-component driven); axe + mace cover the burst weapons.
        var item = mc.player.getMainHandItem().getItem();
        return item instanceof net.minecraft.world.item.AxeItem
            || item instanceof net.minecraft.world.item.MaceItem;
    }
}
