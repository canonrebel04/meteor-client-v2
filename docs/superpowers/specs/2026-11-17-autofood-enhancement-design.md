# AutoFood Enhancement & Reusable Utility-AI Bot Framework Design

**Date:** 2026-11-17
**Target Subproject:** `meteor-client-v2`
**Primary Module:** `meteordevelopment.meteorclient.systems.modules.player.AutoFood`

---

## 1. Goals & Scope

Enhance `AutoFood` from a monolithic 1,385-line state machine into a reliable, profile-aware autonomous food system backed by a reusable Utility-AI bot framework (`utils.bot`).

### Key Objectives
1. **Reusable Utility-AI Core:** Extract generic task scoring, hysteresis/commit scheduling, and Baritone navigation/stuck-recovery into `meteordevelopment.meteorclient.utils.bot` so future autonomous modules can build on the same primitives.
2. **Modular `AutoFood` Controllers:** Decompose `AutoFood` into focused, single-purpose tasks and evaluators under `systems/modules/player/autofood/`.
3. **Configurable Server Profiles:** Support `Anarchy` (6b6t-style), `SMP Safe` (claim-aware, strict pacing), `Singleplayer`, and `Custom` presets.
4. **Phased Delivery:**
   - **Phase 1:** Utility-AI framework + modular refactor + stuck/blacklist recovery + anticheat pacing + claim-safety + biome-aware roaming.
   - **Phase 2:** Integrated auto-eat, haybale/melon/pumpkin/berry foraging, campfire cooking, auto-fishing, and expanded hunting (hoglins, water mobs).
   - **Phase 3:** Optional `self-bootstrap` (wood/cobble/furnace/campfire crafting when stranded) and villager food trading.

---

## 2. Architecture & Package Boundaries

### 2.1 Reusable Bot Framework (`meteordevelopment.meteorclient.utils.bot`)

| Component | Responsibility |
|---|---|
| `TaskStatus` | Enum: `RUNNING`, `SUCCEEDED`, `FAILED`. |
| `BotTask<C>` | Interface: `name()`, `double score(C ctx)`, `TaskStatus tick(C ctx)`, `onStart(C ctx)`, `onStop(C ctx)`. Returns `score <= 0` when ineligible. |
| `UtilityTaskRunner<C>` | Evaluates registered tasks each tick, applies active-task hysteresis bonus (`+15%` or configurable) and minimum commit ticks (`minCommitTicks`) to prevent thrashing, enforces per-task failure cooldowns, and guarantees `onStop()` before switching. |
| `BotNavigator` | Wraps Baritone `CustomGoalProcess`, `FollowProcess`, and `PathingBehavior`. Deduplicates `Goal` / `Entity` dispatches, tracks rolling player displacement (`stuckWindowTicks`, `minDisplacement`), detects stalls, and maintains TTL-based blacklists (`Map<BlockPos, Long>`, `Map<Integer, Long>`). |

### 2.2 `AutoFood` Package (`systems/modules/player/autofood/`)

- **`AutoFood` (`Module`):** Owns Meteor `Setting<T>` definitions, profile presets, `UtilityTaskRunner<FoodContext>`, `TickEvent.Pre`, `Render3DEvent`, and `getInfoString()`.
- **`FoodContext`:** Per-tick shared snapshot passed to all `BotTask<FoodContext>` instances (`mc`, `BotNavigator`, `FoodEvaluator`, active profile/settings view, inventory nutrition summary, active target highlights for rendering).
- **`FoodEvaluator`:** Pure food scoring (`nutrition + saturation * 1.5 - hazardPenalty`), reserve calculation, hotbar slot maintenance, junk pruning, and eat-trigger evaluation.
- **Task Implementations (`BotTask<FoodContext>`):**
  - Phase 1: `CollectDropsTask`, `CookAtFurnaceTask`, `CraftFoodTask`, `LootChestTask`, `HuntAnimalsTask`, `ForageCropsTask`, `RoamTask`.
  - Phase 2: `EatFoodTask`, `CampfireCookTask`, `FishTask`.
  - Phase 3: `BootstrapTask`, `VillagerTradeTask`.

---

## 3. Server Profiles & Safety Controls

Selecting a profile updates default behavior without locking individual toggles:

| Setting / Behavior | `Anarchy` | `SMP Safe` | `Singleplayer` |
|---|---|---|---|
| `claim-safe` (skip player-base chests/crops) | `false` | `true` | `false` |
| `action-delay-ticks` (interaction/click pacing) | `3` | `5` | `1` |
| `max-clicks-per-tick` (container transfers) | `4` | `2` | `8` |
| `pause-in-combat` | `true` | `true` | `false` |
| `evade-threats` (retreat when low HP & threatened) | `true` | `true` | `false` |
| `loot-chests` | `true` | `false` (unless unclaimed) | `true` |

### Claim-Safe Heuristic (`SMP Safe`)
- **Containers:** Skip chests/barrels/shulkers if beds, signs, banners, or item frames exist within 6 blocks (base/claim indicator) unless the container is marked player-placed by `AutoFood` itself.
- **Crops:** Only harvest mature crops when replantable with matching seeds in inventory (and replant immediately), or prefer wild berries, haybales, hunting, and fishing.

### Combat & Threat Handling
- Pause immediately when `CombatBrainModule` has an active target, `mc.player.hurtTime > 0`, or a non-friend player within `combatRange` (`<= 8.0` blocks for passive check, full `combatRange` when swinging or holding a weapon/tool).
- On pause, `UtilityTaskRunner` invokes `onStop()` on the active task, closing any open screen, releasing attack/use keys, and cancelling Baritone pathing.

---

## 4. Phased Feature Specifications

### Phase 1: Utility-AI Core & Reliability Hardening
1. **Stuck Recovery & Blacklisting:**
   - `BotNavigator` samples player position every tick over a 40-tick window. If displacement `< 0.5` blocks while a task is actively navigating, or a task exceeds its timeout without progress, the target `BlockPos` or `Entity` ID is blacklisted for `30s` (`600` ticks) and the task returns `FAILED`.
2. **Paced Interactions & Rotations:**
   - All block interactions, container slot clicks, and melee swings route aim through `Rotations.rotate(...)` and obey `actionDelayTicks` + `maxClicksPerTick`.
3. **Biome-Aware Roaming (`RoamTask`):**
   - Scores candidate roam waypoints (48–80 blocks out) by avoiding deep water/lava/void edges and preferring flat, passable land/plains/forest/village terrain.

### Phase 2: New Food Sources & Auto-Eat
1. **`EatFoodTask`:**
   - Scores highest when `hunger <= eatThreshold` (default `14`) or `health <= lowHealthEatThreshold` and `saturation == 0`. Selects best non-hazardous food, holds `useKey` until consumed, then restores previous slot.
2. **Expanded Foraging (`ForageCropsTask`):**
   - Adds village haybales (`Blocks.HAY_BLOCK`), pumpkins, melons, and sweet/glow berries. `CraftFoodTask` unpacks haybales into wheat → bread.
3. **Campfire Cooking (`CampfireCookTask`):**
   - Uses nearby or placed campfires (`Blocks.CAMPFIRE` / `SOUL_CAMPFIRE`) to cook up to 4 raw meats/fish/potatoes simultaneously without fuel or GUI interaction.
4. **Auto-Fishing (`FishTask`):**
   - Eligible when holding/carrying a fishing rod and water (`>= 3x3`) is within `searchRadius`. Paths to shoreline, casts rod, detects bobber splash/bite velocity, reels in, and collects drops.
5. **Expanded Hunting (`HuntAnimalsTask`):**
   - Adds Hoglins (high porkchop yield, respects extra spacing) and passive fish mobs (cod, salmon, tropical fish, pufferfish skipped as hazardous) in water.
   - **Underwater Oxygen Guard:** Allows diving/swimming underwater to chase detected fish, while continuously monitoring `mc.player.getAirSupply()` against `maxAirSupply`. When air drops below a safe threshold (e.g., `<= 30%` / `<= 90` air ticks), `HuntAnimalsTask` pauses pursuit, surfaces to breathe (pathing upward / holding jump until air recovers), and then resumes or yields.

### Phase 3: Self-Bootstrap & Villager Trading
1. **`BootstrapTask` (`self-bootstrap`, default `false`):**
   - Eligible only when reserve is unmet, no furnace/campfire or fuel is available, and higher-scoring direct food tasks are unavailable.
   - Gathers 1–2 logs → crafts planks, sticks, crafting table, or campfire; mines 8 cobblestone → crafts furnace.
2. **`VillagerTradeTask` (`trade-villagers`, default `true` in `SMP Safe`):**
   - Paths to nearby Farmer or Butcher villagers when emeralds or tradeable crops are held, opens trade GUI, and executes food trades (bread, golden carrots, cooked porkchop/chicken).

---

## 5. Error Handling & Verification

### Error Handling
- Every `BotTask.onStop(FoodContext)` must release `attackKey`/`useKey`, call `navigator.stop()`, and close any `AbstractContainerMenu` opened by `AutoFood`.
- `UtilityTaskRunner` catches and logs/throttles repeated failures (`failureCooldownTicks = 40`) so an unreachable resource never spins at 20 Hz.

### Verification Plan
1. **Static Analysis & Build Gate (after every phase):**
   ```bash
   JAVA_HOME=/home/cachy/.jdks/zulu-25 ./gradlew build -Perrorprone -Pbuild_number=dev -Pcommit=local --no-daemon --stacktrace
   ```
   Must pass with 0 Error Prone, NullAway (JSpecify), and MixinVerifier errors.
2. **Runtime Verification:**
   - Deploy `build/libs/meteor-client-26.3-dev.jar` to `/home/cachy/.local/share/FreesmLauncher/instances/26.3/minecraft/mods/`.
   - Verify in-game: profile switching, stuck-target blacklisting, auto-eat, campfire/furnace cooking, fishing, and clean toggle-off state cleanup.
