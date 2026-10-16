/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.ListMode;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.List;

/** Dedicated dropped-item ESP with an item filter and optional name/count labels. */
public class ItemESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<ListMode> listMode = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode").description("Whitelist: only show listed items. Blacklist: show everything except them.").defaultValue(ListMode.Blacklist).build());

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items").description("Item filter.").build());

    private final Setting<Integer> minCount = sgGeneral.add(new IntSetting.Builder()
        .name("min-count").description("Hide stacks smaller than this.").defaultValue(1).range(1, 64).sliderRange(1, 64).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Max distance.").defaultValue(64).range(4, 256).sliderRange(4, 256).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Box style.").defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Box fill.").defaultValue(new SettingColor(255, 210, 0, 40)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Box outline.").defaultValue(new SettingColor(255, 210, 0, 255)).build());

    private final Setting<SettingColor> rareColor = sgRender.add(new ColorSetting.Builder()
        .name("enchanted-color").description("Outline used for enchanted / rare items.").defaultValue(new SettingColor(200, 80, 255, 255)).build());

    private final Setting<Boolean> labels = sgRender.add(new BoolSetting.Builder()
        .name("labels").description("Show item name and count above each drop.").defaultValue(true).build());

    private final Setting<Double> labelScale = sgRender.add(new DoubleSetting.Builder()
        .name("label-scale").description("Label scale.").defaultValue(1).range(0.5, 3).sliderRange(0.5, 3).visible(labels::get).build());

    private final Vector3d pos = new Vector3d();

    public ItemESP() {
        super(Categories.Render, "item-esp", "Highlights dropped items through walls.");
    }

    /** Pure filter rule. */
    public static boolean shouldShow(ListMode mode, boolean inList, int count, int minCount) {
        return count >= minCount && mode.allows(inList);
    }

    private boolean show(Entity e) {
        if (!(e instanceof ItemEntity ie)) return false;
        if (mc.player.distanceToSqr(e) > range.get() * range.get()) return false;
        ItemStack s = ie.getItem();
        return shouldShow(listMode.get(), items.get().contains(s.getItem()), s.getCount(), minCount.get());
    }

    private static boolean rare(ItemStack s) {
        return s.isEnchanted() || s.hasFoil();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!show(e)) continue;
            double x = Mth.lerp(event.tickDelta, e.xOld, e.getX()) - e.getX();
            double y = Mth.lerp(event.tickDelta, e.yOld, e.getY()) - e.getY();
            double z = Mth.lerp(event.tickDelta, e.zOld, e.getZ()) - e.getZ();
            AABB b = e.getBoundingBox();
            SettingColor line = rare(((ItemEntity) e).getItem()) ? rareColor.get() : lineColor.get();
            event.renderer.box(x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ, sideColor.get(), line, shapeMode.get(), 0);
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!labels.get() || mc.level == null || mc.player == null) return;
        TextRenderer text = TextRenderer.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!show(e)) continue;
            ItemStack s = ((ItemEntity) e).getItem();
            double x = Mth.lerp(event.tickDelta, e.xOld, e.getX());
            double y = Mth.lerp(event.tickDelta, e.yOld, e.getY());
            double z = Mth.lerp(event.tickDelta, e.zOld, e.getZ());
            pos.set(x, y + 0.6, z);
            if (!NametagUtils.to2D(pos, labelScale.get())) continue;
            String label = s.getHoverName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : "");
            NametagUtils.begin(pos);
            double w = text.getWidth(label) / 2.0;
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(-w - 1, -1, w * 2 + 2, text.getHeight() + 1, new SettingColor(0, 0, 0, 90));
            Renderer2D.COLOR.render();
            text.beginBig(event.graphics);
            text.render(label, -w, 0, rare(s) ? rareColor.get() : lineColor.get());
            text.end();
            NametagUtils.end();
        }
    }
}
