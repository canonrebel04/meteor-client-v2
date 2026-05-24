
## 2024-05-24 - Iterable emptiness checking
**Learning:** Using Streams.stream(iterable).toList().isEmpty() or Streams.stream(iterable).findAny().isPresent() on Iterables like mc.level.getBlockCollisions() introduces unnecessary stream instantiation and list allocation overhead.
**Action:** Use !iterable.iterator().hasNext() or iterable.iterator().hasNext() to check if an Iterable is empty or has elements, which avoids O(N) list allocation overhead and stream creation.
