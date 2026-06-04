## 2024-05-15 - Iterable Emptiness Checks
**Learning:** Checking if an Iterable is empty by converting it to a Stream and collecting it to a List (e.g., `Streams.stream(iterable).toList().isEmpty()`) or finding any element (`Streams.stream(iterable).findAny().isPresent()`) introduces significant overhead from stream instantiation and list allocation.
**Action:** Use `!iterable.iterator().hasNext()` (for checking empty) or `iterable.iterator().hasNext()` (for checking not empty) to prevent unnecessary overhead.
