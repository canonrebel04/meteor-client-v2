/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

public class SmartCombatCommand extends Command {
    public SmartCombatCommand() {
        super("combatmode", "Manages the CombatMode AI brain.", "combat-mode", "combat-brain", "smartcombat", "sc", "cm");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder
            // .combatmode - toggle module
            .executes(context -> {
                CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                if (module != null) {
                    module.toggle();
                    module.sendToggledMsg();
                }
                return SINGLE_SUCCESS;
            })
            // .combatmode status
            .then(literal("status")
                .executes(context -> {
                    CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                    if (module == null) {
                        error("CombatBrain module not found.");
                        return SINGLE_SUCCESS;
                    }
                    info("CombatMode: (highlight)%s(default) | State: (highlight)%s(default) | Mode: (highlight)%s(default)",
                        module.isActive() ? "ENABLED" : "DISABLED",
                        module.getState().name(),
                        module.getCombatMode().name()
                    );
                    if (module.getCurrentTarget() != null) {
                        info("Current Target: (highlight)%s(default) | Phase: (highlight)%s",
                            module.getCurrentTarget().getName().getString(),
                            module.getStrikePhase().name()
                        );
                    }
                    return SINGLE_SUCCESS;
                })
            )
            // .combatmode range <value>
            .then(literal("range")
                .then(argument("value", DoubleArgumentType.doubleArg(8, 256))
                    .executes(context -> {
                        CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                        if (module == null) return SINGLE_SUCCESS;
                        double value = context.getArgument("value", Double.class);
                        @SuppressWarnings("unchecked")
                        Setting<Double> setting = (Setting<Double>) (Setting<?>) module.settings.get("acquire-range");
                        if (setting != null) {
                            setting.set(value);
                            info("Acquire range set to (highlight)%.1f(default)m.", value);
                        }
                        return SINGLE_SUCCESS;
                    })
                )
            )
            // .combatmode targets add/remove/list
            .then(literal("targets")
                .then(literal("add")
                    .then(argument("entity", StringArgumentType.string())
                        .suggests((context, suggestionsBuilder) -> SharedSuggestionProvider.suggest(
                            BuiltInRegistries.ENTITY_TYPE.stream()
                                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),
                            suggestionsBuilder
                        ))
                        .executes(context -> {
                            CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                            if (module == null) return SINGLE_SUCCESS;
                            String entityName = context.getArgument("entity", String.class);
                            EntityType<?> entityType = Setting.parseId(BuiltInRegistries.ENTITY_TYPE, entityName);
                            if (entityType == null) {
                                error("Invalid entity type: " + entityName);
                                return SINGLE_SUCCESS;
                            }
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("entities");
                            if (setting != null) {
                                setting.get().add(entityType);
                                info("Added (highlight)" + entityName + "(default) to target list.");
                            }
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("remove")
                    .then(argument("entity", StringArgumentType.string())
                        .suggests((context, suggestionsBuilder) -> {
                            CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                            if (module == null) return suggestionsBuilder.buildFuture();
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("entities");
                            if (setting == null) return suggestionsBuilder.buildFuture();
                            return SharedSuggestionProvider.suggest(
                                setting.get().stream()
                                    .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),
                                suggestionsBuilder
                            );
                        })
                        .executes(context -> {
                            CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                            if (module == null) return SINGLE_SUCCESS;
                            String entityName = context.getArgument("entity", String.class);
                            EntityType<?> entityType = Setting.parseId(BuiltInRegistries.ENTITY_TYPE, entityName);
                            if (entityType == null) {
                                error("Invalid entity type: " + entityName);
                                return SINGLE_SUCCESS;
                            }
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("entities");
                            if (setting != null) {
                                setting.get().remove(entityType);
                                info("Removed (highlight)" + entityName + "(default) from target list.");
                            }
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("list")
                    .executes(context -> {
                        CombatBrainModule module = Modules.get().get(CombatBrainModule.class);
                        if (module == null) return SINGLE_SUCCESS;
                        @SuppressWarnings("unchecked")
                        Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("entities");
                        if (setting == null) return SINGLE_SUCCESS;
                        StringBuilder sb = new StringBuilder("Targets: ");
                        for (EntityType<?> type : setting.get()) {
                            sb.append(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()).append(", ");
                        }
                        if (sb.length() > 8) sb.setLength(sb.length() - 2);
                        info(sb.toString());
                        return SINGLE_SUCCESS;
                    })
                )
            );
    }
}