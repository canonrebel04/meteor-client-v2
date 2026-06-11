## 2024-05-18 - Iterable Empty Check Overhead
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `findAny().isPresent()` creates unnecessary stream instantiations and list allocations for simple empty checks, which causes performance overhead when executed frequently (e.g., every tick for block collisions).
**Action:** Prefer checking `!iterable.iterator().hasNext()` directly on the Iterable to avoid allocations.
