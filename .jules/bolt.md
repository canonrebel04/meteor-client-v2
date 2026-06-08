## 2024-05-24 - Iterable Emptiness Check Optimization
**Learning:** Checking emptiness or presence on iterables by wrapping them in Streams (like `Streams.stream(iterable).toList().isEmpty()` or `.findAny().isPresent()`) introduces significant object allocation overhead (streams and list construction).
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` directly to test for emptiness or presence of elements, saving milliseconds and memory allocations per frame on hot paths like movement collisions.
