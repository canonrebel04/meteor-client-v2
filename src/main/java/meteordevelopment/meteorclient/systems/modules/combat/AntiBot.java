/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * Flags server-side NPCs / anti-cheat bots so shared targeting ({@code TargetUtils}) skips them.
 * KillAura, CombatBrain, AimAssist and every other TargetUtils consumer inherit the filter.
 */
public class AntiBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> tabList = sgGeneral.add(new BoolSetting.Builder()
        .name("tab-list")
        .description("Players missing from the tab list (or with no game mode there) are bots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> invalidName = sgGeneral.add(new BoolSetting.Builder()
        .name("invalid-name")
        .description("Names that are not 3-16 chars of [A-Za-z0-9_] are bots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> zeroPing = sgGeneral.add(new BoolSetting.Builder()
        .name("zero-ping")
        .description("Tab entries reporting 0 ms latency are bots. Can false-flag on some servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> tabNameMismatch = sgGeneral.add(new BoolSetting.Builder()
        .name("tab-name-mismatch")
        .description("Entity name differs from its tab profile name.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minTicksExisted = sgGeneral.add(new IntSetting.Builder()
        .name("min-ticks-existed")
        .description("Entities younger than this are treated as bots (0 = off).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<Boolean> invisible = sgGeneral.add(new BoolSetting.Builder()
        .name("invisible")
        .description("Invisible players are bots.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> noArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("no-armor")
        .description("Players with no armor at all are bots.")
        .defaultValue(false)
        .build()
    );

    public AntiBot() {
        super(Categories.Combat, "anti-bot", "Stops combat modules from targeting server bots / NPCs.");
    }

    /** True when AntiBot is enabled and flags this entity. Non-players and Meteor fake players are never bots. */
    public static boolean shouldIgnore(Entity entity) {
        AntiBot ab = Modules.get() == null ? null : Modules.get().get(AntiBot.class);
        return ab != null && ab.isActive() && ab.isBot(entity);
    }

    public boolean isBot(Entity entity) {
        if (!(entity instanceof Player player) || entity == mc.player || entity instanceof FakePlayerEntity) return false;
        if (mc.getConnection() == null) return false;

        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        String name = player.getGameProfile().name();
        boolean hasArmor = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!player.getItemBySlot(slot).isEmpty()) { hasArmor = true; break; }
        }

        AntiBotHeuristics.Signals s = new AntiBotHeuristics.Signals(
            name,
            info != null,
            info != null && info.getGameMode() != null,
            info == null ? 0 : info.getLatency(),
            player.tickCount,
            player.isInvisible(),
            hasArmor,
            info != null && name.equals(info.getProfile().name())
        );
        AntiBotHeuristics.Config c = new AntiBotHeuristics.Config(
            tabList.get(), invalidName.get(), zeroPing.get(), minTicksExisted.get(),
            invisible.get(), noArmor.get(), tabNameMismatch.get()
        );
        return AntiBotHeuristics.isBot(s, c);
    }
}
