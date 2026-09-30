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
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * A hash map from {@code int} to {@code int} that boxes nothing: open addressing with linear probing over two
 * arrays. Meant for memos keyed by functions or nodes, which a {@code Map<Integer, Integer>} spends most of its time
 * boxing. Every {@code int} but {@link Integer#MIN_VALUE} is a valid key (checked by assertion only). Entries are only
 * ever added or overwritten; {@link #clear()} drops them all. Not thread-safe.
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
}
