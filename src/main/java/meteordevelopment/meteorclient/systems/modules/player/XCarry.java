/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;

/**
 * Lets you store items in the 2x2 crafting grid and cursor: the inventory close packet is not sent,
 * so the server keeps those slots filled.
 */
public class XCarry extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> closeOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("close-on-disable")
        .description("Send the suppressed close packet when disabling, returning carried items to your inventory (or dropping them).")
        .defaultValue(false)
        .build()
    );

    private boolean suppressed;

    public XCarry() {
        super(Categories.Player, "x-carry", "Use the crafting grid as extra inventory slots.");
    }

    /** Container id 0 is always the player's own inventory menu. */
    public static boolean shouldCancel(int containerId) {
        return containerId == 0;
    }

    @Override
    public void onActivate() {
        suppressed = false;
    }

    @Override
    public void onDeactivate() {
        if (closeOnDisable.get() && suppressed && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundContainerClosePacket(0));
        }
        suppressed = false;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundContainerClosePacket p && shouldCancel(p.getContainerId())) {
            event.cancel();
            suppressed = true;
        }
    }
}
