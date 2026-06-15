/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule;
import meteordevelopment.meteorclient.systems.modules.combat.SmartCombatModule.CombatMode;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

import java.util.Set;

public class SmartCombatCommand extends Command {
    public SmartCombatCommand() {
        super("smartcombat", "Manages the SmartCombat system.", "sc");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder
            // .smartcombat - toggle module
            .executes(context -> {
                SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                module.toggle();
                module.sendToggledMsg();
                return SINGLE_SUCCESS;
            })
            // .smartcombat mode <smart|aggressive|defensive>
            .then(literal("mode")
                .then(literal("smart")
                    .executes(context -> {
                        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                        @SuppressWarnings("unchecked")
                        Setting<CombatMode> setting = (Setting<CombatMode>) (Setting<?>) module.settings.get("combat-mode");
                        setting.set(CombatMode.SMART);
                        info("Combat mode set to smart.");
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("aggressive")
                    .executes(context -> {
                        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                        @SuppressWarnings("unchecked")
                        Setting<CombatMode> setting = (Setting<CombatMode>) (Setting<?>) module.settings.get("combat-mode");
                        setting.set(CombatMode.AGGRESSIVE);
                        info("Combat mode set to aggressive.");
                        return SINGLE_SUCCESS;
                    })
                )
                .then(literal("defensive")
                    .executes(context -> {
                        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                        @SuppressWarnings("unchecked")
                        Setting<CombatMode> setting = (Setting<CombatMode>) (Setting<?>) module.settings.get("combat-mode");
                        setting.set(CombatMode.DEFENSIVE);
                        info("Combat mode set to defensive.");
                        return SINGLE_SUCCESS;
                    })
                )
            )
            // .smartcombat range <value>
            .then(literal("range")
                .then(argument("value", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1, 8))
                    .executes(context -> {
                        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                        double value = context.getArgument("value", Double.class);
                        @SuppressWarnings("unchecked")
                        Setting<Double> setting = (Setting<Double>) (Setting<?>) module.settings.get("range");
                        setting.set(value);
                        info("Range set to " + value + ".");
                        return SINGLE_SUCCESS;
                    })
                )
            )
            // .smartcombat targets add/remove/list
            .then(literal("targets")
                .then(literal("add")
                    .then(argument("entity", StringArgumentType.string())
                        .suggests((context, suggestionsBuilder) -> SharedSuggestionProvider.suggest(
                            BuiltInRegistries.ENTITY_TYPE.stream()
                                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),
                            suggestionsBuilder
                        ))
                        .executes(context -> {
                            SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                            String entityName = context.getArgument("entity", String.class);
                            EntityType<?> entityType = Setting.parseId(BuiltInRegistries.ENTITY_TYPE, entityName);
                            if (entityType == null) {
                                error("Invalid entity type: " + entityName);
                                return SINGLE_SUCCESS;
                            }
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("targets");
                            setting.get().add(entityType);
                            info("Added (highlight)" + entityName + "(default) to target list.");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("remove")
                    .then(argument("entity", StringArgumentType.string())
                        .suggests((context, suggestionsBuilder) -> {
                            SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("targets");
                            return SharedSuggestionProvider.suggest(
                                setting.get().stream()
                                    .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()),
                                suggestionsBuilder
                            );
                        })
                        .executes(context -> {
                            SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                            String entityName = context.getArgument("entity", String.class);
                            EntityType<?> entityType = Setting.parseId(BuiltInRegistries.ENTITY_TYPE, entityName);
                            if (entityType == null) {
                                error("Invalid entity type: " + entityName);
                                return SINGLE_SUCCESS;
                            }
                            @SuppressWarnings("unchecked")
                            Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("targets");
                            setting.get().remove(entityType);
                            info("Removed (highlight)" + entityName + "(default) from target list.");
                            return SINGLE_SUCCESS;
                        })
                    )
                )
                .then(literal("list")
                    .executes(context -> {
                        SmartCombatModule module = Modules.get().get(SmartCombatModule.class);
                        @SuppressWarnings("unchecked")
                        Setting<Set<EntityType<?>>> setting = (Setting<Set<EntityType<?>>>) (Setting<?>) module.settings.get("targets");
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