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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
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
    private final SettingGroup sgChests = settings.createGroup("Chests");
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgLimits = settings.createGroup("Limits");
    private final SettingGroup sgInterop = settings.createGroup("Interop");

    public enum HostileAction { Flee, Pause, Ignore }

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

    // chests (A11/A24)

    private final Setting<Boolean> useChests = sgChests.add(new BoolSetting.Builder()
        .name("use-chests")
        .description("Take items from your own chests, barrels and shulker boxes when that's quicker than making them.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> protectChestNames = sgChests.add(new StringListSetting.Builder()
        .name("protect-chest-names")
        .description("Containers whose name contains any of these (case-insensitive) are never touched. Rename a chest in an anvil to protect it.")
        .defaultValue(new ArrayList<>(BaritoneAPI.getSettings().getItemProtectChestNames.value))
        .visible(useChests::get)
        .build()
    );

    private final Setting<List<String>> protectChestPositions = sgChests.add(new StringListSetting.Builder()
        .name("protect-chest-positions")
        .description("Container positions (x,y,z) that are never touched.")
        .defaultValue(new ArrayList<>())
        .visible(useChests::get)
        .build()
    );

    private final Setting<Boolean> useEnderChest = sgChests.add(new BoolSetting.Builder()
        .name("use-ender-chest")
        .description("Also take from / stash into your ender chest.")
        .defaultValue(false)
        .visible(useChests::get)
        .build()
    );

    private final Setting<Integer> containerRadius = sgChests.add(new IntSetting.Builder()
        .name("container-radius")
        .description("How far a remembered container may be to be used.")
        .defaultValue(64)
        .min(8)
        .sliderRange(16, 256)
        .visible(useChests::get)
        .build()
    );

    private final Setting<Boolean> scanContainers = sgChests.add(new BoolSetting.Builder()
        .name("peek-nearby-chests")
        .description("Open a few nearby chests that were never opened, to learn what they hold.")
        .defaultValue(true)
        .visible(useChests::get)
        .build()
    );

    private final Setting<Boolean> stashOverflow = sgChests.add(new BoolSetting.Builder()
        .name("stash-overflow")
        .description("When the inventory is full, put items the plan doesn't need into a nearby unprotected chest.")
        .defaultValue(true)
        .visible(useChests::get)
        .build()
    );

    private final Setting<Boolean> dropJunk = sgBehaviour.add(new BoolSetting.Builder()
        .name("drop-junk")
        .description("Throw away junk items the plan doesn't need when the inventory is full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> junkItems = sgBehaviour.add(new ItemListSetting.Builder()
        .name("junk-items")
        .description("Items that may be thrown away when space is needed.")
        .defaultValue(new ArrayList<>(BaritoneAPI.getSettings().getItemJunkItems.value))
        .visible(dropJunk::get)
        .build()
    );

    private final Setting<Boolean> explore = sgBehaviour.add(new BoolSetting.Builder()
        .name("explore")
        .description("Explore (at the best Y for ores) when a needed block isn't known nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowNether = sgBehaviour.add(new BoolSetting.Builder()
        .name("allow-nether")
        .description("Allow plans that go to the Nether through a known portal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> allowEnd = sgBehaviour.add(new BoolSetting.Builder()
        .name("allow-end")
        .description("Allow plans that go to the End through a known end portal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> returnHome = sgBehaviour.add(new BoolSetting.Builder()
        .name("return-home")
        .description("Go back to the starting dimension when done.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resume = sgBehaviour.add(new BoolSetting.Builder()
        .name("resume")
        .description("Resume an unfinished goal after reconnecting.")
        .defaultValue(true)
        .build()
    );

    // safety (A45/A46)

    private final Setting<HostileAction> hostileAction = sgSafety.add(new EnumSetting.Builder<HostileAction>()
        .name("hostile-action")
        .description("What to do when hostile mobs come close.")
        .defaultValue(HostileAction.Flee)
        .build()
    );

    private final Setting<Boolean> autoEat = sgSafety.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Let Baritone eat when hungry (off automatically while Meteor's AutoEat is on).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> avoidNight = sgSafety.add(new BoolSetting.Builder()
        .name("avoid-night")
        .description("Pause at night while under the open sky.")
        .defaultValue(false)
        .build()
    );

    // limits (A49)

    private final Setting<Integer> maxMinutes = sgLimits.add(new IntSetting.Builder()
        .name("max-minutes")
        .description("Stop after this many minutes (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Integer> maxDistance = sgLimits.add(new IntSetting.Builder()
        .name("max-distance")
        .description("Stop when this far (blocks, horizontally) from the start (0 = no limit).")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 2000)
        .build()
    );

    private final Setting<Integer> minY = sgLimits.add(new IntSetting.Builder()
        .name("min-y")
        .description("Never go below this Y (-9999 = no limit).")
        .defaultValue(-9999)
        .range(-9999, 320)
        .sliderRange(-64, 320)
        .build()
    );

    // interop

    private final Setting<Boolean> combatHandoff = sgInterop.add(new BoolSetting.Builder()
        .name("combat-handoff")
        .description("Turn on Combat Brain while Get Item pauses for hostile mobs, and off again afterwards.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseConflicting = sgInterop.add(new BoolSetting.Builder()
        .name("pause-conflicting")
        .description("Turn off Infinity Miner, Vein Miner, Nuker, Auto Smelter and EChest Farmer while running, and back on afterwards.")
        .defaultValue(true)
        .build()
    );

    private IGetItemProcess process;
    private String lastStep = "";
    private final List<Module> pausedModules = new ArrayList<>();
    private boolean weEnabledCombat;
    private int calmTicks;

    public GetItem() {
        super(Categories.World, "get-item", "Gets an item for you: takes it from your chests or mines, crafts and smelts whatever it takes (Baritone #get).", "get", "auto-acquire", "obtain");
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
        s.getItemUseContainers.value = useChests.get();
        s.getItemProtectChestNames.value = new ArrayList<>(GetItemSpec.chestNames(protectChestNames.get()));
        s.getItemProtectChestPositions.value = new ArrayList<>(GetItemSpec.chestPositions(protectChestPositions.get()));
        s.getItemUseEnderChest.value = useEnderChest.get();
        s.getItemContainerRadius.value = containerRadius.get();
        s.getItemScanNearbyContainers.value = scanContainers.get();
        s.getItemStashOverflow.value = stashOverflow.get();
        s.getItemDropJunk.value = dropJunk.get();
        s.getItemJunkItems.value = new ArrayList<>(junkItems.get());
        s.getItemExplore.value = explore.get();
        s.getItemAllowNether.value = allowNether.get();
        s.getItemAllowEnd.value = allowEnd.get();
        s.getItemReturnHome.value = returnHome.get();
        s.getItemResume.value = resume.get();
        s.getItemHostileAction.value = hostileAction.get().name().toLowerCase(java.util.Locale.ROOT);
        s.getItemAutoEat.value = GetItemSpec.baritoneAutoEat(autoEat.get(), Modules.get().isActive(AutoEat.class));
        s.getItemAvoidNight.value = avoidNight.get();
        s.getItemMaxMinutes.value = maxMinutes.get();
        s.getItemMaxDistance.value = maxDistance.get();
        s.getItemMinY.value = minY.get();
        lastStep = "";
        weEnabledCombat = false;
        calmTicks = 0;
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
            return;
        }
        if (pauseConflicting.get()) {
            for (Module m : Modules.get().getAll()) {
                if (m != this && m.isActive() && GetItemSpec.CONFLICTING.contains(m.name)) {
                    m.toggle();
                    pausedModules.add(m);
                }
            }
            if (!pausedModules.isEmpty()) info("Paused %d conflicting module(s) while running.", pausedModules.size());
        }
    }

    /** Progress line for the HUD, or null when idle. */
    public String progress() {
        return process == null ? null : process.progressLine();
    }

    @Override
    public void onDeactivate() {
        if (process != null && process.isActive()) process.cancel();
        process = null;
        for (Module m : pausedModules) if (!m.isActive()) m.toggle();
        pausedModules.clear();
        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        if (weEnabledCombat && cb != null && cb.isActive()) cb.toggle();
        weEnabledCombat = false;
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
        handoffCombat();
        if (announceSteps.get()) {
            String status = process.status();
            String key = GetItemSpec.stepKey(status);
            if (!key.equals(lastStep)) {
                lastStep = key;
                info("%s", status);
            }
        }
    }

    private void handoffCombat() {
        CombatBrainModule cb = Modules.get().get(CombatBrainModule.class);
        if (cb == null) return;
        boolean paused = process.isPausedForHostiles();
        calmTicks = paused ? 0 : calmTicks + 1;
        switch (GetItemSpec.handoff(combatHandoff.get(), paused, cb.isActive(), weEnabledCombat, calmTicks, 100)) {
            case ENABLE -> {
                cb.toggle();
                weEnabledCombat = true;
                info("Hostiles nearby: Combat Brain on.");
            }
            case DISABLE -> {
                cb.toggle();
                weEnabledCombat = false;
                info("Clear: Combat Brain off, continuing.");
            }
            default -> {
            }
        }
    }
}
