/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public class PearlPhase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch angle used when throwing the ender pearl into the wall/corner.")
        .defaultValue(85.0)
        .range(60.0, 90.0)
        .sliderRange(60.0, 90.0)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disables after throwing the pearl.")
        .defaultValue(true)
        .build()
    );

    private boolean thrown;

    public PearlPhase() {
        super(Categories.Movement, "pearl-phase", "Throws an ender pearl downward at an angle to phase into adjacent blocks.");
    }

    @Override
    public void onActivate() {
        thrown = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (thrown || mc.player == null || mc.gameMode == null) return;

        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            error("No ender pearl found in hotbar.");
            toggle();
            return;
        }

        thrown = true;
        Rotations.rotate(mc.player.getYRot(), pitch.get(), 100, () -> {
            if (mc.player == null || mc.gameMode == null) return;
            InteractionHand hand = pearl.getHand();
            if (hand == null) return;

            if (pearl.isOffhand()) {
                mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            } else {
                InvUtils.swap(pearl.slot(), true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
            }

            if (autoDisable.get() && isActive()) {
                toggle();
            }
        });
    }
}
