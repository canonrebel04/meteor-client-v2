/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.debug.DebugDump;
import meteordevelopment.meteorclient.utils.debug.DebugLog;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.nio.file.Path;

public class DebugCommand extends Command {
    public DebugCommand() {
        super("debug", "Debug logging controls. 'dump' writes debug-<time>.log to the game folder.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            info("Debug logging is %s.", DebugLog.isEnabled() ? "on" : "off");
            return SINGLE_SUCCESS;
        });

        builder.then(literal("on").executes(_ -> {
            Config.get().debugLogging.set(true);
            info("Debug logging on. Debug lines now go to latest.log.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("off").executes(_ -> {
            Config.get().debugLogging.set(false);
            if (DebugLog.isForcedByProperty()) warning("Still on: forced by -D%s=true.", DebugLog.PROPERTY);
            else info("Debug logging off.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("dump").executes(_ -> {
            try {
                Path file = DebugDump.write();
                info("Wrote %s", file.toAbsolutePath());
            } catch (Exception e) {
                DebugLog.error("Debug", "Failed to write debug dump", e);
                error("Failed to write debug dump: %s", e.getMessage());
            }
            return SINGLE_SUCCESS;
        }));
    }
}
