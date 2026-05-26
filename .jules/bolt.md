## 2024-05-20 - Avoid Stream Allocations for Empty Checks
**Learning:** Checking if an `Iterable` is empty using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` causes unnecessary stream instantiation and list allocation overhead, especially in hot loops like `onTick`.
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` instead to efficiently check for elements without unnecessary allocations.
