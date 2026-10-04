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
import java.util.BitSet;
import java.util.Collection;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * A {@link NatSet} that never changes: what {@link NatSet}'s factories and operations return. Held as an exact
 * ascending array or as words without trailing zero words, whichever takes less memory (see
 * {@link NatSetUtil#useWords}), so the representation follows from the elements and equal sets hold equal stores; the
 * empty set and those of one element below {@link #CACHE_LIMIT} are shared. The hash code is computed once.
 */
final class ImmutableNatSet implements NatSet {
    private static final int WORD_SHIFT = NatSetUtil.WORD_SHIFT;
    private static final int CACHE_LIMIT = 128;
    private static final int[] NO_ELEMENTS = new int[0];
    static final ImmutableNatSet EMPTY = new ImmutableNatSet(NO_ELEMENTS);
    private static final ImmutableNatSet[] SINGLETONS = new ImmutableNatSet[CACHE_LIMIT];

    static {
        for (int element = 0; element < CACHE_LIMIT; element++) {
            SINGLETONS[element] = newSingleton(element);
        }
    }

    // The elements, ascending and exactly sized, as an int[], or words as a long[]: one field for both keeps the
    // object at 24 bytes rather than 32 (header, one reference, size, hash).
    private final Object store;
    private final int size;
    private final int hash;

    private ImmutableNatSet(int[] elements) {
        this.store = elements;
        this.size = elements.length;
        this.hash = NatSetUtil.arrayHash(elements, elements.length);
    }

    private ImmutableNatSet(long[] words, int size) {
        assert size == NatSetUtil.wordsCount(words);
        this.store = words;
        this.size = size;
        this.hash = NatSetUtil.wordsHash(words);
    }

    /** The elements, if held as an array. */
    int @Nullable [] elements() {
        Object current = store;
        return current instanceof int[] ? (int[]) current : null;
    }

    /** The words, if held as words. */
    long @Nullable [] words() {
        Object current = store;
        return current instanceof long[] ? (long[]) current : null;
    }

    // Factories

    static ImmutableNatSet singleton(int element) {
        assert element >= 0 : "Negative element " + element;
        return element < CACHE_LIMIT ? SINGLETONS[element] : newSingleton(element);
    }

    private static ImmutableNatSet newSingleton(int element) {
        if (NatSetUtil.useWords(1, element)) {
            long[] words = new long[NatSetUtil.wordCount(element + 1)];
            words[element >>> WORD_SHIFT] = 1L << element;
            return new ImmutableNatSet(words, 1);
        }
        return new ImmutableNatSet(new int[] {element});
    }

    /** The set of the first {@code count} entries of {@code sorted}, ascending and distinct, which it may keep. */
    private static ImmutableNatSet ofSorted(int[] sorted, int count) {
        if (count <= 1) {
            return count == 0 ? EMPTY : singleton(sorted[0]);
        }
        int max = sorted[count - 1];
        if (NatSetUtil.useWords(count, max)) {
            long[] words = new long[NatSetUtil.wordCount(max + 1)];
            for (int index = 0; index < count; index++) {
                words[sorted[index] >>> WORD_SHIFT] |= 1L << sorted[index];
            }
            return new ImmutableNatSet(words, count);
        }
        return new ImmutableNatSet(count == sorted.length ? sorted : Arrays.copyOf(sorted, count));
    }

    /** The set of {@code words}, of which it keeps a trimmed copy or, if {@code own}, the array itself. */
    private static ImmutableNatSet ofWords(long[] words, int count, boolean own) {
        if (count <= 1) {
            return count == 0 ? EMPTY : singleton(NatSetUtil.wordsNext(words, 0));
        }
        int length = NatSetUtil.wordsLength(words);
        if (!NatSetUtil.useWords(count, length - 1)) {
            return new ImmutableNatSet(NatSetUtil.wordsToArray(words, count));
        }
        int wordCount = NatSetUtil.wordCount(length);
        return new ImmutableNatSet(own && wordCount == words.length ? words : Arrays.copyOf(words, wordCount), count);
    }

    static ImmutableNatSet of(int... elements) {
        int[] sorted = elements.clone();
        Arrays.sort(sorted);
        int count = 0;
        for (int element : sorted) {
            assert element >= 0 : "Negative element " + element;
            if (count == 0 || sorted[count - 1] != element) {
                sorted[count] = element;
                count += 1;
            }
        }
        return ofSorted(sorted, count);
    }

    static ImmutableNatSet range(int from, int to) {
        assert from >= 0 : "Negative element " + from;
        if (to - from <= 1) {
            return to <= from ? EMPTY : singleton(from);
        }
        int count = to - from;
        if (NatSetUtil.useWords(count, to - 1)) {
            MutableNatSetImpl set = MutableNatSetImpl.dense(to);
            set.set(from, to);
            return freeze(set);
        }
        int[] elements = new int[count];
        Arrays.setAll(elements, index -> from + index);
        return new ImmutableNatSet(elements);
    }

    static ImmutableNatSet copyOf(NatSet set) {
        if (set instanceof ImmutableNatSet) {
            return (ImmutableNatSet) set;
        }
        MutableNatSetImpl mutable = (MutableNatSetImpl) set;
        long[] mutableWords = mutable.words;
        if (mutableWords == null) {
            return ofSorted(Arrays.copyOf(mutable.elements, mutable.size()), mutable.size());
        }
        return ofWords(mutableWords, mutable.size(), false);
    }

    /** {@code set} as an immutable set, taking over its stores: {@code set} is not used afterwards. */
    static ImmutableNatSet freeze(MutableNatSetImpl set) {
        long[] setWords = set.words;
        return setWords == null ? ofSorted(set.elements, set.size()) : ofWords(setWords, set.size(), true);
    }

    static ImmutableNatSet valueOf(long... words) {
        return ofWords(words, NatSetUtil.wordsCount(words), false);
    }

    static ImmutableNatSet copyOf(BitSet set) {
        long[] words = set.toLongArray();
        return ofWords(words, set.cardinality(), true);
    }

    static ImmutableNatSet copyOf(Collection<Integer> elements) {
        if (elements instanceof BoxedNatSet) {
            return copyOf(((BoxedNatSet) elements).set());
        }
        int[] array = new int[elements.size()];
        int index = 0;
        for (int element : elements) {
            array[index] = element;
            index += 1;
        }
        return of(index == array.length ? array : Arrays.copyOf(array, index));
    }

    // Queries

    @Override
    public boolean contains(int element) {
        if (element < 0) {
            return false;
        }
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayContains(array, size, element);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsContain(current, element);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsAll(NatSet other) {
        return NatSetUtil.containsAll(this, other);
    }

    @Override
    public boolean intersects(NatSet other) {
        return NatSetUtil.intersects(this, other);
    }

    @Override
    public int first() {
        if (size == 0) {
            return -1;
        }
        int[] array = elements();
        return array == null ? nextSetBit(0) : array[0];
    }

    @Override
    public int last() {
        return length() - 1;
    }

    @Override
    public int nextSetBit(int from) {
        assert from >= 0 : from;
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayNext(array, size, from);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsNext(current, from);
    }

    @Override
    public int previousSetBit(int from) {
        assert from >= -1 : from;
        if (from < 0) {
            return -1;
        }
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayPrevious(array, size, from);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsPrevious(current, from);
    }

    @Override
    public int nextClearBit(int from) {
        assert from >= 0 : from;
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayNextClear(array, size, from);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsNextClear(current, from);
    }

    @Override
    public int length() {
        int[] array = elements();
        if (array != null) {
            return size == 0 ? 0 : array[size - 1] + 1;
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsLength(current);
    }

    // Iteration

    @Override
    public void forEach(IntConsumer action) {
        int[] array = elements();
        if (array != null) {
            for (int element : array) {
                action.accept(element);
            }
            return;
        }
        long[] current = words();
        assert current != null;
        NatSetUtil.wordsForEach(current, action);
    }

    @Override
    public PrimitiveIterator.OfInt iterator() {
        int[] array = elements();
        if (array != null) {
            return new NatSetUtil.ArrayIterator(array, size);
        }
        long[] current = words();
        assert current != null;
        return new NatSetUtil.WordsIterator(current);
    }

    @Override
    public boolean anyMatch(IntPredicate predicate) {
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayAnyMatch(array, size, predicate);
        }
        long[] current = words();
        assert current != null;
        return !NatSetUtil.wordsWhile(current, element -> !predicate.test(element));
    }

    @Override
    public boolean allMatch(IntPredicate predicate) {
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayAllMatch(array, size, predicate);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsWhile(current, predicate);
    }

    @Override
    public IntStream intStream() {
        int[] array = elements();
        if (array != null) {
            return Arrays.stream(array);
        }
        int characteristics = Spliterator.ORDERED
                | Spliterator.SORTED
                | Spliterator.DISTINCT
                | Spliterator.SIZED
                | Spliterator.NONNULL
                | Spliterator.IMMUTABLE;
        return StreamSupport.intStream(Spliterators.spliterator(iterator(), size, characteristics), false);
    }

    @Override
    public int[] toIntArray() {
        int[] array = elements();
        if (array != null) {
            return array.clone();
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.wordsToArray(current, size);
    }

    // Combination: an operand that is the result is returned as it is, never changing.

    // Two operands of one representation are combined into the result's store directly, mixed ones through a copy.

    @Override
    public NatSet union(NatSet other) {
        if (containsAll(other)) {
            return this;
        }
        if (other instanceof ImmutableNatSet && other.containsAll(this)) {
            return other;
        }
        long[] current = words();
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (current != null && otherWords != null) {
            boolean longer = current.length >= otherWords.length;
            long[] union = (longer ? current : otherWords).clone();
            long[] shorter = longer ? otherWords : current;
            for (int index = 0; index < shorter.length; index++) {
                union[index] |= shorter[index];
            }
            return ofWords(union, NatSetUtil.wordsCount(union), true);
        }
        int[] array = elements();
        int[] otherArray = NatSetUtil.elementsOf(other);
        if (array != null && otherArray != null) {
            int[] union = new int[size + other.size()];
            return ofSorted(union, NatSetUtil.arrayUnion(array, size, otherArray, other.size(), union));
        }
        MutableNatSetImpl union = MutableNatSetImpl.copyOf(this);
        union.or(other);
        return freeze(union);
    }

    @Override
    public NatSet intersection(NatSet other) {
        if (other.containsAll(this)) {
            return this;
        }
        if (other instanceof ImmutableNatSet && containsAll(other)) {
            return other;
        }
        long[] current = words();
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (current != null && otherWords != null) {
            long[] intersection = new long[Math.min(current.length, otherWords.length)];
            for (int index = 0; index < intersection.length; index++) {
                intersection[index] = current[index] & otherWords[index];
            }
            return ofWords(intersection, NatSetUtil.wordsCount(intersection), true);
        }
        int[] array = elements();
        int[] otherArray = NatSetUtil.elementsOf(other);
        if (array != null && otherArray != null) {
            int[] intersection = new int[Math.min(size, other.size())];
            return ofSorted(
                    intersection, NatSetUtil.arrayIntersection(array, size, otherArray, other.size(), intersection));
        }
        MutableNatSetImpl intersection = MutableNatSetImpl.copyOf(this);
        intersection.and(other);
        return freeze(intersection);
    }

    @Override
    public NatSet difference(NatSet other) {
        if (!intersects(other)) {
            return this;
        }
        long[] current = words();
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (current != null && otherWords != null) {
            long[] difference = current.clone();
            int common = Math.min(current.length, otherWords.length);
            for (int index = 0; index < common; index++) {
                difference[index] &= ~otherWords[index];
            }
            return ofWords(difference, NatSetUtil.wordsCount(difference), true);
        }
        int[] array = elements();
        int[] otherArray = NatSetUtil.elementsOf(other);
        if (array != null && otherArray != null) {
            int[] difference = new int[size];
            return ofSorted(difference, NatSetUtil.arrayDifference(array, size, otherArray, other.size(), difference));
        }
        MutableNatSetImpl difference = MutableNatSetImpl.copyOf(this);
        difference.andNot(other);
        return freeze(difference);
    }

    @Override
    public NatSet shifted(int amount) {
        assert amount > Integer.MIN_VALUE && (amount <= 0 || size == 0 || last() <= Integer.MAX_VALUE - amount)
                : amount;
        if (amount == 0 || size == 0) {
            return this;
        }
        int[] array = elements();
        if (array != null) {
            int first = NatSetUtil.arrayFirstKept(array, size, amount);
            int[] shifted = new int[size - first];
            for (int index = first; index < size; index++) {
                shifted[index - first] = array[index] + amount;
            }
            return ofSorted(shifted, shifted.length);
        }
        long[] current = words();
        assert current != null;
        long[] shifted = NatSetUtil.shiftedWords(current, amount);
        return ofWords(shifted, amount > 0 ? size : NatSetUtil.wordsCount(shifted), true);
    }

    // Views, copies, bridges

    @Override
    public Set<Integer> boxed() {
        return new BoxedNatSet(this);
    }

    @Override
    public long[] toLongArray() {
        int[] array = elements();
        if (array != null) {
            return NatSetUtil.arrayToWords(array, size);
        }
        long[] current = words();
        assert current != null;
        return NatSetUtil.trimmedWords(current);
    }

    @Override
    public BitSet toBitSet() {
        long[] current = words();
        return current == null ? copyInto(new BitSet()) : BitSet.valueOf(current);
    }

    @Override
    public BitSet copyInto(BitSet target) {
        int[] array = elements();
        if (array != null) {
            for (int element : array) {
                target.set(element);
            }
            return target;
        }
        long[] current = words();
        assert current != null;
        target.or(BitSet.valueOf(current));
        return target;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ImmutableNatSet)) {
            return NatSetUtil.setEquals(this, o);
        }
        // Equal sets hold equal stores: the representation follows from the elements, and both are exact.
        ImmutableNatSet other = (ImmutableNatSet) o;
        Object otherStore = other.store;
        boolean equal = size == other.size
                && hash == other.hash
                && (store instanceof long[]
                        ? otherStore instanceof long[] && Arrays.equals((long[]) store, (long[]) otherStore)
                        : otherStore instanceof int[] && Arrays.equals((int[]) store, (int[]) otherStore));
        assert equal == NatSetUtil.setEquals(this, o) : this + " " + o;
        return equal;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return NatSetUtil.toString(this);
    }
}
