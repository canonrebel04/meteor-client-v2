## 2026-06-02 - Avoid Unnecessary Stream Allocations for Emptiness Checks
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` to check if an `Iterable` is empty or has elements is inefficient because it creates unnecessary stream instantiation and list allocation overhead.
**Action:** Always prefer using `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly on the `Iterable` (e.g., when evaluating `mc.level.getBlockCollisions()`) to prevent these overheads.
