## 2024-05-29 - Optimize Iterable Empty Checks
**Learning:** Checking if an Iterable is empty by wrapping it in `Streams.stream(iterable).toList().isEmpty()` or `Streams.stream(iterable).findAny().isPresent()` creates unnecessary stream instantiation and list allocation overhead.
**Action:** When checking if an Iterable has elements, especially for performance-sensitive operations like block collision checks (`mc.level.getBlockCollisions()`), prefer using `iterable.iterator().hasNext()` directly. This avoids unnecessary object creation and improves efficiency.
