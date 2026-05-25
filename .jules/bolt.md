## 2024-05-24 - Avoid Streams for Iterable Emptiness Check
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` to check if an `Iterable` is empty causes unnecessary stream instantiation and list allocation overhead.
**Action:** Use `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` to check if an Iterable is empty or has elements.
