/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import java.util.regex.Pattern;

/** Pure bot-detection rules for {@link AntiBot}; no Minecraft types so it can be unit tested. */
public final class AntiBotHeuristics {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private AntiBotHeuristics() {}

    /** Observed facts about one player entity. */
    public record Signals(String name, boolean inTabList, boolean tabGameModeKnown, int latency,
                          int ticksExisted, boolean invisible, boolean hasArmor, boolean nameMatchesTab) {}

    /** Which checks are enabled. */
    public record Config(boolean tabList, boolean invalidName, boolean zeroPing, int minTicksExisted,
                         boolean invisible, boolean noArmor, boolean tabNameMismatch) {}

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public static boolean isBot(Signals s, Config c) {
        if (c.tabList() && !s.inTabList()) return true;
        if (c.invalidName() && !isValidName(s.name())) return true;
        if (c.zeroPing() && s.inTabList() && s.latency() <= 0) return true;
        if (c.tabList() && s.inTabList() && !s.tabGameModeKnown()) return true;
        if (c.minTicksExisted() > 0 && s.ticksExisted() < c.minTicksExisted()) return true;
        if (c.invisible() && s.invisible()) return true;
        if (c.noArmor() && !s.hasArmor()) return true;
        if (c.tabNameMismatch() && s.inTabList() && !s.nameMatchesTab()) return true;
        return false;
    }
}
