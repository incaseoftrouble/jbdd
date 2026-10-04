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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A hash map from {@code int} to non-null objects that boxes no key: open addressing with linear probing over two
 * arrays. Meant for memos keyed by functions or nodes. Every {@code int} is a valid key. Not thread-safe.
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

    /**
     * Maps {@code key} to {@code value} if it has no value, and otherwise to {@code remapping} of its value and
     * {@code value}, which must return non-null and must not modify this map. Returns the new value.
     */
    public V merge(int key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
        Objects.requireNonNull(value, "value");
        int slot = find(key);
        if (keys[slot] == key) {
            V merged = Objects.requireNonNull(remapping.apply(valueAt(slot), value), "value");
            values[slot] = merged;
            return merged;
        }
        insert(slot, key, value);
        return value;
    }

    /** Removes the entry of {@code key}, returning its value, or {@code null} if it had none. */
    public @Nullable V remove(int key) {
        int slot = find(key);
        if (keys[slot] != key) {
            return null;
        }
        V value = valueAt(slot);
        delete(slot);
        return value;
    }

    /**
     * Replaces each value by {@code function} of its key and itself, which must return non-null and must not modify
     * this map.
     */
    public void replaceAll(EntryFunction<V> function) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != FREE) {
                values[slot] = Objects.requireNonNull(function.apply(keys[slot], valueAt(slot)), "value");
            }
        }
    }

    /** Hands each entry to {@code consumer}, in no particular order. The consumer must not modify this map. */
    public void forEach(EntryConsumer<? super V> consumer) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != FREE) {
                consumer.accept(keys[slot], valueAt(slot));
            }
        }
    }

    /** A cursor over the entries, in the order of {@link #forEach}. The map must not be modified while it is used. */
    public EntryCursor<V> cursor() {
        return new EntryCursor<>(keys, values);
    }

    /** The keys, in the order of {@link #forEach}. The map must not be modified from this call until it is consumed. */
    public IntStream keyStream() {
        int[] slots = keys;
        return Arrays.stream(slots).filter(key -> key != FREE);
    }

    /**
     * The values, in the order of {@link #forEach}. The map must not be modified from this call until it is consumed.
     */
    public Stream<V> valueStream() {
        int[] slots = keys;
        @Nullable Object[] slotValues = values;
        return IntStream.range(0, slots.length)
                .filter(slot -> slots[slot] != FREE)
                .mapToObj(slot -> {
                    @SuppressWarnings("unchecked")
                    V value = (V) Objects.requireNonNull(slotValues[slot]);
                    return value;
                });
    }

    /** The entries, as a {@link HashMap} of the caller's own. */
    public Map<Integer, V> toMap() {
        Map<Integer, V> map = new HashMap<>(size + size / 3 + 1);
        forEach(map::put);
        return map;
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

    // Backward-shift deletion: an entry moves into the hole if the hole lies on its probe path, that is if it is at
    // least as far from its home slot as from the hole. No tombstones, so lookups never pay for removed entries.
    private void delete(int slot) {
        int mask = keys.length - 1;
        int hole = slot;
        for (int index = (hole + 1) & mask; keys[index] != FREE; index = (index + 1) & mask) {
            if (((index - HashTables.home(keys[index], mask)) & mask) >= ((index - hole) & mask)) {
                keys[hole] = keys[index];
                values[hole] = values[index];
                hole = index;
            }
        }
        keys[hole] = FREE;
        values[hole] = null;
        size -= 1;
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

    /**
     * Computes a value from one entry of a map.
     *
     * @param <V> the type of the values
     * @see #replaceAll(EntryFunction)
     */
    @FunctionalInterface
    public interface EntryFunction<V> {
        V apply(int key, V value);
    }

    /**
     * A cursor over the entries of a map: {@link #current()} is the value of the entry it stands on, {@link #key()}
     * its key.
     *
     * @param <V> the type of the values
     */
    public static final class EntryCursor<V> implements Cursor<V> {
        private final int[] keys;
        private final @Nullable Object[] values;
        private int slot = -1;

        private EntryCursor(int[] keys, @Nullable Object[] values) {
            this.keys = keys;
            this.values = values;
            advance();
        }

        @Override
        public boolean valid() {
            return slot < keys.length;
        }

        public int key() {
            assert valid();
            return keys[slot];
        }

        @Override
        @SuppressWarnings("unchecked")
        public V current() {
            Object value = values[slot];
            assert value != null;
            return (V) value;
        }

        @Override
        public boolean advance() {
            int next = slot + 1;
            while (next < keys.length && keys[next] == FREE) {
                next += 1;
            }
            slot = next;
            return next < keys.length;
        }
    }
}
