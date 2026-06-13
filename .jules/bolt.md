## 2026-06-13 - Avoid Streams.stream() for empty checks
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` on Iterables (like `mc.level.getBlockCollisions()`) adds unnecessary stream instantiation and list allocation overhead.
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly to avoid allocation overhead on the hot path (like TickEvents).
