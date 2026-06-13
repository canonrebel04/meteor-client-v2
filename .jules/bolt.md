## 2024-06-25 - Prevent auto-boxing in `Comparator.comparing` on tick render path
**Learning:** In Minecraft rendering logic (`onTick`/`onRender` in things like `Nametags.java`), sorting entity lists runs every frame. Using standard `Comparator.comparing(e -> e.distanceToSqr(pos))` boxes the resulting `double` to a `Double` object. This causes a massive amount of garbage generation every tick.
**Action:** Always use primitive specific comparators like `Comparator.comparingDouble` when sorting using methods that return primitives (`double`, `int`, etc.), especially in render/tick loops to prevent allocation overhead and reduce GC pressure.
## 2024-05-23 - Avoid Repetitive Work in Hot Loops
**Learning:** Checking hotbar for items inside the entity iteration loop is an O(N) operation per tick (N being entities), and completely avoidable if item state is constant per tick.
**Action:** Extract repetitive item searches in modules `onTick` event handlers outside of the main loops.
