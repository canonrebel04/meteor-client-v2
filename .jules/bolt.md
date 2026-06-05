## 2024-05-20 - Iterable Emptiness Check Overhead
**Learning:** Checking if an `Iterable` is empty by converting it to a Stream and then to a List (e.g., `Streams.stream(iterable).toList().isEmpty()`) introduces unnecessary stream instantiation and list allocation overhead, especially in hot paths like movement handling.
**Action:** Always use `!iterable.iterator().hasNext()` to check for emptiness on an `Iterable` to avoid allocation overhead.
