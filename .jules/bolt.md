## 2024-06-11 - [Optimize Block Collisions]
**Learning:** Using `Streams.stream(iterable).toList().isEmpty()` on potentially large iterables like `mc.level.getBlockCollisions()` allocates a stream and a full list on every tick, which is heavily taxing.
**Action:** Use `!iterable.iterator().hasNext()` instead to perform an O(1) empty check without stream instantiation or list allocation overhead.
