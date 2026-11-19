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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Get Item progress (plan A50): goal, have/need, step, current action or pause reason, ETA. */
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

    public GetItemHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        String line;
        if (isInEditor() || mc.player == null) line = "iron_pickaxe 0/1 · step 3/9 · mining iron_ore · ETA 2m 10s";
        else {
            line = BaritoneAPI.getProvider().getPrimaryBaritone().getGetItemProcess().progressLine();
            if (line == null) {
                if (hideIdle.get()) {
                    setSize(0, 0);
                    return;
                }
                line = "idle";
            }
        }
        double s = Hud.get().getTextScale();
        double x2 = renderer.text("Get Item: ", x, y, labelColor.get(), shadow.get(), s);
        x2 = renderer.text(line, x2, y, valueColor.get(), shadow.get(), s);
        setSize(x2 - x, renderer.textHeight(shadow.get(), s));
    }
}
