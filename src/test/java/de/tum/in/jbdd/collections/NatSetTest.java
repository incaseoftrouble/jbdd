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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NatSetTest {
    // Spans that keep a set in the array, move it to words, or mix both.
    private static final int[] SPANS = {8, 64, 200, 5000, 1 << 20};

    private static int element(Random random, int span) {
        return random.nextInt(span);
    }

    private static BitSet randomBitSet(Random random, int span, int count) {
        BitSet set = new BitSet();
        for (int index = 0; index < count; index++) {
            set.set(element(random, span));
        }
        return set;
    }

    // A set with the elements of reference, of either class and, for more than one element, either representation.
    private static NatSet natSet(Random random, BitSet reference) {
        switch (random.nextInt(6)) {
            case 0:
                return NatSet.copyOf(reference);
            case 4: {
                MutableNatSet set = MutableNatSet.dense(reference.length());
                reference.stream().forEach(set::set);
                return NatSet.copyOf(set);
            }
            case 5:
                return NatSet.of(reference.stream().toArray());
            case 1: {
                MutableNatSet set = MutableNatSet.create();
                reference.stream().forEach(set::set);
                return set;
            }
            case 2: {
                MutableNatSet set = MutableNatSet.dense(reference.length());
                reference.stream().forEach(set::set);
                return set;
            }
            default: {
                MutableNatSet set = MutableNatSet.copyOf(reference);
                set.optimize();
                return set;
            }
        }
    }

    private static void assertSameContents(BitSet reference, NatSet set) {
        assertEquals(reference.cardinality(), set.size());
        assertEquals(reference.isEmpty(), set.isEmpty());
        assertArrayEquals(reference.stream().toArray(), set.toIntArray());
        assertArrayEquals(reference.stream().toArray(), set.intStream().toArray());
        List<Integer> forEach = new ArrayList<>();
        set.forEach(forEach::add);
        assertEquals(reference.stream().boxed().collect(Collectors.toList()), forEach);
        List<Integer> iterated = new ArrayList<>();
        set.boxed().iterator().forEachRemaining(iterated::add);
        assertEquals(forEach, iterated);
        assertEquals(reference, set.toBitSet());
        assertArrayEquals(reference.toLongArray(), set.toLongArray());
        assertEquals(set, NatSet.valueOf(reference.toLongArray()));
        assertEquals(set, MutableNatSet.valueOf(reference.toLongArray()));
        assertEquals(reference.length(), set.length());
        assertEquals(reference.isEmpty() ? -1 : reference.nextSetBit(0), set.first());
        assertEquals(reference.length() - 1, set.last());

        Set<Integer> boxed = new TreeSet<>(reference.stream().boxed().collect(Collectors.toList()));
        assertEquals(boxed, set.boxed());
        assertEquals(set.boxed(), boxed);
        assertEquals(new HashSet<>(boxed), set.boxed());
        assertEquals(boxed.hashCode(), set.boxed().hashCode());
        // A set is not a Set: only the view is equal to one.
        assertNotEquals(set, set.boxed());
        assertNotEquals(set.boxed(), set);
        assertEquals(boxed.toString(), set.toString());
    }

    private static void assertSameQueries(Random random, BitSet reference, NatSet set, int span) {
        for (int probe = 0; probe < 20; probe++) {
            int index = element(random, span + 70);
            assertEquals(reference.get(index), set.contains(index));
            assertEquals(reference.nextSetBit(index), set.nextSetBit(index));
            assertEquals(reference.previousSetBit(index), set.previousSetBit(index));
            assertEquals(reference.nextClearBit(index), set.nextClearBit(index));
        }
        assertFalse(set.contains(-1));
        assertEquals(-1, set.previousSetBit(-1));
    }

    @Test
    void mutationsAgreeWithBitSet() {
        Random random = new Random(0);
        for (int span : SPANS) {
            for (int round = 0; round < 40; round++) {
                BitSet reference = new BitSet();
                MutableNatSet set = random.nextBoolean() ? MutableNatSet.create() : MutableNatSet.dense(span);
                for (int step = 0; step < 60; step++) {
                    int index = element(random, span);
                    int to = Math.min(span, index + random.nextInt(Math.min(span, 300)));
                    BitSet otherReference = randomBitSet(random, span, random.nextInt(span < 64 ? 8 : 40));
                    NatSet other = natSet(random, otherReference);
                    switch (random.nextInt(14)) {
                        case 0:
                        case 1:
                        case 2:
                            reference.set(index);
                            set.set(index);
                            break;
                        case 3:
                            reference.clear(index);
                            set.clear(index);
                            break;
                        case 4:
                            reference.flip(index);
                            set.flip(index);
                            break;
                        case 5:
                            reference.set(index, to);
                            set.set(index, to);
                            break;
                        case 6:
                            reference.clear(index, to);
                            set.clear(index, to);
                            break;
                        case 7:
                            reference.flip(index, to);
                            set.flip(index, to);
                            break;
                        case 8:
                            reference.and(otherReference);
                            set.and(other);
                            break;
                        case 9:
                            reference.or(otherReference);
                            set.or(other);
                            break;
                        case 10:
                            reference.andNot(otherReference);
                            set.andNot(other);
                            break;
                        case 11:
                            reference.xor(otherReference);
                            set.xor(other);
                            break;
                        case 12:
                            set.optimize();
                            break;
                        default:
                            if (random.nextInt(10) == 0) {
                                reference.clear();
                                set.clear();
                            }
                    }
                    assertSameContents(reference, set);
                }
                assertSameQueries(random, reference, set, span);
            }
        }
    }

    @Test
    void combinationsAgreeWithBitSet() {
        Random random = new Random(1);
        for (int span : SPANS) {
            for (int round = 0; round < 200; round++) {
                BitSet first = randomBitSet(random, span, random.nextInt(span < 64 ? 6 : 30));
                BitSet second = randomBitSet(random, span, random.nextInt(span < 64 ? 6 : 30));
                NatSet firstSet = natSet(random, first);
                NatSet secondSet = natSet(random, second);

                BitSet union = (BitSet) first.clone();
                union.or(second);
                BitSet intersection = (BitSet) first.clone();
                intersection.and(second);
                BitSet difference = (BitSet) first.clone();
                difference.andNot(second);
                assertSameContents(union, firstSet.union(secondSet));
                assertSameContents(intersection, firstSet.intersection(secondSet));
                assertSameContents(difference, firstSet.difference(secondSet));
                assertEquals(first.intersects(second), firstSet.intersects(secondSet));
                assertEquals(intersection.equals(second), firstSet.containsAll(secondSet));
                assertEquals(first.equals(second), firstSet.equals(secondSet));
                assertEquals(second.stream().allMatch(first::get), secondSet.allMatch(firstSet::contains));
                assertEquals(second.stream().anyMatch(first::get), secondSet.anyMatch(firstSet::contains));
                int sizes = Integer.compare(first.cardinality(), second.cardinality());
                int order = sizes == 0
                        ? Arrays.compare(
                                first.stream().toArray(), second.stream().toArray())
                        : sizes;
                assertEquals(Integer.signum(order), Integer.signum(NatSet.ORDER.compare(firstSet, secondSet)));
                // The operands are not changed, and a copy is independent of its origin.
                assertSameContents(first, firstSet);
                MutableNatSet copy = MutableNatSet.copyOf(firstSet);
                copy.set(span + 1);
                assertSameContents(first, firstSet);
            }
        }
    }

    @Test
    void factories() {
        assertSame(NatSet.of(), NatSet.of(new int[0]));
        assertSame(NatSet.of(5), NatSet.of(5, 5));
        assertSame(NatSet.of(), NatSet.copyOf(new BitSet()));
        assertSame(NatSet.of(3), NatSet.copyOf(BitSet.valueOf(new long[] {8L})));
        assertSame(NatSet.of(7), NatSet.range(7, 8));
        assertSame(NatSet.of(), NatSet.range(7, 7));
        assertEquals(Set.of(1, 4, 1000), NatSet.of(1000, 4, 1, 4).boxed());
        assertEquals(Set.of(2, 3, 4), NatSet.range(2, 5).boxed());
        assertEquals(Set.of(2, 3, 900), NatSet.copyOf(List.of(900, 2, 3)).boxed());
        assertEquals(NatSet.of(9), NatSet.of(9, 9));
        assertEquals(NatSet.of(200), NatSet.of(200));
        assertNotEquals(NatSet.of(1), NatSet.of(1, 2));

        assertThrows(AssertionError.class, () -> NatSet.of(-1));
        assertThrows(AssertionError.class, () -> NatSet.of(1, -1));
        assertThrows(AssertionError.class, () -> NatSet.range(-1, 4));
    }

    @Test
    void immutableAndMutable() {
        // What the factories and operations return never changes, and is shared where that is the result.
        NatSet small = NatSet.of(1, 2, 3);
        NatSet large = NatSet.range(0, 1000);
        for (NatSet set : List.of(NatSet.of(), NatSet.of(3), small, large)) {
            assertFalse(set instanceof MutableNatSet);
            assertThrows(UnsupportedOperationException.class, () -> set.boxed().add(5000));
            assertThrows(UnsupportedOperationException.class, () -> set.boxed().clear());
            assertSame(set, NatSet.copyOf(set));
        }
        assertSame(NatSet.of(3), NatSet.of(3));
        assertSame(large, large.union(small));
        assertSame(large, small.union(large));
        assertSame(small, large.intersection(small));
        assertSame(small, small.difference(NatSet.of(7, 8)));

        // A mutable operand is never shared: the result would change with it.
        MutableNatSet mutable = MutableNatSet.of(1, 2, 3, 700);
        NatSet union = mutable.union(NatSet.of());
        NatSet frozen = NatSet.copyOf(mutable);
        mutable.set(5);
        assertEquals(Set.of(1, 2, 3, 700), union.boxed());
        assertEquals(Set.of(1, 2, 3, 700), frozen.boxed());
        assertEquals(frozen, union);
        assertEquals(frozen.hashCode(), MutableNatSet.copyOf(frozen).hashCode());
        assertEquals(frozen, MutableNatSet.copyOf(frozen));

        Set<Integer> boxed = mutable.boxed();
        assertTrue(boxed.add(6));
        assertFalse(boxed.add(6));
        assertTrue(boxed.remove(2));
        assertFalse(boxed.remove((Object) "2"));
        boxed.removeIf(element -> element > 4);
        assertEquals(Set.of(1, 3), boxed);
        assertNotSame(mutable, MutableNatSet.copyOf(mutable));
    }

    @Test
    void forEachOverEveryRunStructure() {
        // Runs and single elements, within a word, across word boundaries and up to the last bit of a word, in each
        // combination of a sparse or run-structured beginning and a sparse or run-structured rest.
        Random random = new Random(2);
        for (boolean runsFirst : new boolean[] {true, false}) {
            for (boolean runsAfter : new boolean[] {true, false}) {
                BitSet reference = new BitSet();
                for (int block = 0; block < 40; block++) {
                    int base = block * 100;
                    boolean runs = base < 1024 ? runsFirst : runsAfter;
                    if (runs) {
                        reference.set(base, base + 10 + random.nextInt(80));
                    } else {
                        reference.set(base + random.nextInt(100));
                    }
                }
                reference.set(4031, 4096 + 64);
                assertSameContents(reference, NatSet.copyOf(reference));
                assertSameContents(reference, MutableNatSet.copyOf(reference));
            }
        }
    }

    @Test
    void representationsOfOneSetAreEqual() {
        // The same few elements, far apart and close together, in every representation: equal to each other, with
        // one hash code, and ordered alike.
        Random random = new Random(3);
        for (int span : new int[] {64, 1 << 20}) {
            for (int round = 0; round < 200; round++) {
                int[] elements = random.ints(1 + random.nextInt(6), 0, span).toArray();

                MutableNatSet array = MutableNatSet.create();
                MutableNatSet words = MutableNatSet.dense(span);
                for (int element : elements) {
                    array.set(element);
                    words.set(element);
                }
                MutableNatSet optimized = MutableNatSet.copyOf(words);
                optimized.optimize();
                NatSet immutable = NatSet.of(elements);
                NatSet frozenWords = NatSet.copyOf(words);

                assertNotNull(NatSetUtil.wordsOf(words));
                if (span > 64 && array.size() > 1) {
                    // Far apart, a few elements stay in the array and optimize() moves words back to it.
                    assertNull(NatSetUtil.wordsOf(array));
                    assertNull(NatSetUtil.wordsOf(optimized));
                }

                List<NatSet> representations = List.of(array, words, optimized, immutable, frozenWords);
                Set<Integer> boxed = new HashSet<>(array.boxed());
                for (NatSet first : representations) {
                    assertEquals(boxed.hashCode(), first.boxed().hashCode());
                    assertEquals(boxed, first.boxed());
                    for (NatSet second : representations) {
                        assertEquals(first, second);
                        assertEquals(first.hashCode(), second.hashCode());
                        assertEquals(0, NatSet.ORDER.compare(first, second));
                    }
                }
            }
        }
    }

    @Test
    void largestElement() {
        // Integer.MAX_VALUE is an element like any other; iterating past it must not overflow.
        int max = Integer.MAX_VALUE;
        MutableNatSet mutable = MutableNatSet.of(3, max);
        List<NatSet> sets = List.of(NatSet.of(max), NatSet.of(3, max), mutable);
        for (NatSet set : sets) {
            assertEquals(max, set.last());
            assertTrue(set.contains(max));
            assertEquals(set, NatSet.copyOf(set));
            assertTrue(set.containsAll(NatSet.of(max)));
            assertTrue(set.intersects(NatSet.of(max)));
            assertEquals(0, NatSet.ORDER.compare(set, MutableNatSet.copyOf(set)));
            assertEquals(new TreeSet<>(set.boxed()).toString(), set.toString());
            assertEquals(set.boxed(), new HashSet<>(set.boxed()));
            assertEquals(set.size(), set.intStream().count());
        }
        assertEquals(Set.of(3, max), NatSet.of(max).union(NatSet.of(3)).boxed());
        assertTrue(mutable.boxed().remove(max));
        assertEquals(NatSet.of(3), mutable);
    }

    @Test
    void argumentsAreAsserted() {
        MutableNatSet set = MutableNatSet.create();
        assertThrows(AssertionError.class, () -> set.set(-1));
        assertThrows(AssertionError.class, () -> set.clear(-1));
        assertThrows(AssertionError.class, () -> set.set(3, 2));
        assertThrows(AssertionError.class, () -> set.nextSetBit(-1));
        assertThrows(AssertionError.class, () -> set.nextClearBit(-1));
        assertThrows(AssertionError.class, () -> set.previousSetBit(-2));
        assertThrows(AssertionError.class, () -> NatSet.of(4).nextSetBit(-1));
        assertFalse(set.contains(-1));
    }

    @Test
    void powerSetCountsThroughEverySubset() {
        for (NatSet basis :
                List.of(NatSet.of(), NatSet.range(0, 5), NatSet.range(3, 9), NatSet.of(1, 4, 70, 200, 201))) {
            Set<NatSet> subsets = new HashSet<>();
            Cursor<NatSet> cursor = NatSets.powerSet(basis);
            assertTrue(cursor.current().isEmpty());
            for (; cursor.valid(); cursor.advance()) {
                assertTrue(basis.containsAll(cursor.current()));
                assertTrue(subsets.add(NatSet.copyOf(cursor.current())));
            }
            assertEquals(1 << basis.size(), subsets.size());
            assertFalse(cursor.advance());
        }
    }

    @Test
    void hashCodesSpreadOverSmallSets() {
        // Set's hash code, the sum of the elements, would give 121 values here.
        Set<Integer> hashes = new HashSet<>();
        for (Cursor<NatSet> cursor = NatSets.powerSet(16); cursor.valid(); cursor.advance()) {
            hashes.add(cursor.current().hashCode());
            hashes.add(NatSet.copyOf(cursor.current()).hashCode());
        }
        assertTrue(hashes.size() > 65_000, String.valueOf(hashes.size()));
    }

    @Test
    void shiftsAgreeWithBitSet() {
        Random random = new Random(5);
        for (int span : new int[] {8, 64, 200, 3000}) {
            for (int round = 0; round < 200; round++) {
                BitSet reference = randomBitSet(random, span, random.nextInt(span < 64 ? 6 : 40));
                int last = reference.length() - 1;
                int[] amounts = {
                    0, 1, -1, 63, 64, 65, -63, -64, -65, random.nextInt(2 * span + 1) - span, -(last + 1), -(last + 2)
                };
                for (int amount : amounts) {
                    BitSet expected = new BitSet();
                    reference.stream().filter(e -> e + amount >= 0).forEach(e -> expected.set(e + amount));
                    NatSet set = natSet(random, reference);
                    assertSameContents(expected, set.shifted(amount));
                    assertSameContents(reference, set);
                    assertFalse(set.shifted(amount) instanceof MutableNatSet);
                    MutableNatSet mutable = MutableNatSet.copyOf(set);
                    mutable.shift(amount);
                    assertSameContents(expected, mutable);
                }
            }
        }
        assertSame(NatSet.of(3), NatSet.of(3).shifted(0));
        assertEquals(NatSet.of(), NatSet.of(1, 2).shifted(-3));
    }

    @Test
    void rangesAgreeWithBitSet() {
        Random random = new Random(6);
        for (int span : new int[] {8, 64, 200, 3000}) {
            for (int round = 0; round < 200; round++) {
                BitSet reference = randomBitSet(random, span, random.nextInt(span < 64 ? 6 : 40));
                int length = reference.length();
                int[][] ranges = {
                    {0, length},
                    {0, length + 70},
                    {0, 0},
                    {5, 5},
                    {7, 3},
                    {1, length},
                    {0, Math.max(0, length - 1)},
                    {63, 65},
                    {64, 128},
                    {60, 200},
                    {length, length + 3},
                    {random.nextInt(span + 1), random.nextInt(span + 1)},
                    {random.nextInt(span + 1), random.nextInt(2 * span + 1)}
                };
                for (int[] range : ranges) {
                    int from = range[0];
                    int to = range[1];
                    BitSet sliced = to <= from ? new BitSet() : reference.get(from, to);
                    BitSet restricted = new BitSet();
                    sliced.stream().forEach(e -> restricted.set(e + from));
                    NatSet set = natSet(random, reference);
                    assertSameContents(restricted, set.subSet(from, to));
                    assertSameContents(sliced, set.slice(from, to));
                    assertFalse(set.subSet(from, to) instanceof MutableNatSet);
                    assertFalse(set.slice(from, to) instanceof MutableNatSet);
                    assertSameContents(reference, set);
                    assertEquals(set.subSet(from, to).shifted(-from), set.slice(from, to));
                }
            }
        }
        NatSet immutable = NatSet.of(2, 5, 9);
        assertSame(immutable, immutable.subSet(0, 10));
        assertSame(immutable, immutable.subSet(2, 10));
        assertEquals(NatSet.of(5), immutable.subSet(3, 9));
        assertEquals(NatSet.of(2, 6), immutable.slice(3, 10));
    }

    @Test
    void rangesAndEndsWithDefaults() {
        for (int[] range : new int[][] {{0, 0}, {3, 3}, {5, 2}, {0, 1}, {0, 64}, {7, 70}, {63, 65}, {100, 300}}) {
            int from = range[0];
            int to = range[1];
            BitSet reference = new BitSet();
            if (from < to) {
                reference.set(from, to);
            }
            NatSet immutable = NatSet.range(from, to);
            MutableNatSet mutable = MutableNatSet.range(from, to);
            assertSameContents(reference, immutable);
            assertSameContents(reference, mutable);
            assertEquals(immutable, mutable);
            int expectedFirst = reference.isEmpty() ? -7 : from;
            int expectedLast = reference.isEmpty() ? -7 : to - 1;
            assertEquals(expectedFirst, immutable.firstOr(-7));
            assertEquals(expectedLast, immutable.lastOr(-7));
            assertEquals(expectedFirst, mutable.firstOr(-7));
            assertEquals(expectedLast, mutable.lastOr(-7));
            mutable.set(to + 10);
            assertEquals(to + 10, mutable.lastOr(-7));
        }
    }

    @Test
    void freezeAndClearTakesTheElementsOver() {
        Random random = new Random(7);
        for (int span : new int[] {8, 64, 200, 3000}) {
            for (int round = 0; round < 100; round++) {
                BitSet reference = randomBitSet(random, span, random.nextInt(span < 64 ? 6 : 40));
                MutableNatSet mutable = MutableNatSet.copyOf(reference);
                NatSet frozen = mutable.freezeAndClear();
                assertFalse(frozen instanceof MutableNatSet);
                assertSameContents(reference, frozen);
                assertTrue(mutable.isEmpty());
                assertEquals(NatSet.copyOf(reference), frozen);
                // The emptied set is usable again, and the frozen one does not follow it.
                mutable.set(3);
                mutable.set(span + 5);
                assertSameContents(reference, frozen);
                assertEquals(NatSet.of(3, span + 5), mutable);
            }
        }
    }
}
