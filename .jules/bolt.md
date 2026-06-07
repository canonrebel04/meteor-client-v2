## 2024-05-24 - Iterable emptiness checks
**Learning:** Checking if an Iterable (like `mc.level.getBlockCollisions`) is empty using `Streams.stream(iterable).toList().isEmpty()` or `findAny().isPresent()` introduces unnecessary stream instantiation and list allocation overhead. This is a common anti-pattern in the codebase.
**Action:** Always prefer `!iterable.iterator().hasNext()` or `iterable.iterator().hasNext()` to check if an Iterable has elements, especially in hot paths like movement modules.
