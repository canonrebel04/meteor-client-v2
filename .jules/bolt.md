## 2024-05-24 - Avoid stream overhead for Iterable emptiness checks
**Learning:** Checking `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` creates unnecessary Stream objects and performs list allocations which adds overhead to hot paths like block collision checks in the movement modules.
**Action:** Always prefer `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly instead of wrapping the Iterable in a Stream to check if it's empty.
