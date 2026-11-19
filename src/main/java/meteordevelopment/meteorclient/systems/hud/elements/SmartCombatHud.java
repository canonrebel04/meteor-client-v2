/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.systems.modules.combat.CombatTargetAnalyzer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class SmartCombatHud extends HudElement {
    private static final Color GREEN = new Color(15, 255, 15);
    private static final Color AMBER = new Color(255, 190, 15);
    private static final Color RED = new Color(255, 15, 15);

    public static final HudElementInfo<SmartCombatHud> INFO = new HudElementInfo<>(Hud.GROUP, "smart-combat", "Displays SmartCombat state: current target, mode, and timers.", SmartCombatHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScale = settings.createGroup("Scale");
    private final SettingGroup sgBackground = settings.createGroup("Background");

    // General

    private final Setting<SettingColor> primaryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("primary-color")
        .description("Primary text color.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> secondaryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("secondary-color")
        .description("Secondary text color for labels.")
        .defaultValue(new SettingColor(175, 175, 175))
        .build()
    );

    private final Setting<SettingColor> activeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("active-color")
        .description("Color for 'Enabled' status text.")
        .defaultValue(new SettingColor(15, 255, 15))
        .build()
    );

    private final Setting<SettingColor> inactiveColor = sgGeneral.add(new ColorSetting.Builder()
        .name("inactive-color")
        .description("Color for 'Disabled' status text.")
        .defaultValue(new SettingColor(255, 15, 15))
        .build()
    );

    // Scale

    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies a custom scale.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1)
        .min(0.5)
        .sliderRange(0.5, 3)
        .build()
    );

    // Background

    private final Setting<Boolean> background = sgBackground.add(new BoolSetting.Builder()
        .name("background")
        .description("Displays background.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgBackground.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color used for the background.")
        .visible(background::get)
        .defaultValue(new SettingColor(25, 25, 25, 50))
        .build()
    );

    // S70/S73: animated armor gauge, debounced state/mode lines
    private final meteordevelopment.meteorclient.utils.render.anim.Tween armorGauge = new meteordevelopment.meteorclient.utils.render.anim.Tween(
        meteordevelopment.meteorclient.utils.render.anim.Anim.NORMAL);
    private final meteordevelopment.meteorclient.utils.render.anim.Debouncer<String> stateText = new meteordevelopment.meteorclient.utils.render.anim.Debouncer<>(
        0.15, meteordevelopment.meteorclient.utils.render.anim.Anim.MIN_DISPLAY);
    private final meteordevelopment.meteorclient.utils.render.anim.Debouncer<String> modeText = new meteordevelopment.meteorclient.utils.render.anim.Debouncer<>(
        0.15, meteordevelopment.meteorclient.utils.render.anim.Anim.MIN_DISPLAY);
    private long lastFrame;
    private final Color gaugeBack = new Color(0, 0, 0, 90);

    public SmartCombatHud() {
        super(INFO);
        armorGauge.set(0);
    }

    private static double now() {
        return System.nanoTime() / 1e9;
    }

    @Override
    public void tick(HudRenderer renderer) {
        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);

        if (cb == null) {
            setSize(renderer.textWidth("Combat Mode", false, getScale()), renderer.textHeight(false, getScale()));
            return;
        }

        String title = "Combat Mode";
        String status = cb.isActive() ? "Enabled" : "Disabled";
        String mode = "Mode: " + (cb.isActive() ? modeLabel(cb) : "N/A");
        String targetLine = "Target: none";
        String armorLine = "Min Armor: none";
        String stateLine = "State: " + (cb.isActive() ? cb.getState().name() : "IDLE");

        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
            armorLine = "Min Armor: 42%";
            stateLine = "State: ENGAGING (STRIKE)";
        } else if (cb.isActive()) {
            Entity target = cb.getCurrentTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            }
            if (target instanceof LivingEntity living && CombatTargetAnalyzer.hasDamageableArmor(living)) {
                double ratio = CombatTargetAnalyzer.getLowestArmorDurabilityRatio(living);
                int pct = (int) Math.round(ratio * 100.0);
                armorLine = ratio < 0.18 ? "Min Armor: " + pct + "% (LOW)" : "Min Armor: " + pct + "%";
            }
            stateLine = "State: " + cb.getState().name() + " (" + cb.getStrikePhase().name() + ")";
        }

        double width = 0;
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        width = Math.max(width, renderer.textWidth(title, false, getScale()));
        width = Math.max(width, renderer.textWidth(status, false, getScale()));
        width = Math.max(width, renderer.textWidth(mode, false, getScale()));
        width = Math.max(width, renderer.textWidth(targetLine, false, getScale()));
        width = Math.max(width, renderer.textWidth(armorLine, false, getScale()));
        width = Math.max(width, renderer.textWidth(stateLine, false, getScale()));

        double height = 6 * lineHeight;

        setSize(width, height);
    }

    @Override
    public void render(HudRenderer renderer) {
        double x = this.x;
        double y = this.y + 2;

        if (background.get()) {
            renderer.quad(this.x, this.y, getWidth(), getHeight(), backgroundColor.get());
        }

        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        double lineHeight = renderer.textHeight(false, getScale()) + 2;

        String title = "Combat Mode";
        renderer.text(title, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        if (cb == null) {
            renderer.text("Module not loaded", x, y, RED, false, getScale());
            return;
        }

        // Status
        boolean active = cb.isActive();
        Color statusColor = active ? activeColor.get() : inactiveColor.get();
        String status = active ? "Enabled" : "Disabled";
        renderer.text(status, x, y, statusColor, false, getScale());
        y += lineHeight;

        // Mode
        String modeName = active ? modeText.update(modeLabel(cb), now()) : "N/A";
        renderer.text("Mode: " + modeName, x, y, secondaryColor.get(), false, getScale());
        y += lineHeight;

        // Target
        String targetLine;
        String armorLine = "Min Armor: none";
        Color armorColor = secondaryColor.get();
        if (isInEditor()) {
            targetLine = "Target: Zombie (4.2m)";
            armorLine = "Min Armor: 42%";
            armorColor = AMBER;
        } else if (active) {
            Entity target = cb.getCurrentTarget();
            if (target != null && mc.player != null) {
                double dist = Math.round(mc.player.distanceTo(target) * 10.0) / 10.0;
                targetLine = "Target: " + target.getName().getString() + " (" + dist + "m)";
            } else {
                targetLine = "Target: none";
            }
            if (target instanceof LivingEntity living && CombatTargetAnalyzer.hasDamageableArmor(living)) {
                double ratio = CombatTargetAnalyzer.getLowestArmorDurabilityRatio(living);
                int pct = (int) Math.round(ratio * 100.0);
                armorLine = ratio < 0.18 ? "Min Armor: " + pct + "% (LOW)" : "Min Armor: " + pct + "%";
                if (ratio < 0.18) armorColor = RED;
                else if (ratio < 0.50) armorColor = AMBER;
                else armorColor = GREEN;
            }
        } else {
            targetLine = "Target: none";
        }
        renderer.text(targetLine, x, y, primaryColor.get(), false, getScale());
        y += lineHeight;

        renderer.text(armorLine, x, y, armorColor, false, getScale());
        long t = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.25, (t - lastFrame) / 1e9);
        lastFrame = t;
        double pct = parsePercent(armorLine);
        if (pct >= 0) armorGauge.target(pct);
        double g = armorGauge.update(dt);
        if (pct >= 0) {
            double gy = y + lineHeight - 2;
            double gw = getWidth();
            renderer.quad(x, gy, gw, 1.5, gaugeBack);
            renderer.quad(x, gy, gw * g, 1.5, armorColor);
        }
        y += lineHeight;

        // State / Phase
        String stateLine;
        if (isInEditor()) {
            stateLine = "State: ENGAGING (STRIKE)";
        } else if (active) {
            stateLine = "State: " + stateText.update(cb.getState().name() + " (" + cb.getStrikePhase().name() + ")", now());
        } else {
            stateLine = "State: IDLE";
        }
        renderer.text(stateLine, x, y, secondaryColor.get(), false, getScale());
    }

    /** "Min Armor: 42%" -> 0.42; -1 without a percentage. */
    static double parsePercent(String line) {
        int pct = line.indexOf('%');
        if (pct < 0) return -1;
        int i = pct;
        while (i > 0 && Character.isDigit(line.charAt(i - 1))) i--;
        if (i == pct) return -1;
        return Math.min(1, Integer.parseInt(line.substring(i, pct)) / 100.0);
    }

    private double getScale() {
        return customScale.get() ? scale.get() : Hud.get().getTextScale();
    }

    /** Mode name plus forced marker and resolved follow distance, e.g. "KITE* (follow 5.5m)". */
    private static String modeLabel(CombatBrainModule cb) {
        StringBuilder sb = new StringBuilder(cb.getCombatMode().name());
        if (cb.getForcedMode() != null) sb.append('*');
        double fd = cb.getFollowDistance();
        if (!Double.isNaN(fd)) sb.append(" (follow ").append(Math.round(fd * 10.0) / 10.0).append("m)");
        return sb.toString();
    }
}