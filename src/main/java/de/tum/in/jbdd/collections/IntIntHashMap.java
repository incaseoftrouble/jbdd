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
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * A hash map from {@code int} to {@code int} that boxes nothing: open addressing with linear probing over two
 * arrays. Meant for memos keyed by functions or nodes, which a {@code Map<Integer, Integer>} spends most of its time
 * boxing. Every {@code int} but {@link Integer#MIN_VALUE} is a valid key (checked by assertion only). Not thread-safe.
 */
public final class IntIntHashMap {
    // Marks a free slot, hence the one key not allowed.
    private static final int FREE = Integer.MIN_VALUE;
    private static final int MINIMUM_CAPACITY = 8;

    private int[] keys;
    private int[] values;
    private int size = 0;

    public IntIntHashMap() {
        this(MINIMUM_CAPACITY / 2);
    }

    /** A map holding {@code expectedSize} entries without growing. */
    public IntIntHashMap(int expectedSize) {
        if (expectedSize < 0) {
            throw new IllegalArgumentException("Negative size " + expectedSize);
        }
        int capacity = HashTables.capacityFor(expectedSize, MINIMUM_CAPACITY);
        keys = HashTables.freeKeys(capacity, FREE);
        values = new int[capacity];
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

    /** The value of {@code key}, or {@code absent} if it has none. */
    public int get(int key, int absent) {
        int slot = find(key);
        return keys[slot] == key ? values[slot] : absent;
    }

    /** Maps {@code key} to {@code value}, replacing any value it had. */
    public void put(int key, int value) {
        int slot = find(key);
        if (keys[slot] == key) {
            values[slot] = value;
        } else {
            insert(slot, key, value);
        }
    }

    /**
     * The value of {@code key}; if it has none, {@code mapping} applied to it, which becomes its value. The mapping
     * must not modify this map.
     */
    public int computeIfAbsent(int key, IntUnaryOperator mapping) {
        int slot = find(key);
        if (keys[slot] == key) {
            return values[slot];
        }
        int value = mapping.applyAsInt(key);
        insert(slot, key, value);
        return value;
    }

    /**
     * Maps {@code key} to {@code value} if it has no value, and otherwise to {@code remapping} of its value and
     * {@code value}, which must not modify this map. Returns the new value.
     */
    public int merge(int key, int value, IntBinaryOperator remapping) {
        int slot = find(key);
        if (keys[slot] == key) {
            int merged = remapping.applyAsInt(values[slot], value);
            values[slot] = merged;
            return merged;
        }
        insert(slot, key, value);
        return value;
    }

    /** Removes the entry of {@code key}, returning its value, or {@code absent} if it had none. */
    public int remove(int key, int absent) {
        int slot = find(key);
        if (keys[slot] != key) {
            return absent;
        }
        int value = values[slot];
        delete(slot);
        return value;
    }

    /** Replaces each value by {@code function} of its key and itself, which must not modify this map. */
    public void replaceAll(IntBinaryOperator function) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != FREE) {
                values[slot] = function.applyAsInt(keys[slot], values[slot]);
            }
        }
    }

    /** Hands each entry to {@code consumer}, in no particular order. The consumer must not modify this map. */
    public void forEach(EntryConsumer consumer) {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != FREE) {
                consumer.accept(keys[slot], values[slot]);
            }
        }
    }

    /** A cursor over the entries, in the order of {@link #forEach}. The map must not be modified while it is used. */
    public EntryCursor cursor() {
        return new EntryCursor(keys, values);
    }

    /** The keys, in the order of {@link #forEach}. The map must not be modified from this call until it is consumed. */
    public IntStream keyStream() {
        int[] slots = keys;
        return Arrays.stream(slots).filter(key -> key != FREE);
    }

    /**
     * The values, in the order of {@link #forEach}. The map must not be modified from this call until it is consumed.
     */
    public IntStream valueStream() {
        int[] slots = keys;
        int[] slotValues = values;
        return IntStream.range(0, slots.length)
                .filter(slot -> slots[slot] != FREE)
                .map(slot -> slotValues[slot]);
    }

    /** The entries, as a {@link HashMap} of the caller's own. */
    public Map<Integer, Integer> toMap() {
        Map<Integer, Integer> map = new HashMap<>(size + size / 3 + 1);
        forEach(map::put);
        return map;
    }

    /** Removes all entries, keeping the capacity. */
    public void clear() {
        Arrays.fill(keys, FREE);
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

    private void insert(int freeSlot, int key, int value) {
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
        size -= 1;
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldValues = values;
        keys = HashTables.freeKeys(2 * oldKeys.length, FREE);
        values = new int[keys.length];
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
     * @see #forEach(EntryConsumer)
     */
    @FunctionalInterface
    public interface EntryConsumer {
        void accept(int key, int value);
    }

    /**
     * A walk over the entries of a map in the shape of {@code Cursor}: positioned on creation, its key and value
     * defined while {@link #valid()} holds.
     */
    public static final class EntryCursor {
        private final int[] keys;
        private final int[] values;
        private int slot = -1;

        private EntryCursor(int[] keys, int[] values) {
            this.keys = keys;
            this.values = values;
            advance();
        }

        public boolean valid() {
            return slot < keys.length;
        }

        public int key() {
            assert valid();
            return keys[slot];
        }

        public int value() {
            assert valid();
            return values[slot];
        }

        /** Moves to the next entry, returning what {@link #valid()} now reports. */
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
