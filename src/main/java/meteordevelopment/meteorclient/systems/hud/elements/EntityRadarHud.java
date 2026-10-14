/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Top-down radar disk: dots for nearby players, hostiles, passives and items, rotated to your view. */
public class EntityRadarHud extends HudElement {
    public static final HudElementInfo<EntityRadarHud> INFO = new HudElementInfo<>(Hud.GROUP, "entity-radar", "Top-down radar of nearby entities.", EntityRadarHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Integer> size = sgGeneral.add(new IntSetting.Builder()
        .name("size").description("Radar size in pixels.").defaultValue(100).range(40, 300).sliderRange(40, 300).onChanged(v -> setSize(v, v)).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("World blocks from centre to edge.").defaultValue(48).range(8, 256).sliderRange(8, 128).build());

    private final Setting<Integer> dotSize = sgGeneral.add(new IntSetting.Builder()
        .name("dot-size").description("Dot size in pixels.").defaultValue(3).range(1, 8).sliderRange(1, 8).build());

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder().name("players").description("Show players.").defaultValue(true).build());
    private final Setting<Boolean> hostiles = sgGeneral.add(new BoolSetting.Builder().name("hostiles").description("Show hostile mobs.").defaultValue(true).build());
    private final Setting<Boolean> passives = sgGeneral.add(new BoolSetting.Builder().name("passives").description("Show other living entities.").defaultValue(false).build());
    private final Setting<Boolean> items = sgGeneral.add(new BoolSetting.Builder().name("items").description("Show dropped items.").defaultValue(false).build());

    private final Setting<SettingColor> background = sgColors.add(new ColorSetting.Builder().name("background").description("Background colour.").defaultValue(new SettingColor(20, 20, 20, 120)).build());
    private final Setting<SettingColor> cross = sgColors.add(new ColorSetting.Builder().name("crosshair").description("Centre lines.").defaultValue(new SettingColor(255, 255, 255, 60)).build());
    private final Setting<SettingColor> playerColor = sgColors.add(new ColorSetting.Builder().name("player").description("Player dots.").defaultValue(new SettingColor(255, 255, 255)).build());
    private final Setting<SettingColor> friendColor = sgColors.add(new ColorSetting.Builder().name("friend").description("Friend dots.").defaultValue(new SettingColor(0, 200, 255)).build());
    private final Setting<SettingColor> hostileColor = sgColors.add(new ColorSetting.Builder().name("hostile").description("Hostile dots.").defaultValue(new SettingColor(255, 60, 60)).build());
    private final Setting<SettingColor> passiveColor = sgColors.add(new ColorSetting.Builder().name("passive").description("Passive dots.").defaultValue(new SettingColor(80, 255, 80)).build());
    private final Setting<SettingColor> itemColor = sgColors.add(new ColorSetting.Builder().name("item").description("Item dots.").defaultValue(new SettingColor(255, 210, 0)).build());

    public EntityRadarHud() {
        super(INFO);
        setSize(size.get(), size.get());
    }

    @Override
    public void render(HudRenderer renderer) {
        double w = getWidth(), h = getHeight();
        double cx = x + w / 2.0, cy = y + h / 2.0;
        renderer.quad(x, y, w, h, background.get());
        renderer.quad(cx - 0.5, y, 1, h, cross.get());
        renderer.quad(x, cy - 0.5, w, 1, cross.get());

        if (mc.level == null || mc.player == null || isInEditor()) {
            renderer.quad(cx - 1.5, cy - 1.5, 3, 3, playerColor.get());
            return;
        }

        double r = Math.min(w, h) / 2.0;
        int d = dotSize.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            SettingColor c = colorFor(e);
            if (c == null) continue;
            double[] p = RadarMath.project(e.getX() - mc.player.getX(), e.getZ() - mc.player.getZ(), mc.player.getYRot(), range.get(), r);
            if (p == null) continue;
            renderer.quad(cx + p[0] - d / 2.0, cy + p[1] - d / 2.0, d, d, c);
        }
        renderer.quad(cx - 1.5, cy - 1.5, 3, 3, playerColor.get());
    }

    private SettingColor colorFor(Entity e) {
        if (e instanceof Player p) return players.get() ? (Friends.get().isFriend(p) ? friendColor.get() : playerColor.get()) : null;
        if (e instanceof Enemy) return hostiles.get() ? hostileColor.get() : null;
        if (e instanceof ItemEntity) return items.get() ? itemColor.get() : null;
        if (e instanceof net.minecraft.world.entity.LivingEntity) return passives.get() ? passiveColor.get() : null;
        return null;
    }
}
