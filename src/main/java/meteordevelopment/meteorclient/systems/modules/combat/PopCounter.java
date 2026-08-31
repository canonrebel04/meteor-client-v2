/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * PopCounter: tracks how many totems each nearby player has popped this session
 * (via the vanilla totem-activation entity event, byte 35) and renders the tally.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PopCounter extends Module {
    // Vanilla entity-event id for totem activation (stable since 1.8)
    private static final byte TOTEM_POP_EVENT = 35;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the pop tallies.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the tally text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Text shadow.")
        .defaultValue(true)
        .build()
    );

    private final Map<UUID, Integer> pops = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public PopCounter() {
        super(Categories.Combat, "pop-counter", "Counts totem pops of nearby players and renders the tallies.");
    }

    @Override
    public void onDeactivate() {
        pops.clear();
        names.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundEntityEventPacket packet)) return;
        if (packet.getEventId() != TOTEM_POP_EVENT) return;

        var entity = packet.getEntity(mc.level);
        if (!(entity instanceof Player player) || player == mc.player) return;

        pops.merge(player.getUUID(), 1, Integer::sum);
        names.put(player.getUUID(), player.getName().getString());
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!render.get() || pops.isEmpty() || !Utils.canUpdate()) return;

        VanillaTextRenderer renderer = VanillaTextRenderer.INSTANCE;
        renderer.scale = 2;
        renderer.begin(event.graphics);

        double y = 10;
        double x = event.screenWidth - 120;

        for (Map.Entry<UUID, Integer> entry : pops.entrySet()) {
            String name = names.getOrDefault(entry.getKey(), "?");
            renderer.render(name + ": " + entry.getValue() + " pops", x, y, textColor.get(), shadow.get());
            y += 10;
        }

        renderer.end();
    }
}
