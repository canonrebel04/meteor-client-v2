/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

public class CombatBrainCommand extends Command {
    public CombatBrainCommand() {
        super("combat-brain", "Manages the CombatBrain AI system.", "cb", "combatmode");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder
            // .combat-brain - toggle module
            .executes(context -> {
                CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                cb.toggle();
                cb.sendToggledMsg();
                return SINGLE_SUCCESS;
            })

            // .combat-brain mode <follow|flee|both|none>
            .then(literal("mode")
                .then(literal("follow")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        cb.info("(highlight)Follow mode(highlight) set: tracking nearest target.");
                        cb.info("Engaging hostiles within range.");
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("flee")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        cb.info("(highlight)Flee mode(highlight) set: retreating from threats.");
                        if (!cb.isActive()) cb.toggle();
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("both")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        cb.info("(highlight)Both mode(highlight) set: follow when safe, flee when threatened.");
                        if (!cb.isActive()) cb.toggle();
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("none")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        cb.info("(highlight)None mode(highlight): combat brain passive.");
                        if (cb.isActive()) cb.toggle();
                        return SINGLE_SUCCESS;
                    })
                )
            )

            // .combat-brain range <value>
            .then(literal("range")
                .then(argument("value", DoubleArgumentType.doubleArg(1, 16))
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        double value = context.getArgument("value", Double.class);
                        @SuppressWarnings("unchecked")
                        meteordevelopment.meteorclient.settings.Setting<Double> setting =
                            (meteordevelopment.meteorclient.settings.Setting<Double>)
                            (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-range");
                        setting.set(value);
                        info("Target range set to (highlight)" + value + "(default).");
                        return SINGLE_SUCCESS;
                    })
                )
            )

            // .combat-brain follow <player|players|entity|entities> [name]
            .then(literal("follow")
                .then(literal("player")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        @SuppressWarnings("unchecked")
                        meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                            (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                            (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                        setting.set(true);
                        if (!cb.isActive()) cb.toggle();
                        info("Following nearest player.");
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("name", StringArgumentType.string())
                        .executes(context -> {
                            String name = context.getArgument("name", String.class);
                            CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                            @SuppressWarnings("unchecked")
                            meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                                (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                                (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                            setting.set(true);
                            if (!cb.isActive()) cb.toggle();
                            info("Following player (highlight)" + name + "(default).");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("players")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        @SuppressWarnings("unchecked")
                        meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                            (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                            (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                        setting.set(true);
                        if (!cb.isActive()) cb.toggle();
                        info("Following all players.");
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("name", StringArgumentType.string())
                        .executes(context -> {
                            String name = context.getArgument("name", String.class);
                            CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                            @SuppressWarnings("unchecked")
                            meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                                (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                                (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                            setting.set(true);
                            if (!cb.isActive()) cb.toggle();
                            info("Following all players, tracking (highlight)" + name + "(default).");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("entity")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        @SuppressWarnings("unchecked")
                        meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                            (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                            (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                        setting.set(false);
                        if (!cb.isActive()) cb.toggle();
                        info("Following nearest hostile entity.");
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("name", StringArgumentType.string())
                        .executes(context -> {
                            String name = context.getArgument("name", String.class);
                            CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                            @SuppressWarnings("unchecked")
                            meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                                (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                                (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                            setting.set(false);
                            if (!cb.isActive()) cb.toggle();
                            info("Following entity (highlight)" + name + "(default).");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("entities")
                    .executes(context -> {
                        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                        @SuppressWarnings("unchecked")
                        meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                            (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                            (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                        setting.set(false);
                        if (!cb.isActive()) cb.toggle();
                        info("Following all hostile entities.");
                        return SINGLE_SUCCESS;
                    })
                    .then(argument("name", StringArgumentType.string())
                        .executes(context -> {
                            String name = context.getArgument("name", String.class);
                            CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                            @SuppressWarnings("unchecked")
                            meteordevelopment.meteorclient.settings.Setting<Boolean> setting =
                                (meteordevelopment.meteorclient.settings.Setting<Boolean>)
                                (meteordevelopment.meteorclient.settings.Setting<?>) cb.settings.get("target-players");
                            setting.set(false);
                            if (!cb.isActive()) cb.toggle();
                            info("Following all hostile entities, tracking (highlight)" + name + "(default).");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
            )

            // .combat-brain flee
            .then(literal("flee")
                .executes(context -> {
                    CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                    if (!cb.isActive()) cb.toggle();
                    info("(highlight)Flee mode(highlight) activated. Retreating from all threats.");
                    return SINGLE_SUCCESS;
                })
            )

            // .combat-brain stop
            .then(literal("stop")
                .executes(context -> {
                    CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                    if (cb.isActive()) {
                        cb.toggle();
                        info("CombatBrain stopped and disabled.");
                    } else {
                        warning("CombatBrain is already disabled.");
                    }
                    return SINGLE_SUCCESS;
                })
            )

            // .combat-brain analyze
            .then(literal("analyze")
                .executes(context -> {
                    CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                    info("CombatBrain Status:");
                    info(" Module (highlight)" + (cb.isActive() ? "active" : "inactive"));
                    info(" State: (highlight)" + cb.getInfoString());
                    return SINGLE_SUCCESS;
                })
            )

            // .combat-brain settings
            .then(literal("settings")
                .executes(context -> {
                    CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
                    info("(highlight)CombatBrain Settings:(default)");
                    info(" target-range: (highlight)" + cb.settings.get("target-range").get());
                    info(" target-players: (highlight)" + cb.settings.get("target-players").get());
                    info(" target-friendly: (highlight)" + cb.settings.get("target-friendly").get());
                    info(" auto-modules: (highlight)" + cb.settings.get("auto-modules").get());
                    info(" engage-threshold: (highlight)" + cb.settings.get("engage-threshold").get());
                    info(" flee-threshold: (highlight)" + cb.settings.get("flee-threshold").get());
                    info(" flee-distance: (highlight)" + cb.settings.get("flee-distance").get());
                    info(" follow-distance: (highlight)" + cb.settings.get("follow-distance").get());
                    info(" criticals: (highlight)" + cb.settings.get("criticals").get());
                    info(" crystal: (highlight)" + cb.settings.get("crystal").get());
                    info(" analyze-gear: (highlight)" + cb.settings.get("analyze-gear").get());
                    info(" viability-check: (highlight)" + cb.settings.get("viability-check").get());
                    return SINGLE_SUCCESS;
                })
            );
    }
}
