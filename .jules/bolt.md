## 2024-05-23 - Avoid Repetitive Work in Hot Loops
**Learning:** Checking hotbar for items inside the entity iteration loop is an O(N) operation per tick (N being entities), and completely avoidable if item state is constant per tick.
**Action:** Extract repetitive item searches in modules `onTick` event handlers outside of the main loops.
