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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NatSetsTest {
    /** The set in each class and representation: immutable, mutable, mutable as words and, if small, as an array. */
    private static List<NatSet> forms(BitSet bits, int span) {
        MutableNatSet words = MutableNatSet.dense(span);
        bits.stream().forEach(words::set);
        NatSet array = bits.cardinality() <= 16 ? MutableNatSet.of(bits.stream().toArray()) : words;
        return List.of(NatSet.copyOf(bits), MutableNatSet.copyOf(bits), words, array);
    }

    private static BitSet randomBits(Random random, int span) {
        BitSet bits = new BitSet();
        for (int count = random.nextInt(Math.min(span, 40)); count > 0; count--) {
            bits.set(random.nextInt(span));
        }
        return bits;
    }

    // Mostly the first with a few flips, so that agreement on a scope is neither always nor never the answer.
    private static BitSet nearby(Random random, BitSet bits, int span) {
        BitSet near = (BitSet) bits.clone();
        for (int flips = random.nextInt(3); flips > 0; flips--) {
            near.flip(random.nextInt(span));
        }
        return near;
    }

    @Test
    void agreementOnAScopeAgainstBitSet() {
        Random random = new Random(5);
        for (int span : new int[] {8, 64, 200, 3000}) {
            for (int round = 0; round < 60; round++) {
                BitSet first = randomBits(random, span);
                BitSet second = nearby(random, first, span);
                BitSet scope = randomBits(random, span);
                BitSet otherScope = randomBits(random, span);
                BitSet differing = (BitSet) first.clone();
                differing.xor(second);
                BitSet onScope = (BitSet) differing.clone();
                onScope.and(scope);
                BitSet onBoth = (BitSet) onScope.clone();
                onBoth.and(otherScope);
                for (NatSet a : forms(first, span)) {
                    for (NatSet b : forms(second, span)) {
                        for (NatSet s : forms(scope, span)) {
                            assertEquals(onScope.isEmpty(), NatSets.equalOn(a, b, s));
                            assertEquals(onScope.isEmpty(), NatSets.equalOn(b, a, s));
                            for (NatSet t : forms(otherScope, span)) {
                                assertEquals(onBoth.isEmpty(), NatSets.equalOnIntersection(a, b, s, t));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void combinationsLeaveTheirOperands() {
        NatSet a = MutableNatSet.of(1, 2);
        NatSet b = MutableNatSet.of(2, 3);

        assertEquals(NatSet.of(2), NatSets.intersection(a, b));
        assertEquals(NatSet.of(1, 2, 3), NatSets.union(a, b));
        assertEquals(NatSet.of(1), NatSets.without(a, b));
        assertEquals(NatSet.of(3), NatSets.without(b, a));
        assertEquals(NatSet.of(1, 2), a);
        assertEquals(NatSet.of(2, 3), b);

        NatSet c = NatSet.of(2, 3, 70);
        assertEquals(NatSet.of(1, 2, 3, 70), NatSets.union(List.of(a, b, c)));
        assertEquals(NatSet.of(2), NatSets.intersection(List.of(a, b, c)));
        assertEquals(NatSet.of(), NatSets.union(List.of()));
        assertEquals(NatSet.of(1, 2), NatSets.union(List.of(a)));
        assertEquals(NatSet.of(1, 2), NatSets.intersection(List.of(a)));
        assertEquals(NatSet.of(), NatSets.intersection(List.of(a, NatSet.of(), c)));
        assertThrows(IllegalArgumentException.class, () -> NatSets.intersection(List.of()));
        assertEquals(NatSet.of(1, 2, 3, 70), NatSets.lazyUnion(List.of(a, b, c)));
        assertFalse(NatSets.lazyUnion(List.of(a, b, c)) instanceof MutableNatSet);
        assertSame(a, NatSets.lazyUnion(List.of(a)));
        assertSame(c, NatSets.lazyUnion(List.of(NatSet.of(), c, MutableNatSet.create())));
        assertSame(NatSet.of(), NatSets.lazyUnion(List.of()));
        assertSame(NatSet.of(), NatSets.lazyUnion(List.of(MutableNatSet.create())));
        assertEquals(NatSet.of(1, 2), a);
        assertEquals(NatSet.of(2, 3), b);
    }

    @Test
    void mappedCopiesAndViews() {
        assertEquals(NatSet.of(2, 4, 6, 8), NatSets.copyOf(Set.of(1, 2, 3, 4), x -> 2 * x));
        NatSet doubled = NatSets.copyOf(Set.of(2, 4), x -> 2 * x);
        assertEquals(NatSet.of(4, 8), doubled);
        assertEquals(Set.of(2, 4), NatSets.asSet(doubled, x -> x / 2));
    }

    @Test
    void intEncoding() {
        int bits = 2 + 8 + 32;
        assertEquals(NatSet.of(1, 3, 5), NatSets.fromInt(bits));
        assertEquals(bits, NatSets.toInt(NatSets.fromInt(bits)));
    }

    @Test
    void incrementCountsInBinaryOverThePositions() {
        for (NatSet positions : new NatSet[] {NatSet.range(0, 3), NatSet.range(2, 5), NatSet.of(1, 5, 64)}) {
            MutableNatSet number = MutableNatSet.create();
            Set<NatSet> seen = new HashSet<>();
            do {
                assertTrue(positions.containsAll(number));
                assertTrue(seen.add(NatSet.copyOf(number)));
            } while (NatSets.increment(number, positions));
            // Past the largest value the counter is back at zero.
            assertTrue(number.isEmpty());
            assertEquals(8, seen.size());
        }
    }

    @Test
    void powerSetsOfEverySize() {
        for (int size : new int[] {0, 1, 2, 3, 7, 16}) {
            Set<NatSet> subsets = new HashSet<>();
            Cursor<NatSet> cursor = NatSets.powerSet(size);
            for (; cursor.valid(); cursor.advance()) {
                assertTrue(cursor.current().length() <= size);
                assertTrue(subsets.add(NatSet.copyOf(cursor.current())));
            }
            assertEquals(1 << size, subsets.size());
            assertFalse(cursor.advance());
        }
    }
}
