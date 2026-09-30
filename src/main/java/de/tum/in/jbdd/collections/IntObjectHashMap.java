/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2026 Tobias Meggendorfer.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * JBDD is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD. If not, see <http://www.gnu.org/licenses/>.
 */
package de.tum.in.jbdd.collections;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * A hash map from {@code int} to non-null objects that boxes no key: open addressing with linear probing over two
 * arrays. Meant for memos keyed by functions or nodes. Every {@code int} is a valid key. Entries are only ever added
 * or overwritten; {@link #clear()} drops them all. Not thread-safe.
 *
 * @param <V> the type of the values
 */
public final class IntObjectHashMap<V> {
    // Marks a free slot, hence the one key not allowed.
    private static final int FREE = Integer.MIN_VALUE;
    private static final int MINIMUM_CAPACITY = 8;

    private int[] keys;
    private @Nullable Object[] values;
    private int size = 0;

    public IntObjectHashMap() {
        this(MINIMUM_CAPACITY / 2);
    }

    /** A map holding {@code expectedSize} entries without growing. */
    public IntObjectHashMap(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("Negative size " + expectedSize);
        }
        int capacity = HashTables.capacityFor(expectedSize, MINIMUM_CAPACITY);
        keys = HashTables.freeKeys(capacity, FREE);
        values = new Object[capacity];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean containsKey(int key) {
        return keys[find(key)] == key;
    }

    /** The value of {@code key}, or {@code null} if it has none. */
    public @Nullable V get(int key) {
        int slot = find(key);
        return keys[slot] == key ? valueAt(slot) : null;
    }

    /** Maps {@code key} to {@code value}, replacing any value it had. */
    public void put(int key, V value) {
        Objects.requireNonNull(value, "value");
        int slot = find(key);
        if (keys[slot] == key) {
            values[slot] = value;
        } else {
            insert(slot, key, value);
        }
    }

    /**
     * The value of {@code key}; if it has none, {@code mapping} applied to it, which becomes its value. The mapping
     * must return non-null and must not modify this map.
     */
    public V computeIfAbsent(int key, IntFunction<? extends V> mapping) {
        V present = get(key);
        if (present != null) {
            return present;
        }
        V value = mapping.apply(key);
        put(key, value);
        return value;
    }

    /** Hands each entry to {@code consumer}, in no particular order. The consumer must not modify this map. */
    public void forEach(EntryConsumer<? super V> consumer) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != FREE) {
                consumer.accept(keys[slot], valueAt(slot));
            }
        }
    }

    /** Removes all entries, keeping the capacity. */
    public void clear() {
        Arrays.fill(keys, FREE);
        Arrays.fill(values, null);
        size = 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("{");
        forEach((key, value) -> {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(key).append('=').append(value);
        });
        return builder.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private V valueAt(int slot) {
        Object value = values[slot];
        assert value != null;
        return (V) value;
    }

    // The slot holding key, or the free slot where it would go.
    private int find(int key) {
        assert key != FREE : "Integer.MIN_VALUE is not a valid key";
        int mask = keys.length - 1;
        int slot = HashTables.home(key, mask);
        while (keys[slot] != key && keys[slot] != FREE) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void insert(int freeSlot, int key, V value) {
        int slot = freeSlot;
        if (HashTables.isFull(size + 1, keys.length)) {
            grow();
            slot = find(key);
        }
        keys[slot] = key;
        values[slot] = value;
        size += 1;
    }

    private void grow() {
        int[] oldKeys = keys;
        @Nullable Object[] oldValues = values;
        keys = HashTables.freeKeys(2 * oldKeys.length, FREE);
        values = new Object[keys.length];
        for (int slot = 0; slot < oldKeys.length; slot++) {
            if (oldKeys[slot] != FREE) {
                int target = find(oldKeys[slot]);
                keys[target] = oldKeys[slot];
                values[target] = oldValues[slot];
            }
        }
    }

    /**
     * Receives one entry of a map.
     *
     * @param <V> the type of the values
     * @see #forEach(EntryConsumer)
     */
    @FunctionalInterface
    public interface EntryConsumer<V> {
        void accept(int key, V value);
    }
}
