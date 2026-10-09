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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.in.jbdd.TestProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link NatSet} and {@link MutableNatSet} against {@link BitSet}: random operation sequences on a mutable set and its
 * reference, every query compared after each step, the set re-read through each class and representation, and the
 * binary operations against sets of every shape. The spans keep a set in the array, move it to words or mix both; a
 * few elements come from the word boundaries. {@link NatSetTest} pins the contracts one by one, this walks their
 * combinations; the rounds scale with {@link TestProfile}.
 */
class NatSetFuzzTest {
    private static final int SEEDS = 3;
    private static final int ROUNDS = TestProfile.scaled(300, 20);
    private static final int[] SPANS = {70, 300, 5000};

    static Stream<Long> seeds() {
        return IntStream.range(0, SEEDS).mapToObj(seed -> (long) seed);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void testAgainstBitSet(long seed) {
        Random random = new Random(seed);
        for (int round = 0; round < ROUNDS; round++) {
            int span = SPANS[random.nextInt(SPANS.length)];
            MutableNatSet set =
                    random.nextBoolean() ? MutableNatSet.create() : MutableNatSet.dense(random.nextInt(200));
            BitSet reference = new BitSet();
            int steps = random.nextInt(60);
            for (int step = 0; step < steps; step++) {
                reference = mutate(random, span, set, reference);
                if (random.nextInt(4) == 0) {
                    assertSameElements(set, reference);
                }
            }
            assertSameElements(set, reference);
            assertQueriesAgree(random, span, set, reference);
            NatSet frozen = NatSet.copyOf(set);
            assertSameElements(frozen, reference);
            assertQueriesAgree(random, span, frozen, reference);

            BitSet otherReference = randomBits(random, span);
            NatSet other = natSet(random, otherReference);
            assertSameElements(other, otherReference);
            assertCombinationsAgree(set, reference, other, otherReference);
            assertCombinationsAgree(frozen, reference, other, otherReference);
            assertCombinationsAgree(other, otherReference, frozen, reference);
            assertCombinationsAgree(frozen, reference, frozen, reference);
            assertBoxedViewAgrees(random, span, set, reference);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void testLargestElement(long seed) {
        Random random = new Random(seed);
        int below = random.nextInt(100);
        for (NatSet set : List.of(
                NatSet.of(Integer.MAX_VALUE),
                NatSet.of(below, Integer.MAX_VALUE),
                MutableNatSet.of(below, Integer.MAX_VALUE))) {
            assertTrue(set.contains(Integer.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, set.last());
            assertEquals(Integer.MAX_VALUE, set.nextSetBit(Integer.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, set.previousSetBit(Integer.MAX_VALUE));
            assertEquals(set.size() - 1, set.rank(Integer.MAX_VALUE));
            assertEquals(set.size(), set.intStream().count());
            assertTrue(set.anyMatch(element -> element == Integer.MAX_VALUE));
            assertThrows(AssertionError.class, set::length);
        }
    }

    // The mutation step: one of BitSet's mutators on both, returning the (possibly replaced) reference.

    private static BitSet mutate(Random random, int span, MutableNatSet set, BitSet reference) {
        int first = element(random, span);
        int second = element(random, span);
        int from = Math.min(first, second);
        int to = Math.max(first, second);
        switch (random.nextInt(16)) {
            case 0:
                set.set(first);
                reference.set(first);
                return reference;
            case 1:
                set.clear(first);
                reference.clear(first);
                return reference;
            case 2:
                set.flip(first);
                reference.flip(first);
                return reference;
            case 3:
                set.set(from, to);
                reference.set(from, to);
                return reference;
            case 4:
                set.clear(from, to);
                reference.clear(from, to);
                return reference;
            case 5:
                set.flip(from, to);
                reference.flip(from, to);
                return reference;
            case 6: {
                boolean value = random.nextBoolean();
                set.set(from, to, value);
                reference.set(from, to, value);
                return reference;
            }
            case 7: {
                BitSet operand = randomBits(random, span);
                set.or(natSet(random, operand));
                reference.or(operand);
                return reference;
            }
            case 8: {
                BitSet operand = randomBits(random, span);
                set.and(natSet(random, operand));
                reference.and(operand);
                return reference;
            }
            case 9: {
                BitSet operand = randomBits(random, span);
                set.andNot(natSet(random, operand));
                reference.andNot(operand);
                return reference;
            }
            case 10: {
                BitSet operand = randomBits(random, span);
                set.xor(natSet(random, operand));
                reference.xor(operand);
                return reference;
            }
            case 11:
                set.optimize();
                return reference;
            case 12: {
                int modulus = 2 + random.nextInt(5);
                int remainder = random.nextInt(modulus);
                BitSet expected = (BitSet) reference.clone();
                reference.stream()
                        .filter(element -> element % modulus == remainder)
                        .forEach(expected::clear);
                boolean removed = set.removeIf(element -> element % modulus == remainder);
                assertEquals(!expected.equals(reference), removed);
                return expected;
            }
            case 13: {
                int amount = random.nextInt(200) - 100;
                set.shift(amount);
                return shifted(reference, amount);
            }
            case 14: {
                NatSet frozen = set.freezeAndClear();
                assertSameElements(frozen, reference);
                assertSameElements(set, new BitSet());
                set.or(frozen);
                return reference;
            }
            default:
                set.set(first, random.nextBoolean());
                reference.set(first, set.contains(first));
                return reference;
        }
    }

    // Reading the set as a whole, and equality with a copy through each class and representation.

    private static void assertSameElements(NatSet set, BitSet reference) {
        int[] expected = reference.stream().toArray();
        assertEquals(expected.length, set.size());
        assertEquals(reference.isEmpty(), set.isEmpty());
        assertEquals(reference.nextSetBit(0), set.first());
        assertEquals(reference.isEmpty() ? -1 : reference.length() - 1, set.last());
        assertEquals(reference.length(), set.length());
        assertArrayEquals(expected, set.toIntArray());
        assertArrayEquals(expected, set.intStream().toArray());
        assertArrayEquals(reference.toLongArray(), set.toLongArray());
        assertEquals(reference, set.toBitSet());
        assertEquals(new TreeSet<>(boxed(reference)).toString(), set.toString());

        int[] iterated = new int[expected.length];
        int count = 0;
        PrimitiveIterator.OfInt iterator = set.iterator();
        while (iterator.hasNext()) {
            iterated[count] = iterator.nextInt();
            count += 1;
        }
        assertEquals(expected.length, count);
        assertArrayEquals(expected, iterated);
        int[] visited = new int[expected.length];
        int[] visitedCount = {0};
        set.forEach(element -> {
            visited[visitedCount[0]] = element;
            visitedCount[0] += 1;
        });
        assertEquals(expected.length, visitedCount[0]);
        assertArrayEquals(expected, visited);

        for (NatSet copy : copies(reference)) {
            assertEquals(copy, set);
            assertEquals(set, copy);
            assertEquals(copy.hashCode(), set.hashCode());
            assertEquals(0, NatSet.ORDER.compare(copy, set));
            assertTrue(copy.containsAll(set) && set.containsAll(copy));
            assertEquals(!reference.isEmpty(), set.intersects(copy));
        }
    }

    private static List<NatSet> copies(BitSet reference) {
        int[] elements = reference.stream().toArray();
        MutableNatSet dense = MutableNatSet.dense(reference.length() + 70);
        reference.stream().forEach(dense::set);
        long[] words = reference.toLongArray();
        return List.of(
                NatSet.copyOf(reference),
                MutableNatSet.copyOf(reference),
                NatSet.of(elements),
                MutableNatSet.of(elements),
                dense,
                NatSet.valueOf(words),
                MutableNatSet.valueOf(Arrays.copyOf(words, words.length + 3)),
                NatSet.copyOf(boxed(reference)),
                MutableNatSet.copyOf(boxed(reference)));
    }

    // Element queries at random positions, and the derived sets.

    private static void assertQueriesAgree(Random random, int span, NatSet set, BitSet reference) {
        for (int query = 0; query < 20; query++) {
            int element = element(random, span + 100);
            assertEquals(reference.get(element), set.contains(element));
            assertEquals(reference.nextSetBit(element), set.nextSetBit(element));
            assertEquals(reference.previousSetBit(element), set.previousSetBit(element));
            assertEquals(reference.nextClearBit(element), set.nextClearBit(element));
            assertEquals(reference.get(0, element).cardinality(), set.rank(element));

            int bound = element(random, span + 100);
            int from = Math.min(element, bound);
            int to = Math.max(element, bound) + random.nextInt(3) - 1;
            BitSet subSet = (BitSet) reference.clone();
            if (to > from) {
                subSet.clear(to, Integer.MAX_VALUE);
                subSet.clear(0, from);
            } else {
                subSet.clear();
            }
            assertSameElements(set.subSet(from, to), subSet);
            assertSameElements(set.slice(from, to), to > from ? reference.get(from, to) : new BitSet());

            int amount = random.nextInt(300) - 150;
            BitSet shifted = shifted(reference, amount);
            assertSameElements(set.shifted(amount), shifted);
            MutableNatSet shiftedInPlace = MutableNatSet.copyOf(set);
            shiftedInPlace.shift(amount);
            assertSameElements(shiftedInPlace, shifted);
        }
        assertEquals(-1, set.previousSetBit(-1));
        assertEquals(0, set.rank(-5));
        assertEquals(reference.cardinality(), set.rank(Integer.MAX_VALUE));
        assertEquals(
                reference.stream().anyMatch(element -> element % 3 == 0), set.anyMatch(element -> element % 3 == 0));
        assertEquals(
                reference.stream().allMatch(element -> element % 3 == 0), set.allMatch(element -> element % 3 == 0));
        assertEquals(
                reference.stream().noneMatch(element -> element % 3 == 0), set.noneMatch(element -> element % 3 == 0));
        assertEquals(reference.isEmpty() ? -7 : reference.nextSetBit(0), set.firstOr(-7));
        assertEquals(reference.isEmpty() ? -7 : reference.length() - 1, set.lastOr(-7));
    }

    // The combinations of two sets, through NatSet, NatSets and MutableNatSet.

    private static void assertCombinationsAgree(
            NatSet first, BitSet firstReference, NatSet second, BitSet secondReference) {
        BitSet union = (BitSet) firstReference.clone();
        union.or(secondReference);
        BitSet intersection = (BitSet) firstReference.clone();
        intersection.and(secondReference);
        BitSet difference = (BitSet) firstReference.clone();
        difference.andNot(secondReference);
        BitSet symmetricDifference = (BitSet) firstReference.clone();
        symmetricDifference.xor(secondReference);

        assertSameElements(first.union(second), union);
        assertSameElements(first.intersection(second), intersection);
        assertSameElements(first.difference(second), difference);
        assertEquals(intersection.equals(secondReference), first.containsAll(second));
        assertEquals(!intersection.isEmpty(), first.intersects(second));
        assertEquals(firstReference.equals(secondReference), first.equals(second));
        if (firstReference.equals(secondReference)) {
            assertEquals(first.hashCode(), second.hashCode());
        }
        int expectedOrder = Integer.compare(firstReference.cardinality(), secondReference.cardinality());
        if (expectedOrder == 0) {
            expectedOrder = Arrays.compare(
                    firstReference.stream().toArray(), secondReference.stream().toArray());
        }
        assertEquals(Integer.signum(expectedOrder), Integer.signum(NatSet.ORDER.compare(first, second)));

        assertSameElements(NatSets.union(first, second), union);
        assertSameElements(NatSets.intersection(first, second), intersection);
        assertSameElements(NatSets.without(first, second), difference);
        assertSameElements(NatSets.union(List.of(first, second, first)), union);
        assertSameElements(NatSets.lazyUnion(List.of(first, NatSet.of(), second)), union);
        assertSameElements(NatSets.intersection(List.of(first, second)), intersection);
        MutableNatSet target = MutableNatSet.of(1, 2, 3);
        NatSets.difference(target, first, second);
        assertSameElements(target, difference);

        MutableNatSet orInPlace = MutableNatSet.copyOf(first);
        orInPlace.or(second);
        assertSameElements(orInPlace, union);
        MutableNatSet andInPlace = MutableNatSet.copyOf(first);
        andInPlace.and(second);
        assertSameElements(andInPlace, intersection);
        MutableNatSet andNotInPlace = MutableNatSet.copyOf(first);
        andNotInPlace.andNot(second);
        assertSameElements(andNotInPlace, difference);
        MutableNatSet xorInPlace = MutableNatSet.copyOf(first);
        xorInPlace.xor(second);
        assertSameElements(xorInPlace, symmetricDifference);
    }

    // The boxed view's mutators change the set underneath; an immutable set's view refuses them.

    private static void assertBoxedViewAgrees(Random random, int span, MutableNatSet set, BitSet reference) {
        Set<Integer> boxed = set.boxed();
        Set<Integer> expected = boxed(reference);
        int element = element(random, span);
        assertEquals(expected.add(element), boxed.add(element));
        assertEquals(expected.remove(element), boxed.remove(element));
        assertEquals(expected, boxed);
        List<Integer> elements = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            elements.add(element(random, span));
        }
        assertEquals(expected.removeAll(elements), boxed.removeAll(elements));
        assertEquals(expected, boxed);
        assertEquals(expected.retainAll(elements), boxed.retainAll(elements));
        assertEquals(expected, boxed);
        assertEquals(expected.hashCode(), boxed.hashCode());
        reference.clear();
        expected.forEach(reference::set);
        assertThrows(
                UnsupportedOperationException.class,
                () -> NatSet.copyOf(set).boxed().add(3));
    }

    // Shapes

    private static int element(Random random, int span) {
        switch (random.nextInt(10)) {
            case 0:
                return random.nextInt(4);
            case 1:
                return 62 + random.nextInt(4);
            case 2:
                return 126 + random.nextInt(4);
            default:
                return random.nextInt(span);
        }
    }

    private static BitSet randomBits(Random random, int span) {
        BitSet bits = new BitSet();
        int count = random.nextInt(40);
        int shape = random.nextInt(4);
        for (int index = 0; index < count; index++) {
            bits.set(shape == 0 ? random.nextInt(16) : shape == 1 ? random.nextInt(64) : element(random, span));
        }
        if (random.nextInt(30) == 0) {
            int from = random.nextInt(span);
            bits.set(from, from + random.nextInt(200));
        }
        return bits;
    }

    /** A set with the elements of {@code reference}, of either class, built in one of the ways there are. */
    private static NatSet natSet(Random random, BitSet reference) {
        switch (random.nextInt(4)) {
            case 0:
                return NatSet.copyOf(reference);
            case 1:
                return MutableNatSet.copyOf(reference);
            case 2:
                return NatSet.of(reference.stream().toArray());
            default: {
                MutableNatSet set = MutableNatSet.create();
                reference.stream().forEach(set::set);
                if (random.nextBoolean()) {
                    set.optimize();
                }
                return set;
            }
        }
    }

    private static BitSet shifted(BitSet reference, int amount) {
        BitSet shifted = new BitSet();
        reference.stream().filter(element -> element + amount >= 0).forEach(element -> shifted.set(element + amount));
        return shifted;
    }

    private static Set<Integer> boxed(BitSet reference) {
        Set<Integer> set = new TreeSet<>();
        reference.stream().forEach(set::add);
        return set;
    }
}
