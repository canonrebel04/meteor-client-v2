## 2024-06-25 - Prevent auto-boxing in `Comparator.comparing` on tick render path
**Learning:** In Minecraft rendering logic (`onTick`/`onRender` in things like `Nametags.java`), sorting entity lists runs every frame. Using standard `Comparator.comparing(e -> e.distanceToSqr(pos))` boxes the resulting `double` to a `Double` object. This causes a massive amount of garbage generation every tick.
**Action:** Always use primitive specific comparators like `Comparator.comparingDouble` when sorting using methods that return primitives (`double`, `int`, etc.), especially in render/tick loops to prevent allocation overhead and reduce GC pressure.
## 2024-05-23 - Avoid Repetitive Work in Hot Loops
**Learning:** Checking hotbar for items inside the entity iteration loop is an O(N) operation per tick (N being entities), and completely avoidable if item state is constant per tick.
**Action:** Extract repetitive item searches in modules `onTick` event handlers outside of the main loops.
## 2024-05-13 - Optimize regex filter compilation in BetterChat.java
**Learning:** Compiling regex patterns in a loop (using `Pattern.compile`) when the user edits a settings list is inefficient, particularly because elements that didn't change are recompiled. A naive implementation that removes elements from a list during iteration by using `remove(i)` without decrementing `i` skips the element that immediately followed it.
**Action:** Implemented caching for compiled `Pattern` objects. For a new configuration, we check the old `filterRegexList` to see if a given string has already been compiled (by comparing `Pattern.pattern().equals(...)`). If so, we reuse the old object instead of calling `Pattern.compile()`. Also fixed the off-by-one bug by inserting `i--;` inside the `catch` block that removes elements.
## 2023-10-27 - AutoShearer N+1 Loop Optimization
**Learning:** Pre-computing static requirements (like having shears) before an entity loop can completely avoid O(N) operations. In AutoShearer, checking for shears *before* iterating over entities not only prevents redundant `findInHotbar` checks, but also allows an early return, bypassing the `instanceof` and distance checks for all `entitiesForRendering` when no shears are available.
**Action:** When iterating over entities or items, extract required inventory lookups or state checks outside the loop. If the requirement is not met, return early to save CPU cycles.
## 2023-11-20 - Pre-fetch expensive operations outside loops
**Learning:** In Minecraft mods, checking inventory items inside a loop (like `onTick` combined with `blockInteractionRange`) causes redundant processing. Pre-fetching results outside the loop saves CPU cycles, especially when checking for specific items in the hotbar.
**Action:** When iterating over blocks in `onTick` (or similar high-frequency methods) and searching for an item, pre-fetch the item using `InvUtils.findInHotbar` before the loop and add an early return if not found.
## 2026-06-13 - Avoid Streams.stream() for empty checks
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` on Iterables (like `mc.level.getBlockCollisions()`) adds unnecessary stream instantiation and list allocation overhead.
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly to avoid allocation overhead on the hot path (like TickEvents).
## 2024-05-14 - Optimizing Entity Iteration Streams
**Learning:** Using `Streams.stream()` to wrap Iterables (like `mc.level.entitiesForRendering()`) in hot paths like `Step` module's tick calculations causes significant overhead due to stream creation and lambda allocations. Replacing the stream with a standard `for-each` loop halves the execution time.
**Action:** Avoid using `Streams.stream()` for hot path entity iteration; use standard `for-each` loops instead.
## 2024-06-25 - Avoid Math.pow for squaring in hot paths
**Learning:** `Math.pow(x, 2)` involves heavy JNI floating-point native calls, making it significantly slower than simple multiplication (`x * x`). This overhead is especially noticeable in hot paths like render loops (`onTick` / entity iteration).
**Action:** Always prefer simple multiplication (`x * x`) instead of `Math.pow(x, 2)` when squaring values in performance-sensitive paths.
