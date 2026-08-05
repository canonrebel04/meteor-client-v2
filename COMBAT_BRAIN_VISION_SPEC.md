# CombatBrain v2 — Vision Specification & Architecture

**Author:** canonrebel04 (vision brain-dump, 2026-08-05)
**Consolidated + grounded by:** Hermes Agent (against meteor-client-v2 fork source, HEAD 2b5dc4ad8 + CombatBrain fixes)
**Repo:** /home/cachy/Projects/baritone-v2-dev/meteor-client-v2 · modules/combat/CombatBrainModule.java (615 ln)
**Status:** DESIGN SPEC — not yet implemented. Existing code covers ~80% of the scaffolding; gaps marked **[GAP]**.

---

## 0. Vision (verbatim intent)

> "Intelligent combat tactics. An intelligent switching loop of combat modes based on factors happening to the player or environment. A math coordinate graph using 3D coordinates — a cube as the map, player in the center, tracking players around the main player; keeps x,y,z of main player and others; tracks walls/blocks roughly via ray tracing hitting walls to keep track of player locations behind structure/ground. Dynamically changes combat mode based on factors to keep the main player safe and healed up while having an advantage. Combat advancements: utilize meteor-client modules dynamically — detect crystal placed → enable CrystalAura; detect target using bow → enable anti-arrow (ArrowDodge). During regular combat, attack the target using baritone's built-in pathfinding + follow entity/player with dynamic follow distance — outside the hit range dynamically based on target reach range, damage of selected weapon incl. potion effects, enchantments. Track armor and enchants to see if the main player is too undergeared to win. On low health: retreat and heal via auto-heal meteor module, staying away from the target."

---

## 1. System Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │          CombatBrainModule (FSM)            │
                    │  IDLE→SCANNING→ANALYZING→ENGAGING→          │
                    │  RETREATING/HEALING/FLEEING/EMERGENCY_LOG   │
                    └──────┬──────────┬──────────┬───────────────┘
                           │          │          │
        ┌──────────────────┼──────────┼──────────┼──────────────────┐
        ▼                  ▼          ▼          ▼                  ▼
┌───────────────┐  ┌──────────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────────┐
│ AwarenessCube │  │ CombatMode   │ │ Gear/     │ │ ModuleAutom- │ │ FollowEngine │
│ (3D voxel +   │  │ SwitchLoop   │ │ Viability │ │ ator        │ │ (Baritone    │
│ raycast LOS)  │  │ (environment │ │ Analyzer  │ │ (dynamic    │ │ follow +     │
│  [GAP: 3D]    │  │  triggers)   │ │           │ │ module      │ │ dynamic      │
│               │  │  [GAP: part] │ │ [EXISTS]  │ │ toggling)   │ │ distance)    │
│               │  │              │ │           │ │ [GAP: part] │ │ [EXISTS +    │
└───────────────┘  └──────────────┘ └───────────┘ └─────────────┘ │  GAP: dynamic]│
                                                                  └──────────────┘
```

Existing fork code: `CombatBrainModule` (FSM), `CombatTargetAnalyzer` (viability), `CombatTerrainGrid` (2D grid + LOS raycast), `CombatFollowController` (baritone follow/flee), `CombatPathManager` (priority-100 process), `CombatIntegrationBridge`, `TacticalBrain`, `AntiDetectionModule`, `ShieldAutoSwapModule`, overlays, `CombatNotificationManager`.

---

## 2. AwarenessCube — the 3D map [GAP: current grid is 2D XZ]

Current: `CombatTerrainGrid` — 33×33 (radius 16) XZ grid at player Y, chars `'#'` solid / `'.'` air / `'W'` water / `'L'` lava / `'T'` target / `'P'` player / `'?'` unknown. `getPathBlocks()` raycasts at eye-level ±1 Y only.

**Spec — full 3D cube, player-centered:**
- **Volume:** cube radius R (default 24, configurable) → (2R+1)³ cells. Player at center cell. **Y now included** — tracks x,y,z of the main player and every tracked entity.
- **Cell states (per-block):** `AIR`, `SOLID`, `WATER`, `LAVA`, `LIQUID` (lava/water union), `UNKNOWN` (unloaded). Pack into a byte/bit grid — 49³ ≈ 117 KB at 1 byte/cell; fine.
- **Entity registry:** every nearby player/entity with `(x,y,z)`, velocity, UUID, snapshot timestamp. Re-scan entities every N ticks (e.g. 10) via `mc.level.entitiesForRendering()` or the meteor entity lookup (`meteor$getEntityLookup()`).
- **Occupancy:** mark cells occupied by entities (player = center, others as `E` with their Y).
- **Ray tracing:** DDA (Amanatides & Woo) or stepped raycast from player eye position to each tracked entity's position — **3D**, not just XZ. First non-air cell hit = occluder → entity is **behind structure/ground**. Store per-entity `LineOfSight: CLEAR / BLOCKED / WATER` + `occluderPos`.
- **Derived products (consumed by the mode loop):**
  - `isTargetVisible(target)` — LOS clear (walls/ground between us).
  - `getCoverCount()` — nearby solid cells within 2-block shell around player (how much cover we have).
  - `isPlayerInOpen()` — no cover within radius 6 in any direction.
  - `targetBehindCover(target)` — occluder exists → candidate for burst-approach or crystal play.
  - `getThreatMap()` — distance×LOS-weighted threat per tracked entity (feeds `computeThreatLevel`).
- **Implementation notes:** reuse the existing grid char codes; add `E`/`Y` handling; upgrade `getPathBlocks()` to 3D DDA; cap update frequency (every 2 ticks = matches brain cadence); `UNKNOWN` for unloaded chunks so the brain knows data is stale.

---

## 3. CombatMode — the intelligent switching loop [GAP: FSM exists, mode triggers don't]

Current FSM states: `IDLE/SCANNING/ANALYZING/ENGAGING/RETREATING/HEALING/FLEEING/EMERGENCY_LOG` — good skeleton. Missing: a **mode** layer orthogonal to the state machine, switched by environment/player factors.

**CombatMode enum (new):**
```
AGGRESSIVE   — push, chase via baritone follow, close distance
DEFENSIVE    — hold range, use cover, prefer block/hit-trade
KITE        — keep distance, ranged/hit-and-run (target has melee advantage)
RUSH        — all-in burst (target low HP / we have advantage)
BURROW      — hole up / surround / crystal play (multi-target threat)
RETREAT_HEAL — flee + heal (low HP)
SNIPE       — bow/range mode (target can't reach)
STEALTH     — hide behind cover, wait for advantage (AntiDetection synergies)
```

**Switch triggers (evaluated every tick in the mode loop, priority order):**
| # | Condition (from AwarenessCube + analyzer + player state) | Mode |
|---|---|---|
| 1 | `health <= 4 && totems == 0 && threat > 0.8` (existing) | EMERGENCY_LOG |
| 2 | `health < 10` or `threat >= fleeThreshold` | RETREAT_HEAL |
| 3 | 3+ enemies within 8 blocks, no cover | BURROW |
| 4 | target behind cover AND we have crystal/anchors + `crystal` setting | BURROW (crystal breach) |
| 5 | target uses bow / crossbow / trident (held item) | SNIPE or KITE (stay out of their arc, enable ArrowDodge) |
| 6 | target reach > our reach AND our weapon is melee | KITE (keep beyond their reach) |
| 7 | target HP < 30% AND viability >= 0.6 | RUSH |
| 8 | viability < 0.3 (undergeared) | DEFENSIVE or RETREAT_HEAL |
| 9 | default | AGGRESSIVE |

**Mode → module/follow mapping:**
| Mode | Modules | Baritone follow distance |
|---|---|---|
| AGGRESSIVE | KillAura, AutoWeapon, Criticals | `followOffsetDistance = 1.0` (press into range) |
| DEFENSIVE | KillAura, AutoArmor, ShieldAutoSwap, AutoTotem | `targetReach + 0.5` |
| KITE | ArrowDodge, BowAimbot, Velocity | `targetReach + 2.0` |
| RUSH | KillAura, Criticals, CrystalAura (if crystal setting) | `0.5` |
| BURROW | Surround, HoleFiller, CrystalAura, AutoTotem | `1.5` (stay in hole) |
| RETREAT_HEAL | AutoEat, AutoGap, AutoTotem, (AutoArmor) | `flee` via GoalRunAway |
| SNIPE | BowAimbot, ArrowDodge, Velocity | `max(targetReach, 6.0) + 1.0` |
| STEALTH | AntiDetection, NoFall, Velocity | hold position / sneak cycle |

**Mode coherence rule:** mode changes are evaluated 1×/tick but applied with hysteresis (e.g. stay in a mode ≥ 20 ticks unless emergency) to avoid flapping.

---

## 4. ModuleAutomator — dynamic module utilization [GAP: current = static enable list]

Current: `enableCombatModules()` toggles a fixed list (KillAura/ArrowDodge/AutoArmor/AutoWeapon/AutoTotem + Criticals/CrystalAura per settings). **Vision: condition-driven.**

**Event → module rule table:**
| Detect | Condition source | Module to enable |
|---|---|---|
| Crystal placed nearby (end crystal entity within 6 blocks) | entity scan: `EntityType.END_CRYSTAL` | CrystalAura |
| Anchor placed / respawn anchor nearby | block scan via AwarenessCube | AnchorAura |
| Target holding bow/crossbow | `TargetAnalysis.weaponName` / held item check | ArrowDodge (+ BowAimbot if we have a bow) |
| Target attacking (hurtTime/anim) | player hurt event | ShieldAutoSwap (switch shield) |
| Low health (< 14) | health check | AutoEat / AutoGap (auto-heal) |
| In water / target in water | AwarenessCube cell state | Jesus |
| No totems + health < 8 | inventory scan | AutoTotem (offhand swap) |
| Target in bed / near bed in nether | block scan | BedAura |
| Target in hole / 1x1 | AwarenessCube occupancy | HoleFiller / Surround |
| We have crystal + target behind cover | crystal setting + LOS BLOCKED | CrystalAura (breach) |
| Target sprinting at us | velocity vector | Velocity / AntiKnockback (if exists) |

**Rules:**
- Each rule = `(condition predicate, module, enable/disable, cooldown)`. Evaluated in the tick loop; module toggled only on **state change** (avoid repeated `enable()` calls — meteor modules are idempotent but noisy).
- Rule engine order: threats first (crystal/anchor/bow), then advantage (crystal breach), then sustain (heal/totem).
- All auto-toggled modules must be tracked so `disableAllManagedModules()` on deactivate restores state.

---

## 5. FollowEngine — dynamic follow distance [EXISTS: follow; GAP: dynamic distance]

Current: `CombatFollowController.follow(target, distance)` sets `followRadius=0` + `followOffsetDistance=distance` + `followProcess.follow(uuidPredicate)` (fixed distance from `followDistance` setting, default 3.5). Flee via `GoalRunAway`.

**Spec — compute follow distance per target each engagement:**
```
followDistance = f(targetReach, weaponReach, myWeapon, effects, enchants, mode)

base = max(targetReach, myReach)        // never inside their hitbox range
melee:
  if myWeapon is melee (sword/axe):    base + 0.5   // trade range
  if target has reach enchant (e.g. reach-ish, sweep): base + 1.0
  if target has Strength II (effect):  base + 2.0   // stay out of burst
  if we have Weakness:                 base + 3.0   // we hit soft, kite
ranged:
  if myWeapon is bow/crossbow:         max(base, 6.0) + 1.0
defensive (low HP / mode DEFENSIVE):  base + 1.5
```
- Feed from `CombatTargetAnalyzer.TargetAnalysis` (has `targetReach`, `weaponDamage`; **GAP: add potion-effect + enchantment extraction** — `LivingEntity.getActiveEffects()`, `Utils.getEnchantmentLevel(weapon, SHARPNESS/SWEEPING/FIRE_ASPECT)`, plus our own effects).
- Recompute every 20 ticks or on mode change (not every tick — baritone follow restart churn).
- `flee()` already exists (GoalRunAway) — RETREAT_HEAL uses `flee(target, fleeDistance)` with `fleeDistance*1.5` in HEALING (existing behavior).

---

## 6. Gear & Viability Engine [EXISTS: core; GAP: self-gear + enchants/effects]

Current `CombatTargetAnalyzer`:
- `analyze()` → `TargetAnalysis(entity, distance, weaponName, weaponDamage, armorSummary, totalArmor, targetReach, targetHealth, viabilityScore)` — uses `DamageUtils.getAttackDamage`, armor material detection, protection level sum.
- `calculateViability()` — kill-time ratio (myKillTime vs theirKillTime), reach bonus, "diamond/netherite + sharpness = 0.2" hard penalty, "no armor no weapon = 0.9".

**Spec additions:**
- **[GAP] Self-side gear score:** same analysis on `mc.player` — own armor material+prot sum, own weapon damage incl. sharpness/fire aspect/sweeping, own effects (Strength/Weakness/Speed). Produce `myCombatScore` symmetric to `TargetAnalysis`.
- **[GAP] Effect-aware damage:** fold active potion effects into `DamageUtils.getAttackDamage` results (Strength +3 dmg per level, Weakness −4, etc. — vanilla constants) for both sides.
- **[GAP] Enchantment-aware reach:** sweep edge? Not needed; keep reach from entity type + `entityInteractionRange` (already used) + optionally `Attributes.ENTITY_INTERACTION_RANGE` (vanilla 1.21+ attribute).
- **Undergear decision:** `if myCombatScore < 0.6 * theirCombatScore → viability low → mode DEFENSIVE or RETREAT_HEAL` (replaces the hardcoded 0.2/0.9 shortcuts with a continuous score).
- Keep existing heuristics as fallback (they work).

---

## 7. Threat Model (existing, refined)

Existing `computeThreatLevel()`:
```
threat = (1.0 - health/20.0) * 0.4        // own health pressure
       + (totems > 0 ? 0 : 0.3) * 0.2     // no totem pressure
       + min(sum of nearby threats, 0.4)   // entities within 6 blocks, weighted (1-d/6)*0.3
clamped [0,1]
```
**Refinement (vision "keep player safe"):**
- Weight nearby threats by **LOS** (visible threats count double — they can hit us) and by **their damage** (from analyzer) instead of distance only.
- Add cover modifier: `threat *= (inOpen ? 1.2 : 0.8)`.
- Add crystal/anchor proximity term (end crystal within 6 blocks → +0.15).

---

## 8. Healing & Retreat (auto-heal integration) [EXISTS: states; GAP: heal module wiring]

Current: `HEALING` state (health<10 → heal; health<8 for >100 ticks → FLEEING; heal until ≥14 or low threat), `doHealTick()` = flee + sprint, `doFleeTick()` = sprint + flee. `AutoEat`/`AutoGap` modules exist with `pause-baritone` integration.

**Spec:**
- **[GAP] HEALING should enable AutoEat/AutoGap explicitly** (via ModuleAutomator rule: `health < 14 → enable AutoEat (+ AutoGap if golden apples in inv)`) and **disable them** on leaving HEALING.
- Keep `followController.flee(target, fleeDistance*1.5)` in HEALING (already there) — stays away from target while eating (AutoEat pauses baritone natively via pause-baritone, so the two won't fight — verify ordering: brain flee issues GoalRunAway, AutoEat pauses — final state = paused; acceptable, resume on heal end).
- **[GAP] Emergency protocol:** existing EMERGENCY_LOG = disconnect. Consider configurable `emergency-mode: DISCONNECT | BURROW | FLEE` instead of hard disconnect.

---

## 9. Tick Loop & Data Flow (consolidated)

Every 2 ticks (`tickCounter % 2 == 0`):
1. Snapshot: health, totems, position, held item; entity scan.
2. `AwarenessCube.update(targets)` — grid + entity registry + 3D LOS raycasts. (replaces CombatTerrainGrid.update)
3. `computeThreatLevel()` (refined).
4. Emergency check → EMERGENCY_LOG.
5. `CombatMode` switch loop (section 3 table, hysteresis).
6. FSM state transitions (existing switch).
7. Per-state action: `doEngageTick/doRetreatTick/doHealTick/doFleeTick`.
8. ModuleAutomator rule pass (enable/disable only on change).
9. FollowEngine: recompute follow distance if mode changed or 20-tick timer.
10. Overlays update (TargetESP, CombatDecisionOverlay, PathVisualizationOverlay, ThreatIndicatorModule, SmartCombatHud).

---

## 10. Mapping: existing vs new

| Component | Existing (file) | New work |
|---|---|---|
| FSM shell | CombatBrainModule.java:230-337 | keep; add CombatMode layer |
| Target selection | findBestTarget() :385-413 | keep (UUID-friendly after H2 fix) |
| Threat | computeThreatLevel() :454-478 | refine: LOS/damage/cover/crystal terms |
| Terrain map | CombatTerrainGrid.java (2D XZ, 33×33) | **3D cube + DDA raycast + entity registry** |
| Viability | CombatTargetAnalyzer.java | add self-gear score, effects, enchants |
| Follow/flee | CombatFollowController.java (fixed dist) | dynamic distance engine |
| Module toggling | enableCombatModules() :482-499 (static) | rule-based ModuleAutomator |
| Retreat/heal | HEALING/FLEEING states + doHealTick/doFleeTick | wire AutoEat/AutoGap explicitly; emergency modes |
| Path process | CombatPathManager (priority 100, stubs) | fill enableCombatMode/enableFavoring or remove stubs |
| Detection counter | AntiDetectionModule.java | fold into STEALTH mode |
| Commands | CombatBrainCommand (.combat-brain) | add `.combat-brain mode <mode>` + `.combat-brain grid` debug |
| HUD/overlays | SmartCombatHud, CombatDecisionOverlay, PathVisualizationOverlay | add mode + follow-distance readouts |

---

## 11. Implementation roadmap (suggested order)

1. **P0 — FollowEngine dynamic distance** (smallest, highest impact): extend CombatFollowController with distance computation from TargetAnalysis + effects; wire into doEngageTick. (~1 session)
2. **P0 — AwarenessCube 3D + DDA raycast** (biggest structural piece): evolve CombatTerrainGrid into 3D cube, entity registry, LOS per target; keep old API (`getPathBlocks`) working. (~2 sessions)
3. **P1 — ModuleAutomator rules** (crystal→CrystalAura, bow→ArrowDodge, low HP→AutoEat/AutoGap, no totem→AutoTotem): table-driven, change-only toggling. (~1 session)
4. **P1 — CombatMode loop** (mode enum + trigger table + hysteresis + mode→follow/module mapping). (~1-2 sessions)
5. **P2 — Gear engine upgrade** (self-gear score, effect-aware damage, undergear threshold). (~1 session)
6. **P2 — Threat refinement + healing wiring** (LOS weighting, cover modifier, explicit AutoEat in HEALING, emergency modes). (~1 session)
7. **P3 — polish**: mode/follow HUD readouts, `.combat-brain mode` command, STEALTH mode with AntiDetection, config presets.

Each step is independently testable in-game via `.combat-brain` state HUD + existing overlays.

---

## 12. Settings surface (new, consolidated)

```
[CombatMode] mode-switch (bool, true) · mode-hysteresis-ticks (int, 20) · default-mode (enum, AGGRESSIVE)
[Awareness] cube-radius (int, 24) · los-update-ticks (int, 10) · entity-scan-ticks (int, 10)
[ModuleAuto] auto-modules (bool, true — exists) · crystal-detect-range (double, 6.0) · bow-detect (bool, true) · anchor-detect (bool, true) · bed-detect (bool, false) · auto-heal (bool, true) · auto-totem (bool, true — exists)
[Follow] follow-distance (double, 3.5 — exists, becomes base) · dynamic-follow (bool, true) · follow-recalc-ticks (int, 20) · kite-multiplier (double, 1.5) · defensive-padding (double, 1.5)
[Gear] gear-check (bool, true — exists as analyze-gear) · undergear-ratio (double, 0.6) · effect-aware-damage (bool, true)
[Threat] los-weight (double, 2.0) · cover-modifier (bool, true) · crystal-threat (double, 0.15)
[Emergency] emergency-mode (enum, DISCONNECT | BURROW | FLEE) — replaces hard disconnect
```

---

## 13. Key principles (from the vision)

1. **Safety first** — mode loop's primary objective: keep main player safe + healed; combat advantage is secondary. Threat model is the gatekeeper.
2. **Environment-driven, not scripted** — every mode switch must trace to a measurable factor (LOS, cover, held item, crystal entity, health, gear ratio).
3. **Baritone is the legs** — the brain never invents movement; it always delegates to baritone follow/flee with a computed distance. Follow distance is the #1 tactical dial.
4. **Modules are tools, toggled by state change only** — never fight meteor's own module state; track and restore.
5. **Hysteresis everywhere** — no flapping between modes; emergency paths bypass hysteresis.
6. **Debug visibility** — every decision visible in HUD/overlay (state, mode, follow distance, LOS, threat breakdown).
