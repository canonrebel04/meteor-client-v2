## 2024-05-31 - Iterables overhead in game tick loops
**Learning:** Checking whether an `Iterable` is empty via `Streams.stream(iterable).findAny().isPresent()` or `toList().isEmpty()` introduces unnecessary object instantiation overhead in critical game loops, causing an impact on performance.
**Action:** Always prefer `!iterable.iterator().hasNext()` to test if an `Iterable` is empty to eliminate the stream initialization overhead altogether.
