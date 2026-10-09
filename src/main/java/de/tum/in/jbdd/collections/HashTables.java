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

/** What {@link IntIntHashMap} and {@link IntObjectHashMap} share: power-of-two tables filled to at most one half. */
final class HashTables {
    private HashTables() {}

    static int capacityFor(int expectedSize, int minimumCapacity) {
        int capacity = minimumCapacity;
        while (isFull(expectedSize, capacity)) {
            if (capacity >= 1 << 30) {
                throw new IllegalArgumentException("Too many entries: " + expectedSize);
            }
            capacity *= 2;
        }
        return capacity;
    }

    static boolean isFull(int size, int capacity) {
        return 2L * size > capacity;
    }

    static int home(int key, int mask) {
        // Keys such as node ids cluster, and their low bits alone would say little about them.
        int hash = key * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & mask;
    }

    static int[] freeKeys(int capacity, int free) {
        int[] keys = new int[capacity];
        Arrays.fill(keys, free);
        return keys;
    }
}
