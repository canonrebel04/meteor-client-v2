/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.ProfileArgumentType;
import meteordevelopment.meteorclient.systems.profiles.Profile;
import meteordevelopment.meteorclient.systems.profiles.Profiles;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class ProfilesCommand extends Command {

    public ProfilesCommand() {
        super("profiles", "Loads and saves profiles.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("load").then(argument("profile", ProfileArgumentType.create()).executes(context -> {
            Profile profile = ProfileArgumentType.get(context);

            if (profile != null) {
                profile.load();
                info("Loaded profile (highlight)%s(default).", profile.name.get());
            }

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("save").then(argument("profile", ProfileArgumentType.create()).executes(context -> {
            Profile profile = ProfileArgumentType.get(context);

            if (profile != null) {
                profile.save();
                info("Saved profile (highlight)%s(default).", profile.name.get());
            }

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("delete").then(argument("profile", ProfileArgumentType.create()).executes(context -> {
            Profile profile = ProfileArgumentType.get(context);

            if (profile != null) {
                Profiles.get().remove(profile);
                info("Deleted profile (highlight)%s(default).", profile.name.get());
            }

            return SINGLE_SUCCESS;
        })));

        builder.then(literal("default")
            .then(literal("none").executes(context -> {
                Profiles.get().setFallback(null);
                info("Cleared the default fallback profile.");
                return SINGLE_SUCCESS;
            }))
            .then(argument("profile", ProfileArgumentType.create()).executes(context -> {
                Profile profile = ProfileArgumentType.get(context);
                if (profile != null) {
                    Profiles.get().setFallback(profile);
                    info("Default fallback profile set to (highlight)%s(default).", profile.name.get());
                }
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("match").then(argument("address", StringArgumentType.greedyString()).executes(context -> {
            String address = StringArgumentType.getString(context, "address");
            Profile p = Profiles.get().pickFor(address, true);
            if (p == null) info("No profile would load for (highlight)%s(default).", address);
            else info("Joining (highlight)%s(default) would load (highlight)%s(default).", address, p.name.get());
            return SINGLE_SUCCESS;
        })));
    }
}
