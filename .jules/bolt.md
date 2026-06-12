## 2025-01-20 - Avoid Stream allocations for simple Iterable empty checks
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` on iterables like `mc.level.getBlockCollisions()` allocates unnecessary Stream objects and Lists on a hot path, causing memory overhead and garbage collection pressure.
**Action:** Always use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` for simple empty/not-empty checks on `Iterable` objects to avoid allocation overhead.
