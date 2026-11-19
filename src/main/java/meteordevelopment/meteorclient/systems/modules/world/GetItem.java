/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IGetItemProcess;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Get Item (plan Part 4): pick an item (or a tag) and an amount; Baritone's GetItemProcess plans and performs the
 * mining, crafting and smelting needed. This module is a thin front end over {@code #get}.
 */
public class GetItem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBehaviour = settings.createGroup("Behaviour");

    private final Setting<Item> item = sgGeneral.add(new ItemSetting.Builder()
        .name("item")
        .description("The item to get.")
        .defaultValue(Items.IRON_PICKAXE)
        .filter(i -> i != Items.AIR)
        .build()
    );

    private final Setting<String> tag = sgGeneral.add(new StringSetting.Builder()
        .name("tag")
        .description("Get any item of this tag instead (e.g. logs, planks, wool). Leave empty to use the item.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> amount = sgGeneral.add(new IntSetting.Builder()
        .name("amount")
        .description("Total amount to have in your inventory.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> planOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("plan-only")
        .description("Only print the plan (steps, ETA, risks) without doing anything.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Item>> protectItems = sgBehaviour.add(new ItemListSetting.Builder()
        .name("protect-items")
        .description("Items Get Item never uses up or mines with.")
        .defaultValue(new ArrayList<>(BaritoneAPI.getSettings().getItemProtectItems.value))
        .build()
    );

    private final Setting<Boolean> placeStations = sgBehaviour.add(new BoolSetting.Builder()
        .name("place-stations")
        .description("Place a crafting table or furnace when none is nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useRecipeBook = sgBehaviour.add(new BoolSetting.Builder()
        .name("use-recipe-book")
        .description("Fill crafting grids through the recipe book when possible (otherwise individual clicks).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> announceSteps = sgBehaviour.add(new BoolSetting.Builder()
        .name("announce-steps")
        .description("Print each step as it starts.")
        .defaultValue(true)
        .build()
    );

    private IGetItemProcess process;
    private String lastStep = "";

    public GetItem() {
        super(Categories.World, "get-item", "Gets an item for you: mines, crafts and smelts whatever it takes (Baritone #get).", "get", "auto-acquire", "obtain");
    }

    private String target() {
        return GetItemSpec.target(tag.get(), BuiltInRegistries.ITEM.getKey(item.get()).toString());
    }

    @Override
    public void onActivate() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        process = baritone.getGetItemProcess();
        baritone.api.Settings s = BaritoneAPI.getSettings();
        s.getItemProtectItems.value = new ArrayList<>(protectItems.get());
        s.getItemPlaceStations.value = placeStations.get();
        s.getItemUseRecipeBook.value = useRecipeBook.get();
        lastStep = "";
        String target = target();
        if (planOnly.get()) {
            for (String line : process.plan(target, amount.get())) info("%s", line);
            process = null;
            toggle();
            return;
        }
        String err = process.get(target, amount.get());
        if (err != null) {
            error("Can't get %s: %s", target, err);
            process = null;
            toggle();
        }
    }

    @Override
    public void onDeactivate() {
        if (process != null && process.isActive()) process.cancel();
        process = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (process == null) return;
        if (!process.isActive()) {
            String status = process.status();
            if (status.startsWith("idle (last: ")) warning("%s", status.substring(12, status.length() - 1));
            else info("Done.");
            toggle();
            return;
        }
        if (announceSteps.get()) {
            String status = process.status();
            String key = GetItemSpec.stepKey(status);
            if (!key.equals(lastStep)) {
                lastStep = key;
                info("%s", status);
            }
        }
    }
}
