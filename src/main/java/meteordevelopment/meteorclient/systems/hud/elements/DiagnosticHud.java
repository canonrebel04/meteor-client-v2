/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.systems.modules.world.GetItem;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Plan S83: in-game overlay for {@code #movedebug}-style path status, {@code #combat why},
 * and {@code #get plan} progress. Add it from the HUD editor; it does not change pathing.
 */
public class DiagnosticHud extends HudElement {
    public static final HudElementInfo<DiagnosticHud> INFO = new HudElementInfo<>(Hud.GROUP, "diagnostic", "Overlay for combat why, path status, and Get Item plan progress.", DiagnosticHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScale = settings.createGroup("Scale");

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Text shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color of line labels.")
        .defaultValue(new SettingColor(180, 180, 180))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color of line values.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<Boolean> customScale = sgScale.add(new BoolSetting.Builder()
        .name("custom-scale")
        .description("Applies a custom scale to this HUD element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgScale.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Custom scale.")
        .visible(customScale::get)
        .defaultValue(1.0)
        .min(0.5)
        .sliderRange(0.5, 3.0)
        .build()
    );

    public DiagnosticHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = customScale.get() ? scale.get() : Hud.get().getTextScale();
        java.util.List<String[]> lines = new java.util.ArrayList<>();

        if (isInEditor() || mc.player == null || Modules.get() == null) {
            lines.add(new String[]{"#combat why: ", "ENGAGING | even fight"});
            lines.add(new String[]{"#movedebug: ", "idle"});
            lines.add(new String[]{"#get plan: ", "iron_ingot 0/32"});
        } else {
            CombatBrainModule brain = Modules.get().get(CombatBrainModule.class);
            if (brain != null && brain.isActive()) {
                lines.add(new String[]{"#combat why: ", clip(brain.explainVerdict(), 96)});
            } else {
                lines.add(new String[]{"#combat why: ", "off"});
            }

            String path = "idle";
            try {
                var bt = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
                var exec = bt.getPathingBehavior().getCurrent();
                if (exec != null && exec.getPath() != null) {
                    path = exec.getPosition() + " / " + exec.getPath().length();
                    var goal = bt.getPathingBehavior().getGoal();
                    if (goal != null) path += " -> " + goal;
                }
            } catch (Throwable ignored) {
            }
            lines.add(new String[]{"#movedebug: ", path});

            GetItem get = Modules.get().get(GetItem.class);
            if (get != null && get.isActive()) {
                String p = get.progress();
                lines.add(new String[]{"#get plan: ", p != null && !p.isBlank() ? clip(p, 96) : "running"});
            } else {
                lines.add(new String[]{"#get plan: ", "off"});
            }
        }

        double maxWidth = 0;
        double lineH = renderer.textHeight(shadow.get(), s);
        double curY = y;
        for (String[] pair : lines) {
            double x2 = renderer.text(pair[0], x, curY, labelColor.get(), shadow.get(), s);
            x2 = renderer.text(pair[1], x2, curY, valueColor.get(), shadow.get(), s);
            maxWidth = Math.max(maxWidth, x2 - x);
            curY += lineH + 2;
        }
        setSize(maxWidth, Math.max(lineH, curY - y - 2));
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
