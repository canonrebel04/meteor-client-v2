/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AIChat;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class AIChatCommand extends Command {
    public AIChatCommand() {
        super("ai", "Asks the AI chat companion something.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(argument("message", StringArgumentType.greedyString()).executes(context -> {
            AIChat aiChat = Modules.get().get(AIChat.class);
            if (aiChat == null || !aiChat.isActive()) {
                error("The ai-chat module must be enabled.");
                return SINGLE_SUCCESS;
            }

            aiChat.ask(context.getArgument("message", String.class));
            return SINGLE_SUCCESS;
        }));
    }
}
