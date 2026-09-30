/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IntHashMapTest {
    // Clustered keys, and both extremes of the valid ones.
    private static int randomKey(Random random) {
        switch (random.nextInt(8)) {
            case 0:
                return Integer.MIN_VALUE + 1;
            case 1:
                return Integer.MAX_VALUE;
            case 2:
                return Math.max(random.nextInt(), Integer.MIN_VALUE + 1);
            default:
                return random.nextInt(2000) - 100;
        }
    }

    @Test
    void intIntAgreesWithHashMap() {
        Random random = new Random(0);
        for (int round = 0; round < 20; round++) {
            IntIntHashMap map = new IntIntHashMap(round);
            Map<Integer, Integer> reference = new HashMap<>();
            for (int step = 0; step < 3000; step++) {
                int key = randomKey(random);
                int value = random.nextInt();
                switch (random.nextInt(6)) {
                    case 0:
                        map.put(key, value);
                        reference.put(key, value);
                        break;
                    case 1:
                        assertEquals(
                                (int) reference.computeIfAbsent(key, k -> value), map.computeIfAbsent(key, k -> value));
                        break;
                    case 2:
                        Integer removed = reference.remove(key);
                        assertEquals(removed == null ? 17 : removed, map.remove(key, 17));
                        break;
                    case 3:
                        assertEquals(
                                (int) reference.merge(key, value, Integer::sum), map.merge(key, value, Integer::sum));
                        break;
                    default:
                        assertEquals(reference.getOrDefault(key, 17), map.get(key, 17));
                        assertEquals(reference.containsKey(key), map.containsKey(key));
                }
                assertEquals(reference.size(), map.size());
            }
            reference.forEach((key, value) -> assertEquals((int) value, map.get(key, ~value)));
            map.replaceAll((key, value) -> key ^ value);
            reference.replaceAll((key, value) -> key ^ value);
            Map<Integer, Integer> seen = new HashMap<>();
            map.forEach((key, value) -> assertNull(seen.put(key, value)));
            assertEquals(reference, seen);
            assertEquals(reference, map.toMap());
            // The streams and the cursor list the entries in forEach order.
            List<Integer> keys = new ArrayList<>();
            List<Integer> values = new ArrayList<>();
            map.forEach((key, value) -> {
                keys.add(key);
                values.add(value);
            });
            assertEquals(keys, map.keyStream().boxed().collect(Collectors.toList()));
            assertEquals(values, map.valueStream().boxed().collect(Collectors.toList()));
            List<Integer> cursorKeys = new ArrayList<>();
            List<Integer> cursorValues = new ArrayList<>();
            for (IntIntHashMap.EntryCursor cursor = map.cursor(); cursor.valid(); cursor.advance()) {
                cursorKeys.add(cursor.key());
                cursorValues.add(cursor.value());
            }
            assertEquals(keys, cursorKeys);
            assertEquals(values, cursorValues);

            map.clear();
            assertTrue(map.isEmpty());
            assertFalse(map.containsKey(Integer.MAX_VALUE));
            assertEquals(-1, map.get(0, -1));
            assertFalse(map.cursor().valid());
        }
    }

    @Test
    void intObjectAgreesWithHashMap() {
        Random random = new Random(1);
        for (int round = 0; round < 20; round++) {
            IntObjectHashMap<String> map = new IntObjectHashMap<>(round);
            Map<Integer, String> reference = new HashMap<>();
            for (int step = 0; step < 3000; step++) {
                int key = randomKey(random);
                String value = Integer.toString(random.nextInt());
                switch (random.nextInt(6)) {
                    case 0:
                        map.put(key, value);
                        reference.put(key, value);
                        break;
                    case 1:
                        assertEquals(reference.computeIfAbsent(key, k -> value), map.computeIfAbsent(key, k -> value));
                        break;
                    case 2:
                        assertEquals(reference.remove(key), map.remove(key));
                        break;
                    case 3:
                        assertEquals(
                                reference.merge(key, value, String::concat), map.merge(key, value, String::concat));
                        break;
                    default:
                        assertEquals(reference.get(key), map.get(key));
                        assertEquals(reference.containsKey(key), map.containsKey(key));
                }
                assertEquals(reference.size(), map.size());
            }
            reference.forEach((key, value) -> assertEquals(value, map.get(key)));
            map.replaceAll((key, value) -> key + value);
            reference.replaceAll((key, value) -> key + value);
            Map<Integer, String> seen = new HashMap<>();
            map.forEach((key, value) -> assertNull(seen.put(key, value)));
            assertEquals(reference, seen);
            assertEquals(reference, map.toMap());
            List<Integer> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();
            map.forEach((key, value) -> {
                keys.add(key);
                values.add(value);
            });
            assertEquals(keys, map.keyStream().boxed().collect(Collectors.toList()));
            assertEquals(values, map.valueStream().collect(Collectors.toList()));
            List<Integer> cursorKeys = new ArrayList<>();
            List<String> cursorValues = new ArrayList<>();
            for (IntObjectHashMap.EntryCursor<String> cursor = map.cursor(); cursor.valid(); cursor.advance()) {
                cursorKeys.add(cursor.key());
                cursorValues.add(cursor.value());
            }
            assertEquals(keys, cursorKeys);
            assertEquals(values, cursorValues);

            map.clear();
            assertTrue(map.isEmpty());
            assertNull(map.get(Integer.MAX_VALUE));
            assertEquals(0, map.keyStream().count());
            assertFalse(map.cursor().valid());
        }
    }

    @Test
    void rejectsWhatItCannotHold() {
        assertThrows(IllegalArgumentException.class, () -> new IntIntHashMap(-1));
        assertThrows(IllegalArgumentException.class, () -> new IntObjectHashMap<>(-1));
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new IntObjectHashMap<String>().put(0, null));
    }
}
