/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.config;

import meteordevelopment.meteorclient.utils.debug.DebugLog;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.LookPriorityBridge;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.smooth.SmoothProfile;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Config extends System<Config> {
    public final Settings settings = new Settings();

    private final SettingGroup sgVisual = settings.createGroup("Visual");
    private final SettingGroup sgModules = settings.createGroup("Modules");
    private final SettingGroup sgChat = settings.createGroup("Chat");
    private final SettingGroup sgMisc = settings.createGroup("Misc");
    private final SettingGroup sgSmooth = settings.createGroup("Smoothness");

    // Visual

    public final Setting<Boolean> customFont = sgVisual.add(new BoolSetting.Builder()
        .name("custom-font")
        .description("Use a custom font.")
        .defaultValue(true)
        .build()
    );

    public final Setting<FontFace> font = sgVisual.add(new FontFaceSetting.Builder()
        .name("font")
        .description("Custom font to use.")
        .visible(customFont::get)
        .onChanged(Fonts::load)
        .build()
    );

    public final Setting<Double> rainbowSpeed = sgVisual.add(new DoubleSetting.Builder()
        .name("rainbow-speed")
        .description("The global rainbow speed.")
        .defaultValue(0.5)
        .range(0, 10)
        .sliderMax(5)
        .build()
    );

    public final Setting<Boolean> titleScreenCredits = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-credits")
        .description("Show Meteor credits on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> titleScreenSplashes = sgVisual.add(new BoolSetting.Builder()
        .name("title-screen-splashes")
        .description("Show Meteor splash texts on title screen")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> customWindowTitle = sgVisual.add(new BoolSetting.Builder()
        .name("custom-window-title")
        .description("Show custom text in the window title.")
        .defaultValue(false)
        .onModuleActivated(_ -> mc.updateTitle())
        .onChanged(_ -> mc.updateTitle())
        .build()
    );

    public final Setting<String> customWindowTitleText = sgVisual.add(new StringSetting.Builder()
        .name("window-title-text")
        .description("The text it displays in the window title.")
        .visible(customWindowTitle::get)
        .defaultValue("Minecraft {mc_version} - {meteor.name} {meteor.version}")
        .onChanged(_ -> mc.updateTitle())
        .build()
    );

    public final Setting<SettingColor> friendColor = sgVisual.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("The color used to show friends.")
        .defaultValue(new SettingColor(0, 255, 180))
        .build()
    );

    public final Setting<Boolean> syncListSettingWidths = sgVisual.add(new BoolSetting.Builder()
        .name("sync-list-setting-widths")
        .description("Prevents the list setting screens from moving around as you add & remove elements.")
        .defaultValue(false)
        .build()
    );

    public final Setting<ButtonPosition> accountButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("accounts-button")
        .description("Controls the position and visibility of the accounts button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showAccountStatus = sgVisual.add(new BoolSetting.Builder()
        .name("account-status")
        .description("Shows information about the current account in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    public final Setting<ButtonPosition> proxiesButtonAnchor = sgVisual.add(new EnumSetting.Builder<ButtonPosition>()
        .name("proxies-button")
        .description("Controls the position and visibility of the proxies button in the multiplayer screen.")
        .defaultValue(ButtonPosition.TopRight)
        .build()
    );

    public final Setting<Boolean> showProxiesStatus = sgVisual.add(new BoolSetting.Builder()
        .name("proxy-status")
        .description("Shows information about the current proxy in the multiplayer screen.")
        .defaultValue(true)
        .build()
    );

    // Modules

    public final Setting<List<Module>> hiddenModules = sgModules.add(new ModuleListSetting.Builder()
        .name("hidden-modules")
        .description("Prevent these modules from being rendered as options in the clickgui.")
        .build()
    );

    public final Setting<Integer> moduleSearchCount = sgModules.add(new IntSetting.Builder()
        .name("module-search-count")
        .description("Amount of modules and settings to be shown in the module search bar.")
        .defaultValue(8)
        .min(1).sliderMax(12)
        .build()
    );

    public final Setting<Boolean> moduleAliases = sgModules.add(new BoolSetting.Builder()
        .name("search-module-aliases")
        .description("Whether or not module aliases will be used in the module search bar.")
        .defaultValue(true)
        .build()
    );

    // Chat

    public final Setting<String> prefix = sgChat.add(new StringSetting.Builder()
        .name("prefix")
        .description("Prefix.")
        .defaultValue(".")
        .build()
    );

    public final Setting<Boolean> chatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Sends chat feedback when meteor performs certain actions.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> deleteChatFeedback = sgChat.add(new BoolSetting.Builder()
        .name("delete-chat-feedback")
        .description("Delete previous matching chat feedback to keep chat clear.")
        .visible(chatFeedback::get)
        .defaultValue(true)
        .build()
    );

    // Misc

    public final Setting<Integer> rotationHoldTicks = sgMisc.add(new IntSetting.Builder()
        .name("rotation-hold")
        .description("Hold long to hold server side rotation when not sending any packets.")
        .defaultValue(4)
        .build()
    );

    public final Setting<Boolean> useTeamColor = sgMisc.add(new BoolSetting.Builder()
        .name("use-team-color")
        .description("Uses player's team color for rendering things like esp and tracers.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> debugLogging = sgMisc.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes meteor-client-v2 debug lines to latest.log. Also forced on by -Dmeteorv2.debug=true.")
        .defaultValue(false)
        .onChanged(DebugLog::setEnabled)
        .build()
    );

    // Smoothness (plan PS1)

    public final Setting<SmoothProfile> lookProfile = sgSmooth.add(new EnumSetting.Builder<SmoothProfile>()
        .name("look-profile")
        .description("How camera turns are smoothed. Legit-Smooth: human-like timed turns with a slow settle (safest on anticheat servers). Balanced: quicker. Fast: legacy snappy spring.")
        .defaultValue(SmoothProfile.LegitSmooth)
        .onChanged(_ -> syncSmoothProfile())
        .build()
    );

    public final Setting<Boolean> smoothFov = sgSmooth.add(new BoolSetting.Builder()
        .name("smooth-fov")
        .description("Ease sprint / speed / bow FOV changes in over a few ticks instead of jumping.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> fovSmoothTicks = sgSmooth.add(new IntSetting.Builder()
        .name("fov-smooth-ticks")
        .description("Time constant of the FOV smoothing in ticks.")
        .defaultValue(3)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(smoothFov::get)
        .build()
    );

    public final Setting<Boolean> reduceMotion = sgSmooth.add(new BoolSetting.Builder()
        .name("reduce-motion")
        .description("Accessibility: HUD/overlay animations snap instead of easing, pulses are static.")
        .defaultValue(false)
        .onChanged(v -> meteordevelopment.meteorclient.utils.render.anim.Anim.reduceMotion = v)
        .build()
    );

    public final Setting<Boolean> highContrast = sgSmooth.add(new BoolSetting.Builder()
        .name("high-contrast")
        .description("Accessibility: opaque HUD backgrounds behind animated elements.")
        .defaultValue(false)
        .onChanged(v -> meteordevelopment.meteorclient.utils.render.anim.Anim.highContrast = v)
        .build()
    );

    public final Setting<Boolean> adaptiveQuality = sgSmooth.add(new BoolSetting.Builder()
        .name("adaptive-quality")
        .description("Lower overlay detail (animations, extra lines) while frame times are over budget; restore when they recover.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> chatThrottleMs = sgSmooth.add(new IntSetting.Builder()
        .name("chat-throttle")
        .description("Collapse identical module chat messages repeated within this many milliseconds (0 disables).")
        .defaultValue(1000)
        .range(0, 10000)
        .sliderRange(0, 5000)
        .build()
    );

    public List<String> dontShowAgainPrompts = new ArrayList<>();

    public Config() {
        super("config");
    }

    public static Config get() {
        return Systems.get(Config.class);
    }

    /** Pushes {@link #lookProfile} to Baritone's {@code lookProfile} setting when Baritone is installed. */
    public void syncSmoothProfile() {
        if (!BaritoneUtils.IS_AVAILABLE) return;
        try {
            LookPriorityBridge.applyLookProfile(lookProfile.get().baritoneName);
        } catch (Throwable t) {
            DebugLog.warn("Config", "Could not apply look profile to Baritone: {}", t.toString());
        }
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        tag.putString("version", MeteorClient.VERSION.toString());
        tag.put("settings", settings.toTag());
        tag.put("dontShowAgainPrompts", listToTag(dontShowAgainPrompts));

        return tag;
    }

    @Override
    public Config fromTag(CompoundTag tag) {
        if (tag.contains("settings")) settings.fromTag(tag.getCompoundOrEmpty("settings"));
        if (tag.contains("dontShowAgainPrompts")) dontShowAgainPrompts = listFromTag(tag, "dontShowAgainPrompts");

        return this;
    }

    private ListTag listToTag(List<String> list) {
        ListTag nbt = new ListTag();
        for (String item : list) nbt.add(StringTag.valueOf(item));
        return nbt;
    }

    private List<String> listFromTag(CompoundTag tag, String key) {
        List<String> list = new ArrayList<>();
        for (Tag item : tag.getListOrEmpty(key)) list.add(item.asString().orElse(""));
        return list;
    }

    public enum ButtonPosition {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
        Hidden,
    }
}
