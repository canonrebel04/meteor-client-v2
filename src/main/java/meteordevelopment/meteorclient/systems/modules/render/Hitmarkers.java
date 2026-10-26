/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render;

import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

import java.util.*;

/** Crosshair hitmarkers, optional hit sound, and floating damage / heal numbers. */
public class Hitmarkers extends Module {
    private final SettingGroup sgMarker = settings.createGroup("Hitmarker");
    private final SettingGroup sgDamage = settings.createGroup("Damage Numbers");

    private final Setting<Boolean> marker = sgMarker.add(new BoolSetting.Builder()
        .name("hitmarker").description("Draw an X at the crosshair when you hit an entity.").defaultValue(true).build());

    private final Setting<Integer> markerDuration = sgMarker.add(new IntSetting.Builder()
        .name("duration").description("Hitmarker lifetime in milliseconds.").defaultValue(300).range(50, 2000).sliderRange(50, 1000).visible(marker::get).build());

    private final Setting<Integer> markerSize = sgMarker.add(new IntSetting.Builder()
        .name("size").description("Hitmarker arm length in pixels.").defaultValue(6).range(2, 30).sliderRange(2, 20).visible(marker::get).build());

    private final Setting<SettingColor> markerColor = sgMarker.add(new ColorSetting.Builder()
        .name("color").description("Hitmarker colour.").defaultValue(new SettingColor(255, 255, 255, 255)).visible(marker::get).build());

    private final Setting<SettingColor> killColor = sgMarker.add(new ColorSetting.Builder()
        .name("kill-color").description("Colour used when the hit killed the target.").defaultValue(new SettingColor(255, 40, 40, 255)).visible(marker::get).build());

    private final Setting<Boolean> sound = sgMarker.add(new BoolSetting.Builder()
        .name("sound").description("Play a click on hit.").defaultValue(false).build());

    private final Setting<Double> pitch = sgMarker.add(new DoubleSetting.Builder()
        .name("sound-pitch").description("Hit sound pitch.").defaultValue(1.6).range(0.5, 2).sliderRange(0.5, 2).visible(sound::get).build());

    private final Setting<Boolean> damageNumbers = sgDamage.add(new BoolSetting.Builder()
        .name("damage-numbers").description("Float health changes above entities.").defaultValue(true).build());

    private final Setting<Boolean> showHeals = sgDamage.add(new BoolSetting.Builder()
        .name("show-heals").description("Also show health gains.").defaultValue(false).visible(damageNumbers::get).build());

    private final Setting<Boolean> onlyAttacked = sgDamage.add(new BoolSetting.Builder()
        .name("only-attacked").description("Only show numbers for entities you hit recently.").defaultValue(false).visible(damageNumbers::get).build());

    private final Setting<Double> range = sgDamage.add(new DoubleSetting.Builder()
        .name("range").description("Track entities within this distance.").defaultValue(24).range(4, 64).sliderRange(4, 64).visible(damageNumbers::get).build());

    private final Setting<Integer> numberDuration = sgDamage.add(new IntSetting.Builder()
        .name("number-duration").description("Lifetime of each number in milliseconds.").defaultValue(1000).range(200, 4000).sliderRange(200, 3000).visible(damageNumbers::get).build());

    private final Setting<Double> scale = sgDamage.add(new DoubleSetting.Builder()
        .name("scale").description("Text scale.").defaultValue(1.2).range(0.5, 3).sliderRange(0.5, 3).visible(damageNumbers::get).build());

    private final Setting<SettingColor> damageColor = sgDamage.add(new ColorSetting.Builder()
        .name("damage-color").description("Damage number colour.").defaultValue(new SettingColor(255, 80, 80, 255)).visible(damageNumbers::get).build());

    private final Setting<SettingColor> healColor = sgDamage.add(new ColorSetting.Builder()
        .name("heal-color").description("Heal number colour.").defaultValue(new SettingColor(80, 255, 80, 255)).visible(() -> damageNumbers.get() && showHeals.get()).build());

    private record Popup(double x, double y, double z, float amount, long start) {}

    private static final int MAX_POPUPS = 64;

    private final HitFeedback.HealthTracker tracker = new HitFeedback.HealthTracker();
    private final Deque<Popup> popups = new ArrayDeque<>();
    private final Map<Integer, Long> recentlyAttacked = new HashMap<>();
    private final Vector3d pos = new Vector3d();
    private final Color tmp = new Color();
    private long hitTime = -1;
    private Entity lastHit;

    public Hitmarkers() {
        super(Categories.Render, "hitmarkers", "Hit feedback: crosshair markers, hit sound and floating damage numbers.");
    }

    @Override
    public void onActivate() {
        tracker.clear();
        popups.clear();
        recentlyAttacked.clear();
        hitTime = -1;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (!(event.entity instanceof LivingEntity)) return;
        hitTime = System.currentTimeMillis();
        lastHit = event.entity;
        recentlyAttacked.put(event.entity.getId(), hitTime);
        if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ARROW_HIT_PLAYER, pitch.get().floatValue(), 0.6f));
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null || !damageNumbers.get()) return;
        long now = System.currentTimeMillis();
        recentlyAttacked.values().removeIf(t -> now - t > 3000);

        Set<Integer> seen = new HashSet<>();
        double r2 = range.get() * range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == mc.player) continue;
            if (mc.player.distanceToSqr(e) > r2) continue;
            seen.add(e.getId());
            float delta = tracker.update(e.getId(), le.getHealth());
            if (delta == 0) continue;
            if (delta > 0 && !showHeals.get()) continue;
            if (onlyAttacked.get() && !recentlyAttacked.containsKey(e.getId())) continue;
            double jitter = (popups.size() % 5 - 2) * 0.15;
            popups.addLast(new Popup(e.getX() + jitter, e.getY() + e.getBbHeight() + 0.3, e.getZ(), delta, now));
            while (popups.size() > MAX_POPUPS) popups.removeFirst();
        }
        tracker.retain(seen);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        long now = System.currentTimeMillis();

        if (marker.get() && hitTime > 0) {
            double a = HitFeedback.fade(now - hitTime, markerDuration.get());
            if (a > 0) {
                boolean killed = lastHit instanceof LivingEntity le && (le.isDeadOrDying() || !le.isAlive());
                Color base = killed ? killColor.get() : markerColor.get();
                tmp.set(base.r, base.g, base.b, (int) (base.a * a));
                double cx = event.screenWidth / 2.0, cy = event.screenHeight / 2.0;
                int gap = 3, s = markerSize.get();
                Renderer2D.COLOR.begin();
                Renderer2D.COLOR.line(cx - gap - s, cy - gap - s, cx - gap, cy - gap, tmp);
                Renderer2D.COLOR.line(cx + gap + s, cy - gap - s, cx + gap, cy - gap, tmp);
                Renderer2D.COLOR.line(cx - gap - s, cy + gap + s, cx - gap, cy + gap, tmp);
                Renderer2D.COLOR.line(cx + gap + s, cy + gap + s, cx + gap, cy + gap, tmp);
                Renderer2D.COLOR.render();
            }
        }

        if (!damageNumbers.get() || popups.isEmpty()) return;
        int dur = numberDuration.get();
        popups.removeIf(p -> now - p.start() > dur);
        TextRenderer text = TextRenderer.get();
        for (Popup p : popups) {
            double elapsed = now - p.start();
            double a = HitFeedback.fade(elapsed, dur);
            pos.set(p.x(), p.y() + elapsed / dur * 0.8, p.z());
            if (!NametagUtils.to2D(pos, scale.get())) continue;
            Color base = p.amount() < 0 ? damageColor.get() : healColor.get();
            tmp.set(base.r, base.g, base.b, (int) (base.a * a));
            String s = (p.amount() < 0 ? "-" : "+") + String.format(Locale.ROOT, "%.1f", Math.abs(p.amount()));
            NametagUtils.begin(pos);
            text.beginBig(event.graphics);
            text.render(s, -text.getWidth(s) / 2.0, 0, tmp, true);
            text.end();
            NametagUtils.end();
        }
    }
}
