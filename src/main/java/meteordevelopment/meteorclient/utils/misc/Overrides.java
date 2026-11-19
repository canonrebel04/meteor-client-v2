/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Temporary overrides of other modules' settings (C98): the first override of a key remembers the user's value,
 * {@link #restoreAll()} puts every original back (in reverse order) unless the user changed the setting meanwhile.
 * Replaces ad-hoc "set and forget" changes that used to leave helper modules altered after combat.
 */
public final class Overrides {
    private static final class Entry<T> {
        final Supplier<T> get;
        final Consumer<T> set;
        final T original;
        T applied;

        Entry(Supplier<T> get, Consumer<T> set, T original) {
            this.get = get;
            this.set = set;
            this.original = original;
        }
    }

    private final LinkedHashMap<Object, Entry<?>> entries = new LinkedHashMap<>();

    /** Set {@code value}, remembering the current value the first time this key is overridden. */
    public <T> void apply(Object key, Supplier<T> get, Consumer<T> set, T value) {
        @SuppressWarnings("unchecked")
        Entry<T> e = (Entry<T>) entries.get(key);
        if (e == null) {
            e = new Entry<>(get, set, get.get());
            entries.put(key, e);
        }
        e.applied = value;
        if (!Objects.equals(get.get(), value)) set.accept(value);
    }

    public boolean has(Object key) {
        return entries.containsKey(key);
    }

    /** Restore one key; a value the user changed since our override is left alone. */
    public void restore(Object key) {
        Entry<?> e = entries.remove(key);
        if (e != null) restoreEntry(e);
    }

    private static <T> void restoreEntry(Entry<T> e) {
        if (Objects.equals(e.get.get(), e.applied)) e.set.accept(e.original);
    }

    public void restoreAll() {
        Object[] keys = entries.keySet().toArray();
        for (int i = keys.length - 1; i >= 0; i--) restore(keys[i]);
    }

    public int size() {
        return entries.size();
    }

    public Map<Object, Object> originals() {
        Map<Object, Object> m = new LinkedHashMap<>();
        entries.forEach((k, v) -> m.put(k, v.original));
        return m;
    }
}
