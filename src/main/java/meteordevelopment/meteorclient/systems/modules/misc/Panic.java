/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.ArrayList;
import java.util.List;

/**
 * One-key "disable everything". Bind it like any module; activating it turns off every other active
 * module (optionally keeping Render modules and a whitelist), then turns itself back off.
 */
public class Panic extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> keepRender = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-render").description("Leave Render-category modules enabled.").defaultValue(true).build());

    private final Setting<List<Module>> keep = sgGeneral.add(new ModuleListSetting.Builder()
        .name("keep").description("Modules that Panic never disables.").build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify").description("Print how many modules were disabled.").defaultValue(true).build());

    private final List<Module> lastDisabled = new ArrayList<>();

    public Panic() {
        super(Categories.Misc, "panic", "Instantly disables all other modules. Bind it to a key.");
    }

    /** Pure decision so it can be tested without a module registry. */
    public static boolean shouldDisable(boolean isSelf, boolean active, boolean isRender, boolean keepRender, boolean whitelisted) {
        if (isSelf || !active || whitelisted) return false;
        return !(isRender && keepRender);
    }

    @Override
    public void onActivate() {
        lastDisabled.clear();
        for (Module m : new ArrayList<>(Modules.get().getActive())) {
            if (shouldDisable(m == this, m.isActive(), m.category == Categories.Render, keepRender.get(), keep.get().contains(m))) {
                m.disable();
                lastDisabled.add(m);
            }
        }
        if (notify.get()) info("Disabled %d module(s).", lastDisabled.size());
        toggle();
    }
}
