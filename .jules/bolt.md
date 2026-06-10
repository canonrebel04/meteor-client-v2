## 2024-05-24 - Iterable Performance
**Learning:** Using Streams.stream(iterable).toList().isEmpty() causes unnecessary stream instantiation and list allocation overhead.
**Action:** Use !iterable.iterator().hasNext() or iterable.iterator().hasNext() instead.