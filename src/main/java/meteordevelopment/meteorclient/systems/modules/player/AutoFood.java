/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.CombatBrainModule;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodEvaluator;
import meteordevelopment.meteorclient.systems.modules.player.autofood.ServerProfile;
import meteordevelopment.meteorclient.systems.modules.player.autofood.tasks.*;
import meteordevelopment.meteorclient.utils.bot.BotNavigator;
import meteordevelopment.meteorclient.utils.bot.UtilityTaskRunner;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Autonomous food acquisition, upgrading, cooking, crafting, eating, and inventory manager
 * powered by the reusable Utility-AI {@link UtilityTaskRunner} framework.
 */
public class AutoFood extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSources = settings.createGroup("Sources");
    private final SettingGroup sgCooking = settings.createGroup("Cooking");
    private final SettingGroup sgSafety = settings.createGroup("Safety & Timing");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<ServerProfile> profile = sgGeneral.add(new EnumSetting.Builder<ServerProfile>()
        .name("profile")
        .description("Server environment preset (auto-adjusts safety, looting, and timing defaults).")
        .defaultValue(ServerProfile.Anarchy)
        .onChanged(this::applyProfile)
        .build()
    );

    private final Setting<Integer> targetFoodReserve = sgGeneral.add(new IntSetting.Builder()
        .name("target-reserve")
        .description("Total hunger points of good food to maintain before idling (1 steak = 8).")
        .defaultValue(64)
        .range(8, 256)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> upgradeFood = sgGeneral.add(new BoolSetting.Builder()
        .name("upgrade-food")
        .description("Keeps looking for higher-tier food (e.g. steak/golden carrots) even if reserve is met with low-tier food.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minUpgradeScore = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-upgrade-score")
        .description("Food quality threshold to consider food top-tier (cooked steak ~ 24, bread ~ 12.5).")
        .defaultValue(18.0)
        .range(6.0, 30.0)
        .sliderRange(10.0, 26.0)
        .visible(upgradeFood::get)
        .build()
    );

    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats the best available food when hunger or health drops low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> eatThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("eat-threshold")
        .description("Hunger level (0-20) at or below which AutoFood eats.")
        .defaultValue(14)
        .range(1, 19)
        .sliderRange(4, 19)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Double> lowHealthEatThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("low-health-eat")
        .description("Health threshold to eat for saturation regen even if hunger is above eat-threshold.")
        .defaultValue(14.0)
        .range(2.0, 20.0)
        .sliderRange(4.0, 20.0)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> autoHotbar = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-hotbar")
        .description("Automatically moves the best food item into the selected hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot (1-9) to keep your best food in.")
        .defaultValue(9)
        .range(1, 9)
        .sliderRange(1, 9)
        .visible(autoHotbar::get)
        .build()
    );

    private final Setting<Boolean> dropJunkFood = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-junk-food")
        .description("Drops hazardous food when inventory is nearly full and better food is held.")
        .defaultValue(true)
        .build()
    );

    // Sources
    private final Setting<Integer> searchRadius = sgSources.add(new IntSetting.Builder()
        .name("search-radius")
        .description("Block radius to search for chests, furnaces, campfires, water, and crops.")
        .defaultValue(48)
        .range(12, 96)
        .sliderRange(16, 80)
        .build()
    );

    private final Setting<Integer> huntRadius = sgSources.add(new IntSetting.Builder()
        .name("hunt-radius")
        .description("Block radius to search for animals and fish.")
        .defaultValue(48)
        .range(12, 96)
        .sliderRange(16, 80)
        .build()
    );

    private final Setting<Boolean> lootChests = sgSources.add(new BoolSetting.Builder()
        .name("loot-chests")
        .description("Loots food, ingredients, and fuel from nearby chests, barrels, and shulkers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> huntAnimals = sgSources.add(new BoolSetting.Builder()
        .name("hunt-animals")
        .description("Hunts cows, pigs, sheep, chickens, hoglins, and optional fish.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> huntFish = sgSources.add(new BoolSetting.Builder()
        .name("hunt-fish")
        .description("Hunts cod, salmon, and tropical fish underwater while monitoring oxygen to avoid drowning.")
        .defaultValue(true)
        .visible(huntAnimals::get)
        .build()
    );

    private final Setting<Boolean> preferFireAspect = sgSources.add(new BoolSetting.Builder()
        .name("prefer-fire-aspect")
        .description("Prefers Fire Aspect weapons when hunting so drops are pre-cooked.")
        .defaultValue(true)
        .visible(huntAnimals::get)
        .build()
    );

    private final Setting<Boolean> forageCrops = sgSources.add(new BoolSetting.Builder()
        .name("forage-crops")
        .description("Harvests mature crops, village haybales, melons, pumpkins, and berries.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoReplant = sgSources.add(new BoolSetting.Builder()
        .name("auto-replant")
        .description("Automatically replants harvested farmland crops.")
        .defaultValue(true)
        .visible(forageCrops::get)
        .build()
    );

    private final Setting<Boolean> craftFood = sgSources.add(new BoolSetting.Builder()
        .name("craft-food")
        .description("Crafts bread (from wheat/haybales), golden carrots, and mushroom stew.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoFish = sgSources.add(new BoolSetting.Builder()
        .name("auto-fish")
        .description("Fishes in nearby water when carrying a fishing rod.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tradeVillagers = sgSources.add(new BoolSetting.Builder()
        .name("trade-villagers")
        .description("Trades emeralds with Farmer and Butcher villagers for food.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> selfBootstrap = sgSources.add(new BoolSetting.Builder()
        .name("self-bootstrap")
        .description("Gathers logs or stone when stranded with raw food but missing fuel or a furnace.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> roamToFind = sgSources.add(new BoolSetting.Builder()
        .name("roam-to-find")
        .description("Roams toward safe land terrain when no food sources are nearby.")
        .defaultValue(true)
        .build()
    );

    // Cooking
    private final Setting<Boolean> cookFood = sgCooking.add(new BoolSetting.Builder()
        .name("cook-food")
        .description("Cooks raw meats, fish, potatoes, and kelp at campfires, smokers, or furnaces.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useCampfires = sgCooking.add(new BoolSetting.Builder()
        .name("use-campfires")
        .description("Uses nearby or hotbar campfires for zero-fuel cooking.")
        .defaultValue(true)
        .visible(cookFood::get)
        .build()
    );

    private final Setting<Boolean> placeFurnace = sgCooking.add(new BoolSetting.Builder()
        .name("place-furnace")
        .description("Places a furnace/smoker from your hotbar if none is nearby.")
        .defaultValue(true)
        .visible(cookFood::get)
        .build()
    );

    private final Setting<Boolean> retrieveFurnace = sgCooking.add(new BoolSetting.Builder()
        .name("retrieve-furnace")
        .description("Mines and picks up a furnace placed by AutoFood once cooking finishes.")
        .defaultValue(true)
        .visible(() -> cookFood.get() && placeFurnace.get())
        .build()
    );

    private final Setting<Boolean> useWoodFuel = sgCooking.add(new BoolSetting.Builder()
        .name("use-wood-fuel")
        .description("Allows logs/planks as furnace fuel when coal or charcoal is unavailable.")
        .defaultValue(true)
        .visible(cookFood::get)
        .build()
    );

    // Safety & Timing
    private final Setting<Boolean> claimSafe = sgSafety.add(new BoolSetting.Builder()
        .name("claim-safe")
        .description("Avoids looting containers near beds/signs/banners and avoids breaking non-replantable crops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseInCombat = sgSafety.add(new BoolSetting.Builder()
        .name("pause-in-combat")
        .description("Pauses gathering and cooking when hostile players or CombatBrain threats are nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> combatRange = sgSafety.add(new DoubleSetting.Builder()
        .name("combat-range")
        .description("Radius to check for threatening players before pausing AutoFood.")
        .defaultValue(12.0)
        .min(4.0)
        .sliderMax(24.0)
        .visible(pauseInCombat::get)
        .build()
    );

    private final Setting<Integer> actionDelayTicks = sgSafety.add(new IntSetting.Builder()
        .name("action-delay-ticks")
        .description("Minimum ticks between container/crafting interactions.")
        .defaultValue(3)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> maxClicksPerTick = sgSafety.add(new IntSetting.Builder()
        .name("max-clicks-per-tick")
        .description("Maximum container slot clicks per interaction cycle.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> stuckTimeoutTicks = sgSafety.add(new IntSetting.Builder()
        .name("stuck-timeout-ticks")
        .description("Ticks without movement progress before blacklisting the current target.")
        .defaultValue(50)
        .range(20, 200)
        .sliderRange(30, 120)
        .build()
    );

    private final Setting<Integer> minAirTicks = sgSafety.add(new IntSetting.Builder()
        .name("min-air-ticks")
        .description("Air supply ticks at or below which underwater fish hunting surfaces to breathe.")
        .defaultValue(90)
        .range(30, 240)
        .sliderRange(60, 180)
        .build()
    );

    // Render
    private final Setting<Boolean> renderTarget = sgRender.add(new BoolSetting.Builder()
        .name("render-target")
        .description("Highlights the active target block or entity.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the target highlight is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(renderTarget::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color for target highlight.")
        .defaultValue(new SettingColor(80, 220, 120, 40))
        .visible(renderTarget::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color for target highlight.")
        .defaultValue(new SettingColor(80, 220, 120, 200))
        .visible(renderTarget::get)
        .build()
    );

    private final BotNavigator navigator = new BotNavigator();
    private final FoodEvaluator evaluator = new FoodEvaluator();
    private final FoodContext ctx = new FoodContext(navigator, evaluator);
    private final UtilityTaskRunner<FoodContext> runner = new UtilityTaskRunner<>(List.of(
        new EatFoodTask(),
        new CollectDropsTask(),
        new CraftFoodTask(),
        new CampfireCookTask(),
        new CookAtFurnaceTask(),
        new LootChestTask(),
        new VillagerTradeTask(),
        new ForageCropsTask(),
        new HuntAnimalsTask(),
        new FishTask(),
        new BootstrapTask(),
        new RoamTask()
    ));

    private boolean pausedByCombat;

    public AutoFood() {
        super(Categories.Player, "auto-food", "Autonomously gathers, upgrades, crafts, cooks, and eats food via a Utility-AI runner.");
    }

    @Override
    public void onActivate() {
        pausedByCombat = false;
        navigator.clearAll();
        ctx.visitedContainers.clear();
        ctx.placedFurnacePos = null;
        ctx.clearTargets();
    }

    @Override
    public void onDeactivate() {
        runner.stopAll(ctx);
        navigator.clearAll();
        ctx.clearTargets();
    }

    private void applyProfile(ServerProfile selected) {
        switch (selected) {
            case Anarchy -> {
                claimSafe.set(false);
                lootChests.set(true);
                pauseInCombat.set(true);
                actionDelayTicks.set(3);
                maxClicksPerTick.set(4);
            }
            case SMP_Safe -> {
                claimSafe.set(true);
                lootChests.set(false);
                pauseInCombat.set(true);
                actionDelayTicks.set(5);
                maxClicksPerTick.set(2);
            }
            case Singleplayer -> {
                claimSafe.set(false);
                lootChests.set(true);
                pauseInCombat.set(false);
                actionDelayTicks.set(1);
                maxClicksPerTick.set(8);
            }
            case Custom -> {}
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        ctx.tickCounter++;
        navigator.tick();
        syncContextSettings();
        ctx.refreshInventorySnapshot();

        if (autoHotbar.get()) {
            evaluator.maintainBestHotbarFood(hotbarSlot.get());
        }
        if (dropJunkFood.get()) {
            evaluator.pruneJunkFoodIfFull();
        }

        if (pauseInCombat.get() && isCombatThreatNearby()) {
            if (!pausedByCombat) {
                runner.stopAll(ctx);
                pausedByCombat = true;
            }
            return;
        }
        pausedByCombat = false;

        runner.tick(ctx);
    }

    private void syncContextSettings() {
        ctx.targetFoodReserve = targetFoodReserve.get();
        ctx.upgradeFood = upgradeFood.get();
        ctx.searchRadius = searchRadius.get();
        ctx.huntRadius = huntRadius.get();
        ctx.minUpgradeScore = minUpgradeScore.get();
        ctx.lootChests = lootChests.get();
        ctx.huntAnimals = huntAnimals.get();
        ctx.huntFish = huntFish.get();
        ctx.preferFireAspect = preferFireAspect.get();
        ctx.forageCrops = forageCrops.get();
        ctx.autoReplant = autoReplant.get();
        ctx.craftFood = craftFood.get();
        ctx.roamToFind = roamToFind.get();
        ctx.cookFood = cookFood.get();
        ctx.placeFurnace = placeFurnace.get();
        ctx.retrieveFurnace = retrieveFurnace.get();
        ctx.useWoodFuel = useWoodFuel.get();
        ctx.useCampfires = useCampfires.get();
        ctx.autoEat = autoEat.get();
        ctx.eatThreshold = eatThreshold.get();
        ctx.lowHealthEatThreshold = lowHealthEatThreshold.get();
        ctx.autoFish = autoFish.get();
        ctx.selfBootstrap = selfBootstrap.get();
        ctx.tradeVillagers = tradeVillagers.get();
        ctx.claimSafe = claimSafe.get();
        ctx.actionDelayTicks = actionDelayTicks.get();
        ctx.maxClicksPerTick = maxClicksPerTick.get();
        ctx.stuckTimeoutTicks = stuckTimeoutTicks.get();
        ctx.minAirTicks = minAirTicks.get();
        ctx.resumeAirTicks = mc.player != null ? Math.max(ctx.minAirTicks + 60, (int) (mc.player.getMaxAirSupply() * 0.8)) : 240;
    }

    private boolean isCombatThreatNearby() {
        CombatBrainModule brain = Modules.get().get(CombatBrainModule.class);
        if (brain != null && brain.isActive() && brain.getCurrentTarget() != null) {
            return true;
        }
        if (mc.player.hurtTime > 0) {
            return true;
        }
        double passiveRangeSq = Math.min(combatRange.get(), 8.0);
        passiveRangeSq *= passiveRangeSq;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isCreative()) continue;
            if (!Friends.get().shouldAttack(player)) continue;
            if (player.distanceToSqr(mc.player) <= passiveRangeSq && (player.isSwinging() || !player.getMainHandItem().isEmpty())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderTarget.get()) return;
        if (ctx.renderBlockTarget != null) {
            event.renderer.box(ctx.renderBlockTarget, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
        if (ctx.renderEntityTarget != null && ctx.renderEntityTarget.isAlive()) {
            event.renderer.box(ctx.renderEntityTarget.getBoundingBox(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        if (pausedByCombat) return "Combat Pause";
        String task = runner.getActiveTaskName();
        if ("Idle".equals(task)) return "Idle (" + ctx.currentNutrition + "fp)";
        if (!ctx.statusDetail.isEmpty()) return task + " (" + ctx.statusDetail + ")";
        return task;
    }
}
