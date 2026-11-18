# AutoFood Enhancement & Utility-AI Bot Framework Implementation Plan

**Design Spec:** [2026-11-17-autofood-enhancement-design.md](file:///home/cachy/Projects/Baritone-Meteor-Dev/meteor-client-v2/docs/superpowers/specs/2026-11-17-autofood-enhancement-design.md)
**Target Subproject:** `meteor-client-v2`
**Build & Verification Gate:**
```bash
JAVA_HOME=/home/cachy/.jdks/zulu-25 ./gradlew build -Perrorprone -Pbuild_number=dev -Pcommit=local --no-daemon --stacktrace
```

---

## Phase 1: Utility-AI Core, Modular Refactor & Reliability Hardening

### Step 1.1: Reusable Bot Framework (`src/main/java/meteordevelopment/meteorclient/utils/bot/`)
Create 4 focused classes with JSpecify nullability annotations (`@NullMarked` / `@Nullable`) for NullAway compliance:
1. `TaskStatus.java` — `enum TaskStatus { RUNNING, SUCCEEDED, FAILED }`
2. `BotTask.java` — `interface BotTask<C>`:
   - `String name()`
   - `double score(C ctx)`
   - `void onStart(C ctx)`
   - `TaskStatus tick(C ctx)`
   - `void onStop(C ctx)`
3. `UtilityTaskRunner.java` — generic scheduler for `List<BotTask<C>>`:
   - Hysteresis multiplier (`1.15` default) and `minCommitTicks` (`15` default) to prevent task ping-ponging.
   - Per-task failure cooldown map (`failureCooldownTicks = 40`).
   - `tick(C ctx)` and `stopAll(C ctx)` (guaranteed `onStop` cleanup on deactivate/pause).
4. `BotNavigator.java` — Baritone wrapper:
   - Deduplicated `pathToBlock(BlockPos key, Goal goal)` and `followEntity(Entity target)`.
   - Rolling displacement watchdog (`stuckWindowTicks = 40`, `minDisplacementSq = 0.25`) returning `isStuck()`.
   - TTL blacklist (`blacklistBlock(BlockPos, long ttlTicks)`, `blacklistEntity(int id, long ttlTicks)`, `isBlacklisted(...)`).
   - `stop()` cancelling `CustomGoalProcess` and `FollowProcess`.

### Step 1.2: `AutoFood` Domain Context & Evaluator (`src/main/java/meteordevelopment/meteorclient/systems/modules/player/autofood/`)
1. `ServerProfile.java` — `enum ServerProfile { ANARCHY, SMP_SAFE, SINGLEPLAYER, CUSTOM }` with helper to apply profile defaults.
2. `FoodEvaluator.java` — pure inventory & food-scoring utilities:
   - `scoreFood(ItemStack)`, `isHazardous(Item)`, `isRawCookable(Item)`, `isFuel(Item)`.
   - `getGoodFoodNutritionTotal()`, `getBestFoodScoreInInventory()`, `countRawCookables()`, `hasFuelForCooking()`.
   - `maintainBestHotbarFood(int slot)`, `pruneJunkFoodIfFull()`.
3. `FoodContext.java` — per-tick context passed to tasks:
   - Holds `Minecraft`, `BotNavigator`, `FoodEvaluator`, profile/setting values, `BlockPos renderBlockTarget`, `Entity renderEntityTarget`, `String statusDetail`, and container-open state.
   - Claim-safety helpers: `isLikelyClaimedContainer(BlockPos)`, `isSafeToHarvestCrop(BlockPos)`.

### Step 1.3: Phase 1 `BotTask<FoodContext>` Implementations (`systems/modules/player/autofood/tasks/`)
Extract and harden each existing state from `AutoFood.java`:
1. `CollectDropsTask.java` — paths to nearby `ItemEntity` food/ingredient drops; respects blacklist & stuck recovery.
2. `CookAtFurnaceTask.java` — finds/places furnace or smoker, paces slot transfers (`maxClicksPerTick`, `actionDelayTicks`), retrieves cooked food and placed furnace.
3. `CraftFoodTask.java` — crafts bread, golden carrots, mushroom stew (and prepares for haybales in Phase 2) with paced grid clicks.
4. `LootChestTask.java` — loots chests/barrels/shulkers with `claim-safe` check, `maxClicksPerTick`, and `Rotations.rotate(...)`.
5. `HuntAnimalsTask.java` — hunts land livestock using `BotNavigator.followEntity`, equips best weapon, blacklists fenced/unreachable targets on stall.
6. `ForageCropsTask.java` — harvests mature crops & sweet berries with `claim-safe` and auto-replant.
7. `RoamTask.java` — biome/terrain-aware directional sampling (avoids deep water/lava/void edges).

### Step 1.4: Refactor `AutoFood.java` Coordinator
- Replace monolithic logic in `AutoFood.java` with `UtilityTaskRunner<FoodContext>` + profile settings (`profile`, `claim-safe`, `action-delay-ticks`, `max-clicks-per-tick`, `evade-threats`, `stuck-timeout-ticks`).
- Ensure `onDeactivate()` and combat-pause call `runner.stopAll(ctx)` and release `attackKey`/`useKey`.
- **Verify Phase 1:** Run `./gradlew build -Perrorprone -Pbuild_number=dev -Pcommit=local --no-daemon --stacktrace` and deploy to FreesmLauncher.

---

## Phase 2: Auto-Eat, Campfires, Fishing, Haybales & Underwater Fish Hunting

### Step 2.1: `EatFoodTask.java`
- Triggers when `mc.player.getFoodData().getFoodLevel() <= eatThreshold` (or `health <= lowHealthEatThreshold` and `saturation == 0`).
- Selects best non-hazardous food, holds `useKey` until eaten, and restores previous hotbar slot in `onStop()`.

### Step 2.2: `CampfireCookTask.java` & Expanded `ForageCropsTask` / `CraftFoodTask`
- `CampfireCookTask`: detects `Blocks.CAMPFIRE` / `Blocks.SOUL_CAMPFIRE`, places up to 4 raw items via paced `Rotations.rotate` + `interactBlock`, waits for popped cooked drops.
- `ForageCropsTask` + `CraftFoodTask`: add `Blocks.HAY_BLOCK`, `Blocks.MELON`, `Blocks.PUMPKIN`, `Blocks.GLOW_BERRIES`, and haybale → 9 wheat → 3 bread crafting.

### Step 2.3: `FishTask.java`
- Eligible when carrying a `FishingRodItem` and open water (`>= 3x3`) is nearby.
- Paths to shoreline, faces water via `Rotations.rotate`, casts, watches `FishingHook` bobber splash/vertical velocity drop, reels in, and collects drops.

### Step 2.4: Underwater Fish Hunting + Oxygen Guard in `HuntAnimalsTask.java`
- Allow `HuntAnimalsTask` to target `Cod`, `Salmon`, and `TropicalFish` (skip `Pufferfish` as hazardous) and `Hoglin` (with extra spacing).
- **Oxygen Monitor:** When `mc.player.isUnderWater()` or `mc.player.getAirSupply() < mc.player.getMaxAirSupply()`:
  - If `airSupply <= minAirTicks` (default `90` ticks / ~30%), enter surfacing sub-state: stop diving after the fish, hold `jumpKey` / path upward to surface air until `airSupply >= resumeAirTicks` (default `240` ticks), then resume hunting.
  - Always release `jumpKey` in `onStop()`.

- **Verify Phase 2:** Run full Error Prone + NullAway + MixinVerifier build and deploy.

---

## Phase 3: Self-Bootstrap & Villager Trading

### Step 3.1: `BootstrapTask.java` (`self-bootstrap`, default `false`)
- Eligible only when reserve is unmet, no furnace/campfire or fuel is available, and direct food tasks score `<= 0`.
- Gathers 1–2 nearby logs → crafts planks/sticks/crafting table/campfire, or mines 8 cobblestone → crafts furnace.

### Step 3.2: `VillagerTradeTask.java` (`trade-villagers`, default `true` in `SMP Safe`)
- Paths to nearby `Villager` (Farmer / Butcher profession) when carrying emeralds or tradeable crops, opens `MerchantMenu`, and executes bread/golden-carrot/cooked-meat trades with paced clicks.

- **Verify Phase 3:** Run full Error Prone + NullAway + MixinVerifier build and deploy.
