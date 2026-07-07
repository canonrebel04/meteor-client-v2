/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ShieldAutoSwapModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> swapDelay = sgGeneral.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Delay in ticks before swapping back from shield after damage ends.")
        .defaultValue(0)
        .min(0)
        .max(40)
        .build()
    );

    private ItemStack previousItem;
    private int swapTimer;

    public ShieldAutoSwapModule() {
        super(Categories.Combat, "shield-auto-swap", "Automatically swaps to shield when taking damage and swaps back when attacking.");
    }

    @Override
    public void onActivate() {
        previousItem = mc.player.getOffhandItem().copy();
        swapTimer = 0;
    }

    @Override
    public void onDeactivate() {
        previousItem = null;
        swapTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Utils.canUpdate()) return;

        boolean takingDamage = mc.player.hurtTime > 0;
        boolean hasShield = mc.player.getOffhandItem().getItem() == Items.SHIELD;

        if (takingDamage && !hasShield) {
            FindItemResult shield = InvUtils.find(itemStack -> itemStack.getItem() == Items.SHIELD);
            if (shield.found()) {
                InvUtils.move().from(shield.slot()).toOffhand();
            }
            swapTimer = 0;
        } else if (!takingDamage && hasShield) {
            swapTimer++;
            if (swapTimer >= swapDelay.get() && previousItem != null) {
                FindItemResult prev = InvUtils.find(itemStack -> itemStack.getItem() == previousItem.getItem());
                if (prev.found()) {
                    InvUtils.move().from(prev.slot()).toOffhand();
                }
                swapTimer = 0;
            }
        } else {
            swapTimer = 0;
        }
    }
}