/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.interfaces.IGoalRenderPos;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BaritoneHud extends HudElement {
    public static final HudElementInfo<BaritoneHud> INFO = new HudElementInfo<>(Hud.GROUP, "baritone", "Displays live Baritone process, goal ETA, and elytra fuel telemetry.", BaritoneHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScale = settings.createGroup("Scale");

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders text with drop shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color of metric labels.")
        .defaultValue(new SettingColor(180, 180, 180))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color of metric values.")
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

    public BaritoneHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = customScale.get() ? scale.get() : Hud.get().getTextScale();
        List<String[]> lines = new ArrayList<>();

        if (isInEditor() || mc.player == null) {
            lines.add(new String[]{"Baritone: ", "Elytra [FLYING]"});
            lines.add(new String[]{"Goal: ", "1240m (ETA 41s)"});
            lines.add(new String[]{"Rockets: ", "128 (~12.8km)"});
            lines.add(new String[]{"Path: ", "42 / 180"});
        } else {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            IBaritoneProcess proc = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
            lines.add(new String[]{"Baritone: ", proc != null ? proc.displayName() : "Idle"});

            Goal goal = baritone.getPathingBehavior().getGoal();
            if (goal instanceof IGoalRenderPos grp) {
                BlockPos gPos = grp.getGoalPos();
                double dist = Math.hypot(gPos.getX() - mc.player.getX(), gPos.getZ() - mc.player.getZ());
                double speedBps = Math.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z) * 20.0;
                String eta = speedBps > 0.5 ? String.format(" (ETA %ds)", (int) Math.ceil(dist / speedBps)) : "";
                lines.add(new String[]{"Goal: ", String.format("%.0fm%s", dist, eta)});
            }

            int rockets = InvUtils.find(Items.FIREWORK_ROCKET).count();
            if (rockets > 0 || mc.player.isFallFlying()) {
                double estKm = (rockets * BaritoneAPI.getSettings().elytraBlocksPerFirework.value) / 1000.0;
                lines.add(new String[]{"Rockets: ", String.format("%d (~%.1fkm)", rockets, estKm)});
            }

            IPathExecutor exec = baritone.getPathingBehavior().getCurrent();
            if (exec != null && exec.getPath() != null) {
                lines.add(new String[]{"Path: ", exec.getPosition() + " / " + exec.getPath().length()});
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
}
