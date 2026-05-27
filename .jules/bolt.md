## 2026-05-27 - Optimize Iterable Emptiness Checks
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` for checking emptiness causes unnecessary list allocation and stream instantiation overhead, which is particularly bad in hot paths like the movement event handlers.
**Action:** Prefer using `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly on the Iterable (e.g., `mc.level.getBlockCollisions() `) to check for emptiness.
