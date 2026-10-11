/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.debug;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Writes {@code debug-<timestamp>.log} in the game directory for bug reports. */
public final class DebugDump {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_VALUE_LENGTH = 300;

    private DebugDump() {
    }

    public static Path write() throws IOException {
        StringBuilder sb = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();

        section(sb, "meteor-client-v2 debug dump");
        sb.append("time: ").append(now).append('\n');
        sb.append("debug logging: ").append(DebugLog.isEnabled()).append(DebugLog.isForcedByProperty() ? " (-D" + DebugLog.PROPERTY + "=true)" : "").append('\n');

        section(sb, "Versions");
        sb.append("meteor: ").append(modVersion(MeteorClient.MOD_ID)).append(" (build ").append(MeteorClient.BUILD_NUMBER).append(")\n");
        sb.append("baritone: ").append(modVersion("baritone-meteor")).append(" / ").append(modVersion("baritone")).append('\n');
        sb.append("minecraft: ").append(modVersion("minecraft")).append('\n');
        sb.append("fabric loader: ").append(modVersion("fabricloader")).append('\n');
        sb.append("java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("os: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
        sb.append("path manager: ").append(PathManagers.get().getName()).append('\n');

        section(sb, "Mods");
        FabricLoader.getInstance().getAllMods().stream()
            .map(ModContainer::getMetadata)
            .sorted(Comparator.comparing(m -> m.getId()))
            .forEach(m -> sb.append(m.getId()).append(' ').append(m.getVersion().getFriendlyString()).append('\n'));

        section(sb, "Player");
        if (mc != null && mc.player != null && mc.level != null) {
            sb.append(String.format(Locale.ROOT, "dimension: %s%npos: %.2f, %.2f, %.2f%nhealth: %.1f%n",
                mc.level.dimension().identifier(), mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getHealth()));
        } else {
            sb.append("not in a world\n");
        }

        section(sb, "Active modules");
        try {
            for (Module module : Modules.get().getActive()) {
                sb.append(module.name).append('\n');
                for (SettingGroup group : module.settings) {
                    for (Setting<?> setting : group) {
                        sb.append("    ").append(group.name).append('/').append(setting.name).append(" = ")
                            .append(truncate(String.valueOf(setting.get()))).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("failed to read modules: ").append(t).append('\n');
        }

        section(sb, "Baritone");
        if (BaritoneUtils.IS_AVAILABLE) {
            try {
                BaritoneDebugInfo.append(sb);
            } catch (Throwable t) {
                // e.g. NoSuchMethodError when the obfuscated "standalone" Baritone jar is installed
                sb.append("failed to read Baritone state: ").append(t).append('\n');
            }
        } else {
            sb.append("not installed\n");
        }

        section(sb, "Last " + DebugLog.CAPACITY + " log lines");
        DebugLog.snapshot().forEach(line -> sb.append(line).append('\n'));

        Path file = FabricLoader.getInstance().getGameDir().resolve("debug-" + now.format(FILE_TIME) + ".log");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        DebugLog.debug("Debug", "Wrote debug dump to {}", file);
        return file;
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n===== ").append(title).append(" =====\n");
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("not loaded");
    }

    private static String truncate(String s) {
        s = s.replace('\n', ' ');
        return s.length() <= MAX_VALUE_LENGTH ? s : s.substring(0, MAX_VALUE_LENGTH) + "... (" + s.length() + " chars)";
    }
}
