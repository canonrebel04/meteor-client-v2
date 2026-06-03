## 2024-05-24 - Iterable allocation overhead
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` to check if an `Iterable` is empty incurs unnecessary stream instantiation and list allocation overhead.
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly to prevent allocations.
