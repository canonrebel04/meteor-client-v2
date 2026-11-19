/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.hud.elements;

import baritone.api.BaritoneAPI;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.world.GetItemSpec;
import meteordevelopment.meteorclient.utils.render.anim.Anim;
import meteordevelopment.meteorclient.utils.render.anim.EtaFilter;
import meteordevelopment.meteorclient.utils.render.anim.Tween;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Get Item progress (plan A50): goal, have/need, step, current action or pause reason, ETA. S68/S69: fades in
 * and out, eased progress bar, Kalman-smoothed ETA that counts down steadily. S71: optional plan list.
 */
public class GetItemHud extends HudElement {
    public static final HudElementInfo<GetItemHud> INFO = new HudElementInfo<>(Hud.GROUP, "get-item", "Shows Baritone Get Item progress.", GetItemHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hideIdle = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-when-idle")
        .description("Hide when Get Item isn't running.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Renders text with drop shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> progressBar = sgGeneral.add(new BoolSetting.Builder()
        .name("progress-bar")
        .description("Show an eased progress bar under the text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> smoothEta = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-eta")
        .description("Filter the ETA so it counts down steadily instead of jumping on every re-plan.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> planLines = sgGeneral.add(new IntSetting.Builder()
        .name("plan-lines")
        .description("Show this many lines of the current plan under the progress (0 = off).")
        .defaultValue(0)
        .range(0, 12)
        .sliderRange(0, 12)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color of the label.")
        .defaultValue(new SettingColor(180, 180, 180))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color of the progress text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> barColor = sgGeneral.add(new ColorSetting.Builder()
        .name("bar-color")
        .description("Color of the progress bar.")
        .defaultValue(new SettingColor(90, 200, 120))
        .visible(progressBar::get)
        .build()
    );

    private final Tween fill = new Tween(Anim.SLOW);
    private final Tween alpha = new Tween(Anim.NORMAL);
    private final EtaFilter eta = new EtaFilter();
    private long lastNanos;
    private String lastLine = "";
    private List<String> plan = new ArrayList<>();
    private long planAt;
    private final Color tmpLabel = new Color(), tmpValue = new Color(), tmpBar = new Color(), tmpBack = new Color();

    public GetItemHud() {
        super(INFO);
        alpha.set(0);
        fill.set(0);
    }

    @Override
    public void render(HudRenderer renderer) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        String line;
        boolean running = true;
        if (isInEditor() || mc.player == null) line = "iron_pickaxe 0/1 · step 3/9 · mining iron_ore · ETA 2m 10s";
        else {
            line = BaritoneAPI.getProvider().getPrimaryBaritone().getGetItemProcess().progressLine();
            if (line == null) {
                running = false;
                eta.reset();
                if (hideIdle.get()) {
                    alpha.target(0);
                    if (alpha.update(dt) < 0.02) {
                        setSize(0, 0);
                        return;
                    }
                    line = lastLine;
                } else line = "idle";
            }
        }
        if (running) {
            lastLine = line;
            alpha.target(1);
        }
        double a = alpha.update(dt);
        if (running && smoothEta.get()) {
            double e = eta.update(GetItemSpec.etaSeconds(line), dt);
            line = GetItemSpec.withEta(line, e < 0 ? null : EtaFilter.format(e));
        }
        double s = Hud.get().getTextScale();
        double x2 = renderer.text("Get Item: ", x, y, fade(tmpLabel, labelColor.get(), a), shadow.get(), s);
        x2 = renderer.text(line, x2, y, fade(tmpValue, valueColor.get(), a), shadow.get(), s);
        double h = renderer.textHeight(shadow.get(), s);
        double width = x2 - x;
        double yy = y + h;
        if (progressBar.get()) {
            double f = GetItemSpec.fraction(line);
            if (f >= 0) fill.target(f);
            double v = fill.update(dt);
            double bh = Math.max(2, h * 0.25);
            yy += 1;
            renderer.quad(x, yy, width, bh, tmpBack.set(0, 0, 0, (int) (Anim.backgroundAlpha(110) * a)));
            renderer.quad(x, yy, width * v, bh, fade(tmpBar, barColor.get(), a));
            yy += bh;
        }
        if (planLines.get() > 0 && running && !isInEditor()) {
            if (now - planAt > 1_000_000_000L) {
                planAt = now;
                plan = BaritoneAPI.getProvider().getPrimaryBaritone().getGetItemProcess().why();
            }
            for (int i = 0; i < Math.min(planLines.get(), plan.size()); i++) {
                double lx = renderer.text(plan.get(i), x, yy, fade(tmpLabel, labelColor.get(), a), shadow.get(), s * 0.85);
                width = Math.max(width, lx - x);
                yy += h * 0.85;
            }
        }
        setSize(width, yy - y);
    }

    /** Fade into a reused colour (S77: no per-frame allocation). */
    private static Color fade(Color into, Color c, double a) {
        return into.set(c.r, c.g, c.b, (int) (c.a * Math.max(0, Math.min(1, a))));
    }
}
