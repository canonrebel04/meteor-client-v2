# Meteor Client Internals — Source-Level Report

Fork: baritone-v2-dev/meteor-client-v2 (Mojang mappings, MC 26.1.x). All citations relative to `src/main/java/meteordevelopment/meteorclient/`. Orbit is vendored as a binary dependency (`meteordevelopment:orbit:0.2.4`, `build.gradle.kts:89`), decompiled for this report.

---

## 1. Startup pipeline — `MeteorClient.java`

`MeteorClient implements ClientModInitializer` (`MeteorClient.java:50`). Static state is built in the class initializer (`:65-79`): `MOD_META` from FabricLoader, `NAME`, SLF4J `LOG`, `VERSION` (strips `-SNAPSHOT`), `BUILD_NUMBER`. `EVENT_BUS = new EventBus()` (`:61`), `FOLDER = <gameDir>/meteor-client` (`:62`).

`onInitializeClient()` (`:81-150`) runs in this exact order:

1. **INSTANCE guard** (`:83-86`) — first invocation sets `INSTANCE` and returns; real init happens on the second call.
2. `mc = Minecraft.getInstance()` (`:89`).
3. Dev-env mixin audit (`:91-94`).
4. **Pre-load folder setup** (`:99-103`) — creates `FOLDER`; on a *fresh* install registers a `Systems.addPreLoadTask(...)` that enables `DiscordPresence` after config load (`Systems.java:32-34` runs preload tasks inside `Systems.load()`).
5. **`AddonManager.init()`** (`:106`) — builds `AddonManager.ADDONS` (`AddonManager.java:18-91`): first a Meteor "pseudo-addon" whose `getPackage()` is `"meteordevelopment.meteorclient"` (`:23-47`), then every Fabric `"meteor"` entrypoint (`:66-90`), throwing on init failure (`:72`) or missing authors (`:77`).
6. **Lambda factories** (`:109-115`) — for each addon: `EVENT_BUS.registerLambdaFactory(addon.getPackage(), (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()))`; i.e. Orbit gets a factory that reflectively calls `MethodHandles.privateLookupIn(klass, lookup())` so the event bus can build `LambdaMetafactory` handles into *addon* classes. `AbstractMethodError` ⇒ "addon too old" (`:112-114`).
7. **`ReflectInit.registerPackages()`** (`:118`) — creates one `Reflections(pkg, Scanners.MethodsAnnotated)` per addon package (`ReflectInit.java:25-39`).
8. **`ReflectInit.init(PreInit.class)`** (`:121`) — finds all `@PreInit` static methods, groups by declaring class, resolves `dependencies()` recursively, invokes each statically (`ReflectInit.java:41-73`). Used by `Rotations.init` (`Rotations.java:44-47`), `PostProcessShaders.init` (`PostProcessShaders.java:17-24`), `PathManagers.init` (`PathManagers.java:20-39`).
9. **`Categories.init()`** (`:124`) — sets `REGISTERING=true`, registers Combat/Player/Movement/Render/World/Misc (`Categories.java:14-32`), calls addon `onRegisterCategories` (`:35`), clears flag (`:37`). `Modules.registerCategory` throws outside this window (`Modules.java:105-110`).
10. **`Systems.init()`** (`:127`) — see below.
11. `EVENT_BUS.subscribe(this)` (`:130`) — enables MeteorClient's own keybind handlers (`:152-171`).
12. **`AddonManager.ADDONS.forEach(MeteorAddon::onInitialize)`** (`:133`).
13. **`Modules.get().sortModules()`** (`:136`) — sorts each category list by title (`Modules.java:99-103`).
14. **`Systems.load()`** (`:139`) — runs preload tasks then `system.load()` for every registered system (`Systems.java:87-95`).
15. **`ReflectInit.init(PostInit.class)`** (`:142`) — e.g. `ChamsShader.load` loads `textures/chams.png` (`ChamsShader.java:47-81`).
16. **Shutdown hook** (`:145-149`) — `OnlinePlayers.leave()`, `Systems.save()`, `GuiThemes.save()`.

**`Systems.init()`** (`Systems.java:36-57`): `Modules` is added **first** (`:38`) so the hidden-modules config can reference module instances; then `Config` is added, `init()`'d and `load()`'d immediately (`:40-43`) so `Config.get().prefix` etc. exist early; `config.settings.registerColorSettings(null)` (`:46`) so rainbow colors work for friends; then Macros, Friends, Accounts, Waypoints, Profiles, Proxies, Hud (`:48-54`); `EVENT_BUS.subscribe(Systems.class)` (`:56`). `Systems.add()` (`:59-65`) = put into a `Reference2ReferenceOpenHashMap<Class, System>` (`:29`) + subscribe + `init()`. Persistence: every `System` maps to `FOLDER/<name>.nbt` (`System.java:27-30`) saved via temp-file + atomic move (`System.java:38-60`).

---

## 2. Orbit event bus (orbit-0.2.4, decompiled)

Classes: `EventBus`, `EventHandler` (annotation, `int priority()` default 0), `EventPriority` (HIGHEST=200, HIGH=100, MEDIUM=0, LOW=-100, LOWEST=-200), `ICancellable`, `listeners/{IListener, ConsumerListener, LambdaListener + Factory}`, `NoLambdaFactoryException`.

**Data structures** (`EventBus`): `listenerCache: ConcurrentHashMap<Object,List<IListener>>`, `staticListenerCache: ConcurrentHashMap<Class<?>,List<IListener>>`, `listenerMap: ConcurrentHashMap<Class<?>,List<IListener>>` (the dispatch table, keyed by *event class*), `lambdaFactoryInfos: List<LambdaFactoryInfo>`.

**Subscription** — `subscribe(Object)` → `getListeners(klass, instance)` walks `getDeclaredMethods()` **plus the superclass chain**; `isValid(method)` requires `@EventHandler`, `void` return, exactly 1 param, non-primitive param type. Each valid method becomes a `LambdaListener(factory, klass, instance, method)`. Per-instance results are cached in `listenerCache`. Then each listener is inserted into `listenerMap.computeIfAbsent(eventTargetClass, CopyOnWriteArrayList)` via `insert()`, which sorts by **priority descending** (higher priority called first). `subscribe(Class)` is the static variant (instance=null, cached in `staticListenerCache`). `unsubscribe` mirrors this.

**Dispatch** — `post(T)`: `listenerMap.get(event.getClass())`, iterate, `IListener.call(event)`. The `ICancellable` overload first calls `setCancelled(false)`, then breaks out of the loop as soon as `isCancelled()` — that's how Meteor cancels events (e.g. `MeteorClient.EVENT_BUS.post(DropItemsEvent...).isCancelled()` at `LocalPlayerMixin.java:52`). Dispatch is synchronous on the posting thread; no executor.

**LambdaListener** — constructor: target = param[0].type; static = modifiers; priority from `@EventHandler`. It then builds a `MethodHandles.Lookup` (Java 8 hack via accessible `Lookup` ctor, else `factory.create(method, klass)`), `findStatic`/`findVirtual`, and calls `LambdaMetafactory.metafactory` to mint a `Consumer<Object>`; non-static listeners are **bound to the instance** at construction (`executor = (Consumer) handle.invoke(instance)`). `call(event)` = `executor.accept(event)`.

**Lambda factory selection** — `getLambdaFactory(klass)` scans `lambdaFactoryInfos` and returns the first factory whose registered `packagePrefix` matches `klass.getName().startsWith(prefix)`; throws `NoLambdaFactoryException` otherwise. Meteor registers one factory per addon package (`MeteorClient.java:109-115`), which is why addons can have private `@EventHandler` methods.

---

## 3. Module lifecycle & registries

`Module` (`Module.java:29`) — abstract, `ISerializable<Module>, Comparable<Module>`. Constructor (`:53-74`): captures `mc`, category, name/title/description/aliases, a random HSV `color` (`:63`), and resolves `addon` by matching the class's package against `AddonManager.ADDONS` (`:65-74`). Flags: `serialize`, `runInMainMenu`, `autoSubscribe` (default true, `:44-46`), `keybind`, `toggleOnBindRelease`, `chatFeedback`, `favorite` (`:48-51`). `settings = new Settings()` (`:40`).

**`toggle()`** (`:90-110`) — enable path: `active=true` → `Modules.get().addActive(this)` (posts `ActiveModulesChangedEvent`, `Modules.java:201-208`) → `settings.onActivated()` (fires every setting's `onModuleActivated` consumer, `Settings.java:29-35`) → if `runInMainMenu || Utils.canUpdate()`: `EVENT_BUS.subscribe(this)` (when `autoSubscribe`) then `onActivate()`. Disable path mirrors: unsubscribe + `onDeactivate()` first, then `active=false`, `removeActive`. `enable()`/`disable()` (`:112-118`) are idempotent wrappers.

**World join/leave** (`Modules.java:307-329`) — on `GameJoinedEvent`, every active non-main-menu module is re-subscribed and `onActivate()` re-run (modules deactivate while in menus but their `active` flag persists); on `GameLeftEvent` the inverse. `disableAll()` (`:331-337`).

**Serialization** — `Module.toTag` (`:156-169`): name, keybind, toggleOnKeyRelease, chatFeedback, favorite, `settings.toTag()`, `active`. `fromTag` (`:171-187`): restores, then `if (active != isActive()) toggle()` (`:183-184`). `Modules.toTag` (`:340-351`) packs a `ListTag` of modules; `Modules.fromTag` (`:353-365`) calls `disableAll()` then matches modules by name.

**Registries** (`Modules.java:60-72`) — `moduleInstances: Reference2ReferenceOpenHashMap<Class<? extends Module>, Module>`, `groups: Reference2ReferenceOpenHashMap<Category, List<Module>>`, `active: ArrayList<Module>`. `Modules.add(module)` (`:369-396`): category must be registered, removes a previous same-name module (and its color settings), inserts, registers color settings with `RainbowColors`. All modules are hardcoded: `init()` → `initCombat()/initPlayer()/initMovement()/initRender()/initWorld()/initMisc()` (`:78-86`). Fork additions in `initCombat()` (`:428-440`): TacticalBrain, CombatBrainModule, SmartCombatModule, AntiDetectionModule, ShieldAutoSwapModule, PathVisualizationOverlay, ThreatIndicatorModule, CombatNotificationManager, TargetESP, CombatDecisionOverlay — with the comment *"SmartCombat system (legacy — replaced by CombatBrainModule)"* (`:430-431`). Keybind handling: `onKey`/`onMouseClick` at `EventPriority.HIGH` (`:270-291`) iterate all modules matching their keybind.

---

## 4. Settings system

**`Setting<T>`** (`Setting.java:22`) — fields: `name/title/description`, `IVisible visible`, `defaultValue`, `value`, `onChanged: Consumer<T>`, `onModuleActivated: Consumer<Setting<T>>`, `module`, `lastWasVisible`. `get()` (`:50`); `set()` (`:54-59`) validates via `isValueValid` then fires `onChanged()`; `reset()` (`:65-68`); `parse(str)` (`:74-85`); `wasChanged()` = value differs from default (`:87-89`); `onActivated()` fires the module-activation consumer (`:95-97`); `isVisible()` (`:99-101`) = `visible == null || visible.isVisible()`.

**Serialization** — `toTag()` (`:117-125`): writes `"name"` then delegates to abstract `save(tag)`. `fromTag()` (`:129-135`): `load(tag)` + `onChanged()`. NBT shape per setting: `{name: "...", value: ...}`.

**Builder** — `SettingBuilder<B,V,S>` (`:167-210`): fluent `name()/description()/defaultValue()/visible(IVisible)/onChanged()/onModuleActivated()`, each returning `(B)this`; `build()` abstract. `visible` commonly takes a method ref to another setting's `get()`, e.g. `Config.java:35` `.visible(customFont::get)`.

**`SettingGroup`** (`SettingGroup.java:18`) — `name`, `sectionExpanded`, ordered `List<Setting<?>>`; `add()` (`:37-41`); `toTag` (`:60-73`) writes **only changed settings**; `fromTag` (`:75-88`) matches by name. **`Settings`** (`Settings.java:23`) — list of groups; `getDefaultGroup()` lazily creates `"General"` (`:86-89`); `createGroup(name[, expanded])` (`:91-99`); `onActivated()` fans out (`:29-35`); `registerColorSettings(module)` (`:101-114`) wires `ColorSetting`/`ColorListSetting` into `RainbowColors`; `toTag/fromTag` (`:156-183`) write/read only changed groups and reset first.

**`BoolSetting`** (`BoolSetting.java:13`) — `parseImpl` accepts `true/1/false/0/toggle` (`:21-26`), suggestions `[true, false, toggle]`; `save` puts boolean `"value"` (`:39-43`); `load` reads it (`:46-50`); `Builder` defaults to `false` (`:52-61`).

**`EnumSetting<T extends Enum<?>>`** (`EnumSetting.java:14`) — caches `values = defaultValue.getDeclaringClass().getEnumConstants()` (`:23`); suggestions = constant names (`:24-26`); `parseImpl` case-insensitive match (`:29-35`); `save` writes `value.toString()` (`:48-52`); `load` = `parse` (`:55-59`); `Builder` defaults to `null` (must call `.defaultValue(...)`, `:61-70`).

**`GenericSetting<T extends IGeneric<T>>`** (`GenericSetting.java:14`) — for composite values (e.g. `SettingColor`): `save` nests `value.toTag()` (`:40-44`), `load` calls `get().fromTag(...)` (`:46-51`), `resetImpl` copies the default (`:24-27`).

---

## 5. Rendering

### Renderer3D (`Renderer3D.java:16`)
Holds **two `MeshBuilder`s**: `lines` (DEBUG_LINES) and `triangles` (TRIANGLES) (`:17-18`), constructed with pipelines from `MeteorRenderPipelines` (`GameRendererMixin.java:100-103`). `begin()` (`:29-32`); `render(PoseStack)` (`:34-46`) draws both via `MeshRenderer.begin().attachments(mainRenderTarget).pipeline(...).mesh(builder, matrices).end()`.

Primitives, all CPU-side immediate mode:
- `line()` (`:50-61`): 2 vertices (`vec3().color().next()`) + `lines.line(i1,i2)`.
- `boxLines()` (`:63-113`): 8 corner vertices, 12 index-pairs; `excludeDir` skips edges adjacent to a face using `Dir`.
- `quad()` (`:121-145`): 4 vertices → `triangles.quad(...)` (6 indices), with per-corner colors for gradients (`gradientQuadVertical`, `:144-146`).
- `side()` (`:150-169`): 4 line indices + optional quad per `ShapeMode`.
- `boxSides()` (`:181-219`): 8 vertices, 6 quads (36 indices), per-face `excludeDir`; `box()` (`:225-240`) dispatches `mode.lines()`/`mode.sides()`.

### MeshBuilder (`MeshBuilder.java:22`)
Off-heap `ByteBuffer` vertex/indices. `begin()` (`:57-75`) snapshots camera x/z (3D camera-relative rendering: vertices stored relative to camera). `vec3()` (`:77-88`) writes 3 floats (x,z minus camera); `color()` (`:102-114`) writes RGBA bytes with global `alpha` multiplier — format is POSITION_COLOR (12B pos + 4B color). `next()` returns the vertex index (`:116-118`). `line()` writes 2 ints (`:120-129`); `quad()` writes 6 ints `i1,i2,i3,i3,i4,i1` (`:131-145`); `triangle()` 3 (`:147-157`). `ensureCapacity()` (`:171-201`) doubles buffers (initial 256/512 quads); `getVertexBuffer()/getIndexBuffer()` upload via `format.uploadImmediateVertexBuffer` (`:221-229`).

### MeshRenderer (`MeshRenderer.java:33`)
Singleton builder. `end()` (`:130-202`): pushes model-view matrix, applies `applyCameraPos()` (translate `-cameraPos.y`, since x/z already baked, `:204-207`), writes the `MeshData` UBO (`MeshUniforms.write(projection, modelViewStack)`, `:161`), creates a `RenderPass` against the color+depth attachments (`:163-165`), `setPipeline`, binds extra uniforms/samplers, sets buffers, `drawIndexed` (`:178-180`).

### Shaders (`MeteorRenderPipelines.java`)
`WORLD_COLORED` (`:39-48`): POSITION_COLOR TRIANGLES, `shaders/pos_color.vert|frag`, depth `ALWAYS_PASS` (no depth test/write), `TRANSLUCENT` blend, cull off. `WORLD_COLORED_LINES` (`:50-60`): DEBUG_LINES + line smooth. `WORLD_COLORED_DEPTH`/`WORLD_COLORED_LINES_DEPTH` (`:62-83`): depth `LESS_THAN_OR_EQUAL` (for the "depth" renderer, e.g. chams-style x-ray geometry). `precompile()` (`:209-224`) precompiles all pipelines at startup. `pos_color.vert`: `gl_Position = u_Proj * u_ModelView * pos` from std140 `MeshData {mat4 u_Proj; mat4 u_ModelView;}`; `pos_color.frag` passes `v_Color` straight out.

### Render3DEvent posting (`GameRendererMixin.java:92-139`)
Injected at the `profiler.popPush("hand")` point of `GameRenderer.renderLevel`: lazily creates the normal + depth `Renderer3D` (`:100-103`), fills the singleton `Render3DEvent` (`:104`, `Render3DEvent.java:12-32` — public mutable fields `matrices/renderer/depthRenderer/tickDelta/offsetX/Y/Z`), applies bob-view inverse correction to the model-view stack (`:108-118`, with Iris-shader fallback at `:122`), `renderer.begin()` + `depthRenderer.begin()` (`:128-129`), **`EVENT_BUS.post(event)`** (`:130`), then `renderer.render(bobStack)` + `depthRenderer.render(bobStack)` (`:131-132`).

### Post-process shaders (CHAMS / ENTITY_OUTLINE)
`PostProcessShaders` (`PostProcessShaders.java:10-50`): `@PreInit init()` constructs `ChamsShader`, `EntityOutlineShader`, `StorageOutlineShader` and subscribes (`:17-24`). Flow per frame:
1. `beginRender()` (`:26-30`) clears all three framebuffers — called from `LevelRendererMixin.onRenderLevelHead` (`LevelRendererMixin.java:118-121`).
2. Entity geometry capture: `LevelRendererMixin.onSubmitEntities` (`:132-188`) iterates `entityRenderStates`; for entities where `shader.shouldDraw(entity)`, submits the entity renderer into `outlineRenderCommandQueue` with a per-entity color (`:147-175`), then `meteor$pushEntityOutlineFramebuffer(shader.framebuffer)` + `renderDispatcher.renderAllFeatures()` + pop (`:180-187`). The push/pop (`:251-264`) swaps the real `entityOutlineTarget`/`targets.entityOutline` handle onto stacks — so the render dispatcher's outline buffer writes into the shader's own `TextureTarget` (`PostProcessShader.java:25`).
3. `submitEntityVertices()` (`PostProcessShaders.java:32-35`) — called after vanilla `endOutlineBatch()` (`LevelRendererMixin.java:196-199`) — re-renders the queued vertices into each shader's framebuffer via `vertexConsumerProvider::draw` (`EntityShader.java:30-32`; pre/post draw re-push/pop the framebuffer, `:21-28`).
4. Composite: `@EventHandler onRender(Render2DEvent)` (`PostProcessShaders.java:37-41`) runs `CHAMS.render()` then `ENTITY_OUTLINE.render()` — each is a fullscreen triangle pass (`MeshRenderer.fullscreen()`, `PostProcessShader.render()` `:52-68`) sampling the shader framebuffer as `u_Texture` with a `PostData` UBO (window size + `glfwGetTime()`), plus per-shader uniforms, into the main render target.

`ChamsShader` (`ChamsShader.java:36`) — POST_IMAGE pipeline; `shouldDraw()` = Chams module `isShader()` (`:102-105`); per-entity filter via `chams.entities` + `ignoreSelfDepth` (`:108-111`); `setupPass` writes `ImageData` (tint color) and binds an optional custom image texture loaded from `textures/chams.png|jpg` at `@PostInit` (`:47-81, 89-99`). `EntityOutlineShader` (`EntityOutlineShader.java:9`) — POST_OUTLINE pipeline; `shouldDraw()` = ESP `isShader()` (`:17-20`), per-entity `!esp.shouldSkip(entity)` (`:23-26`); `setupPass` writes `OutlineData` from `esp.outlineWidth/fillOpacity/shapeMode/glowMultiplier` (`:29-36`). `outline.frag` implements the two-shape-mode algorithm: filled center (shapeMode 0 = fill, discards on center; 1 = outline) and a width-sized neighborhood tap with squared-distance glow falloff (`assets/.../shaders/post-process/outline.frag`).

---

## 6. Rotations (`Rotations.java:27`)

Static utility with a `Pool<Rotation>` (`:28`) and priority-sorted `List<Rotation> rotations` (`:29`); state `serverYaw/serverPitch/rotationTimer`, `preYaw/prePitch`, `lastRotation/lastRotationTimer/sentLastRotation`, `rotating` (`:30-39`). `@PreInit init()` subscribes the static class (`:44-47`).

**`rotate(yaw, pitch, priority, clientSide, callback)`** (`:49-59`): pulls a pooled `Rotation`, sets it, and inserts at the first index where `priority > rotations.get(i).priority` — highest priority wins the next packet. Overloads default `clientSide=false`, `priority=0`, `callback=null` (`:61-75`).

**Server-side packet mechanics** — hooked into vanilla movement sending via `SendMovementPacketsEvent.Pre/Post`, posted in `LocalPlayerMixin` around `LocalPlayer.sendPosition` and vehicle send (`LocalPlayerMixin.java:170-188`):
- `onSendMovementPacketsPre` (`Rotations.java:86-112`): if camera is the player, `sentLastRotation=false`; if rotations queued: `rotating=true`, take `rotations.get(i)` (static cursor `i`), `setupMovementPacketRotation` → `setClientRotation` (saves `preYaw/prePitch`, sets `player.setYRot/setXRot`, `:119-125`) + `setCamRotation` (updates `serverYaw/Pitch`, resets `rotationTimer`, `:219-223`). The rotation is thus smuggled into the *vanilla* position packet that follows — server sees the aim with zero extra packets.
- `onSendMovementPacketsPost` (`:127-155`): runs the callback of the rotation just sent (`:131`) — this is the **delay callback**: it fires only once the rotation actually reached the wire; if it was the only rotation it becomes `lastRotation` and the client view is restored via `resetPreRotation()` (`:157-160`). Remaining rotations (i.e. lower-priority ones that missed the vanilla packet) are each sent as an **explicit `ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, horizontalCollision)`** (`Rotation.sendPacket`, `:239-242`) followed by its callback; `clientSide` rotations also re-apply the client rotation per packet. Last one is held as `lastRotation`, the rest freed, list cleared, `i=0`.

**Hold/smoothing**: after the queue empties, `lastRotation` keeps being applied to movement packets for `Config.rotationHoldTicks` (`:101-111`) so the server doesn't see the view snap back, then `rotating=false`. There is **no interpolation** in this class — client-side smoothing is achieved by the fact that rotations are server-side only: `setClientRotation` mutates the player's angles just for the packet and `resetPreRotation` restores the real view immediately. `rotationTimer` increments every `TickEvent.Pre` (`:162-165`) and is a monotonic "time since last server rotation" counter used by other modules.

---

## 7. Baritone integration

**`PathManagers`** (`PathManagers.java:13-49`) — static `INSTANCE = new NopPathManager()` (`:14`); `@PreInit init()` probes for Voyager (`meteordevelopment.voyager.PathManager`, reflectively instantiated, `:22-29`), then for Baritone (`baritone.api.BaritoneAPI` → `BaritoneUtils.IS_AVAILABLE=true`, `:31-36`); Baritone wins only if still Nop. Logs the active manager (`:38`). `get()` (`:16-18`). All modules call `PathManagers.get()` so they work with either backend.

**`IPathManager`** (`IPathManager.java:16-57`): `getName/isPathing/pause/resume/stop/moveTo(BlockPos[,ignoreY])/moveInDirection(yaw)/mine/follow/getTargetYaw/getTargetPitch/getSettings` + nested `ISettings` (walkOnWater/walkOnLava/step/noFall + save). `NopPathManager` is a no-op stub (`NopPathManager.java:17-110`).

**`BaritonePathManager`** (`BaritonePathManager.java:30`): ctor subscribes to the event bus and **registers a `BaritoneProcess`** with `PathingControlManager` (`:44`). `pause()` sets `pathingPaused=true` (`:58-60`); the registered `IBaritoneProcess.isActive()` returns that flag (`:195-197`) and its `onTick` clears all input keys and returns `PathingCommand(null, REQUEST_PAUSE)` (`:200-203`) — a Baritone-native pause. `resume()` clears it (`:63-65`); `stop()` = `cancelEverything()` (`:68-70`). `moveTo` uses `GoalXZ` (ignoreY) or `GoalGetToBlock` (`:73-80`); `moveInDirection` uses a custom `GoalDirection` goal re-anchored 100 blocks along the yaw every 20 ticks (`:83-86, 125-191`); `getTargetYaw/Pitch` read Baritone's player-context rotations (`:99-106`).

**KillAura usage** (`KillAura.java`): setting `pause-baritone` (Bool, default **true**, `:122-127`). On engage: `if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) { PathManagers.get().pause(); wasPathing = true; }` (`:357-359`). `stopAttacking()`: `if (wasPathing) { PathManagers.get().resume(); wasPathing = false; }` (`:363-373`). So KillAura freezes Baritone for the duration of an attack and resumes when done. **Fork caveat**: `CombatBrainModule.enableCombatModules` forcibly sets KillAura's `pause-baritone` to `false` (`CombatBrainModule.java:483-489`) so the brain's own Baritone control isn't blocked — matching commit `2b5dc4ad8` ("CRITICAL - disable KillAura.pause-baritone or it blocks pathing").

**`CombatIntegrationBridge`** (`CombatIntegrationBridge.java:15-84`): thin wrapper over the primary Baritone — `pathTo`→`GoalBlock` (`:30-33`), `pathNear`→`GoalNear` (`:38-41`), `pathToXZ`→`GoalXZ` (`:46-49`), `follow(entity)`→`followProcess.follow(e -> e == entity)` after cancel (`:55-62`), `stop()`→`cancelEverything()` (`:67-69`), `isPathing`/`getCurrentGoal` (`:74-83`). Used by `PathVisualizationOverlay` to draw the current Baritone goal as a 3D line + 2D distance HUD (`PathVisualizationOverlay.java:79-130`).

---

## 8. The SmartCombat system (fork additions)

Registered in `Modules.initCombat` (`Modules.java:428-440`), explicitly marked *"legacy — replaced by CombatBrainModule"*.

### `SmartCombatModule` (`SmartCombatModule.java:26`, 145 lines)
A simple KillAura-lite: settings `combat-mode` enum SMART/AGGRESSIVE/DEFENSIVE (`:33-38`), `targets` EntityTypeList (ZOMBIE/SKELETON/SPIDER/CREEPER, `:42-48`), `range` 4.5 (`:50-57`), `delay` 10 ticks (`:61-68`). `onActivate/onDeactivate` reset target+timer (`:77-87`). `onTick(TickEvent.Post)` (`:89-111`): decrements `attackTimer`; if target dead/invalid, re-acquires via `TargetUtils.get(this::isValidTarget, SortPriority.ClosestAngle)` (`:97-99`); calls `Rotations.rotate(getYaw, getPitch(Body))` with no priority (default 0) (`:103`); attacks via `mc.gameMode.attack` + `swing` when in range and timer expired, then resets timer (`:106-110`). `isValidTarget` (`:113-119`): not self/camera, alive, type in set, within range. Exposes getters for other modules (`:121-139`). Note: the `CombatMode` enum is stored but **not read anywhere in the module's own logic** — it's config-only for external consumers.

### `AntiDetectionModule` (`AntiDetectionModule.java:17`, 95 lines)
Anti-bot-detection for AFK combat: `rotation-randomness` (0–10, default 2.0) adds a uniform `±range` jitter to yaw **and** pitch every tick by directly mutating `mc.player.setYRot/setXRot` (`:70-77`); `sneak-interval` (40) + `sneak-duration` (5) drive a periodic sneak cycle by toggling `mc.options.keyShift.setDown` (`:79-93`). `onDeactivate` releases the sneak key (`:57-64`).

### `CombatBrainModule` (`CombatBrainModule.java:32`, 615 lines) — the actual "SmartCombat" engine
A tick-driven finite state machine over `BrainState {IDLE, SCANNING, ANALYZING, ENGAGING, RETREATING, HEALING, FLEEING, EMERGENCY_LOG}` (`:33-42`).

- **Settings** (`:44-158`): Targeting — target-entities, target-players, target-friendly (only if they hit you first), target-range (8). Engagement — auto-modules (auto-toggle KillAura/ArrowDodge/AutoArmor/AutoWeapon/AutoTotem), engage-threshold 0.4, flee-threshold 0.7, flee-distance 8, criticals, crystal. Analysis — analyze-gear, viability-check, follow-distance 3.5.
- **State** (`:160-170`): `state`, `currentTarget`, `tickCounter`, `stateTimer`, `followController: CombatFollowController`, `lastAnalysis: CombatTargetAnalyzer.TargetAnalysis`, `terrainGrid: CombatTerrainGrid`, `lastAttackedTimestamps: HashMap<UUID,Long>`, `stuckHealTicks`.
- **Lifecycle**: `onActivate` → SCANNING, allocates follow controller + terrain grid (`:176-187`); `onDeactivate` → stops follow, disables all managed modules (`:189-196`).
- **`onTick(TickEvent.Pre)`** (`:198-337`), throttled to every 2 ticks (`:205`): computes health+absorption, totem count, and `computeThreatLevel()` (`:454-478` — `healthFactor*0.4 + totemFactor*0.2 + min(nearbyThreats,0.4)`, clamped 0–1). Emergency: `health<=4 && totems==0 && threat>0.8` → `EMERGENCY_LOG` → hard disconnect via `mc.getConnection().disconnect(...)` then disables itself (`:213-216, 588-594`). Tracks hurt/hurtMob UUID timestamps for target-friendly (`:219-227`).
- **State machine** (`:230-328`): SCANNING→`findBestTarget()` via `TargetUtils.get(..., SortPriority.LowestDistance)` with entity-type + friend + friendly-gate filters (`:385-413`); ANALYZING→`CombatTargetAnalyzer.analyze()` + viability (`viability > 0.4`, `:242-257, 421-425`); ENGAGING→threat check, `enableCombatModules()`, `doEngageTick()` (terrain grid check, then `followController.follow(target, followDistance)` when out of range, `:538-559`); RETREATING→`doRetreatTick()` = `followController.flee(target, fleeDistance)` (`:561-566`); HEALING→sprint + flee ×1.5, with a 100-tick stuck-heal watchdog to FLEEING (`:292-312, 568-576`); FLEEING→sprint + flee (`:578-586`); a global 200-tick per-state watchdog resets to IDLE (`:330-334`).
- **Module management** (`:482-534`): `enableCombatModules()` first force-disables KillAura's `pause-baritone` (`:483-489`), then enables KillAura/ArrowDodge/AutoArmor/AutoWeapon/AutoTotem (+Criticals/CrystalAura when configured); `disableCombatModules`/`disableAllManagedModules` mirror.
- **Helpers** (`CombatFollowController.java:10-81`): `follow()` sets Baritone `followRadius=0` + `followOffsetDistance=distance` and runs the follow process with identity predicate (`:32-47`); `flee()` uses `GoalRunAway(distance, targetPos)` (`:49-61`); `stop()` cancels everything (`:63-69`). `CombatPathManager.java:22-141` (used by `TacticalBrain`, `TacticalBrain.java:61-88`): its own `IBaritoneProcess` (priority 100) serving `SET_GOAL_AND_PATH`/`REQUEST_PAUSE`; `enableCombatMode`/`enableFavoring` are stubs (`:98-106`).

**Relationship summary**: `SmartCombatModule` + `AntiDetectionModule` are the older, standalone pieces (targeted attack with randomized rotations + periodic sneak); `CombatBrainModule` supersedes the attack logic with a full threat/viability state machine and owns Baritone movement through `CombatFollowController`; `CombatIntegrationBridge` is a general Baritone wrapper consumed by the visualization overlay (`PathVisualizationOverlay`); `CombatPathManager` backs `TacticalBrain`.
