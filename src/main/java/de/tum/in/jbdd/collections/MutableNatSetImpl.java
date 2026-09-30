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

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * The {@link MutableNatSet}: at most {@link NatSetUtil#MAX_ARRAY_SIZE} elements as a sorted {@code int[]} where
 * that is smaller than words over their span, words otherwise. An insertion may move the set from the array to
 * words; nothing moves it back but {@link #optimize()}, so removing never changes the representation.
 */
// The concrete types are the point: an operand of this class or the immutable one is where the fast paths are.
@SuppressWarnings({"PMD.LooseCoupling", "ObjectEquality", "VariableNotUsedInsideIf"})
final class MutableNatSetImpl extends AbstractSet<Integer> implements MutableNatSet {
    private static final int WORD_SHIFT = NatSetUtil.WORD_SHIFT;
    private static final int MAX_ARRAY_SIZE = NatSetUtil.MAX_ARRAY_SIZE;
    private static final int[] NO_ELEMENTS = new int[0];

    // While words is null, the set is the first size entries of elements, ascending; otherwise it is words.
    int[] elements = NO_ELEMENTS;
    long @Nullable [] words;
    private int size = 0;

    MutableNatSetImpl() {
        // Empty, as an array.
    }

    private MutableNatSetImpl(MutableNatSetImpl other) {
        long[] otherWords = other.words;
        if (otherWords == null) {
            elements = other.size == 0 ? NO_ELEMENTS : Arrays.copyOf(other.elements, other.size);
        } else {
            words = Arrays.copyOf(otherWords, NatSetUtil.wordCount(other.length()));
        }
        size = other.size;
    }

    static MutableNatSetImpl dense(int capacity) {
        assert capacity >= 0 : "Negative capacity " + capacity;
        MutableNatSetImpl set = new MutableNatSetImpl();
        set.words = new long[NatSetUtil.wordCount(capacity)];
        return set;
    }

    static MutableNatSetImpl of(int... elements) {
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
        MutableNatSetImpl set = new MutableNatSetImpl();
        if (count > 0 && NatSetUtil.useWords(count, sorted[count - 1])) {
            long[] setWords = new long[NatSetUtil.wordCount(sorted[count - 1] + 1)];
            for (int index = 0; index < count; index++) {
                setWords[sorted[index] >>> WORD_SHIFT] |= 1L << sorted[index];
            }
            set.words = setWords;
        } else if (count > 0) {
            set.elements = count == sorted.length ? sorted : Arrays.copyOf(sorted, count);
        }
        set.size = count;
        return set;
    }

    static MutableNatSetImpl copyOf(NatSet set) {
        if (set instanceof MutableNatSetImpl) {
            return new MutableNatSetImpl((MutableNatSetImpl) set);
        }
        ImmutableNatSet immutable = (ImmutableNatSet) set;
        MutableNatSetImpl copy = new MutableNatSetImpl();
        int[] immutableElements = immutable.elements();
        long[] immutableWords = immutable.words();
        if (immutableWords == null) {
            assert immutableElements != null;
            copy.elements = immutableElements.clone();
        } else {
            copy.words = immutableWords.clone();
        }
        copy.size = immutable.size();
        return copy;
    }

    static MutableNatSetImpl copyOf(BitSet set) {
        MutableNatSetImpl copy = new MutableNatSetImpl();
        int count = set.cardinality();
        if (count == 0) {
            return copy;
        }
        if (NatSetUtil.useWords(count, set.length() - 1)) {
            copy.words = set.toLongArray();
        } else {
            copy.elements = set.stream().toArray();
        }
        copy.size = count;
        return copy;
    }

    static MutableNatSetImpl copyOf(Collection<Integer> elements) {
        if (elements instanceof NatSet) {
            return copyOf((NatSet) elements);
        }
        int[] array = new int[elements.size()];
        int index = 0;
        for (int element : elements) {
            array[index] = element;
            index += 1;
        }
        return of(index == array.length ? array : Arrays.copyOf(array, index));
    }

    // Representation

    private long[] switchToWords(int max) {
        assert words == null;
        long[] setWords = new long[NatSetUtil.wordCount(max + 1)];
        for (int index = 0; index < size; index++) {
            int element = elements[index];
            setWords[element >>> WORD_SHIFT] |= 1L << element;
        }
        words = setWords;
        elements = NO_ELEMENTS;
        return setWords;
    }

    private long[] growWords(long[] current, int wordCount) {
        long[] grown = Arrays.copyOf(current, Math.max(wordCount, 2 * current.length));
        words = grown;
        return grown;
    }

    private void recount(long[] current) {
        size = NatSetUtil.wordsCount(current);
    }

    // The index of the first array element at least element, size if there is none.
    private int indexAtLeast(int element) {
        int index = 0;
        while (index < size && elements[index] < element) {
            index += 1;
        }
        return index;
    }

    @Override
    public void optimize() {
        long[] current = words;
        if (current == null) {
            if (elements.length > size) {
                elements = size == 0 ? NO_ELEMENTS : Arrays.copyOf(elements, size);
            }
            return;
        }
        int length = length();
        if (NatSetUtil.useWords(size, length - 1)) {
            if (NatSetUtil.wordCount(length) < current.length) {
                words = Arrays.copyOf(current, NatSetUtil.wordCount(length));
            }
        } else {
            elements = toIntArray();
            words = null;
        }
    }

    // Queries

    @Override
    public boolean contains(int element) {
        if (element < 0) {
            return false;
        }
        long[] current = words;
        return current == null
                ? NatSetUtil.arrayContains(elements, size, element)
                : NatSetUtil.wordsContain(current, element);
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof Integer && contains((int) (Integer) o);
    }

    @Override
    public boolean get(int index) {
        NatSetUtil.checkIndex(index);
        return contains(index);
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
        return words == null ? elements[0] : nextSetBit(0);
    }

    @Override
    public int last() {
        return length() - 1;
    }

    @Override
    public int nextSetBit(int from) {
        NatSetUtil.checkIndex(from);
        long[] current = words;
        return current == null ? NatSetUtil.arrayNext(elements, size, from) : NatSetUtil.wordsNext(current, from);
    }

    @Override
    public int previousSetBit(int from) {
        NatSetUtil.checkPrevious(from);
        if (from < 0) {
            return -1;
        }
        long[] current = words;
        return current == null
                ? NatSetUtil.arrayPrevious(elements, size, from)
                : NatSetUtil.wordsPrevious(current, from);
    }

    @Override
    public int nextClearBit(int from) {
        NatSetUtil.checkIndex(from);
        long[] current = words;
        return current == null
                ? NatSetUtil.arrayNextClear(elements, size, from)
                : NatSetUtil.wordsNextClear(current, from);
    }

    @Override
    public int length() {
        long[] current = words;
        if (current == null) {
            return size == 0 ? 0 : elements[size - 1] + 1;
        }
        return NatSetUtil.wordsLength(current);
    }

    // Iteration

    @Override
    public void forEach(IntConsumer action) {
        long[] current = words;
        if (current == null) {
            for (int index = 0; index < size; index++) {
                action.accept(elements[index]);
            }
            return;
        }
        for (int index = 0; index < current.length; index++) {
            long word = current[index];
            while (word != 0) {
                action.accept((index << WORD_SHIFT) + Long.numberOfTrailingZeros(word));
                word &= word - 1;
            }
        }
    }

    @Override
    public void forEach(Consumer<? super Integer> action) {
        forEach((IntConsumer) action::accept);
    }

    @Override
    public PrimitiveIterator.OfInt iterator() {
        return new PrimitiveIterator.OfInt() {
            private int next = nextSetBit(0);
            private int current = -1;

            @Override
            public boolean hasNext() {
                return next >= 0;
            }

            @Override
            public int nextInt() {
                if (next < 0) {
                    throw new NoSuchElementException();
                }
                current = next;
                next = nextSetBit(current + 1);
                return current;
            }

            @Override
            public void remove() {
                if (current < 0) {
                    throw new IllegalStateException();
                }
                clear(current);
                current = -1;
            }
        };
    }

    @Override
    public IntStream intStream() {
        if (words == null) {
            return Arrays.stream(elements, 0, size);
        }
        int characteristics = Spliterator.ORDERED
                | Spliterator.SORTED
                | Spliterator.DISTINCT
                | Spliterator.SIZED
                | Spliterator.NONNULL;
        return StreamSupport.intStream(Spliterators.spliterator(iterator(), size, characteristics), false);
    }

    @Override
    public int[] toIntArray() {
        long[] current = words;
        return current == null ? Arrays.copyOf(elements, size) : NatSetUtil.wordsToArray(current, size);
    }

    // Single elements

    @Override
    public void set(int index) {
        NatSetUtil.checkIndex(index);
        long[] current = words;
        if (current == null) {
            int position = indexAtLeast(index);
            if (position < size && elements[position] == index) {
                return;
            }
            int max = position == size ? index : elements[size - 1];
            if (!NatSetUtil.useWords(size + 1, max)) {
                insertAt(position, index);
                return;
            }
            current = switchToWords(max);
        }
        int wordIndex = index >>> WORD_SHIFT;
        long[] target = wordIndex < current.length ? current : growWords(current, wordIndex + 1);
        long old = target[wordIndex];
        long updated = old | (1L << index);
        if (updated != old) {
            target[wordIndex] = updated;
            size += 1;
        }
    }

    private void insertAt(int position, int element) {
        if (size == elements.length) {
            int capacity = elements.length == 0 ? 4 : Math.min(MAX_ARRAY_SIZE, 2 * elements.length);
            elements = Arrays.copyOf(elements, capacity);
        }
        System.arraycopy(elements, position, elements, position + 1, size - position);
        elements[position] = element;
        size += 1;
    }

    @Override
    public void set(int index, boolean value) {
        if (value) {
            set(index);
        } else {
            clear(index);
        }
    }

    @Override
    public void clear(int index) {
        NatSetUtil.checkIndex(index);
        long[] current = words;
        if (current == null) {
            int position = indexAtLeast(index);
            if (position < size && elements[position] == index) {
                System.arraycopy(elements, position + 1, elements, position, size - position - 1);
                size -= 1;
            }
            return;
        }
        int wordIndex = index >>> WORD_SHIFT;
        if (wordIndex < current.length) {
            long old = current[wordIndex];
            long updated = old & ~(1L << index);
            if (updated != old) {
                current[wordIndex] = updated;
                size -= 1;
            }
        }
    }

    @Override
    public void flip(int index) {
        if (contains(index)) {
            clear(index);
        } else {
            set(index);
        }
    }

    // Ranges

    @Override
    public void set(int from, int to) {
        NatSetUtil.checkRange(from, to);
        if (from == to) {
            return;
        }
        long[] current = words;
        if (current == null) {
            if (to - from <= MAX_ARRAY_SIZE) {
                for (int index = from; index < to; index++) {
                    set(index);
                }
                return;
            }
            current = switchToWords(Math.max(to - 1, last()));
        }
        int maxWord = (to - 1) >>> WORD_SHIFT;
        long[] target = maxWord < current.length ? current : growWords(current, maxWord + 1);
        int minWord = from >>> WORD_SHIFT;
        long firstMask = -1L << from;
        long lastMask = -1L >>> -to;
        if (minWord == maxWord) {
            target[minWord] |= firstMask & lastMask;
        } else {
            target[minWord] |= firstMask;
            Arrays.fill(target, minWord + 1, maxWord, -1L);
            target[maxWord] |= lastMask;
        }
        recount(target);
    }

    @Override
    public void set(int from, int to, boolean value) {
        if (value) {
            set(from, to);
        } else {
            clear(from, to);
        }
    }

    @Override
    public void clear(int from, int to) {
        NatSetUtil.checkRange(from, to);
        if (from == to) {
            return;
        }
        long[] current = words;
        if (current == null) {
            int kept = 0;
            for (int index = 0; index < size; index++) {
                int element = elements[index];
                if (element < from || element >= to) {
                    elements[kept] = element;
                    kept += 1;
                }
            }
            size = kept;
            return;
        }
        int minWord = from >>> WORD_SHIFT;
        if (minWord >= current.length) {
            return;
        }
        int maxWord = (to - 1) >>> WORD_SHIFT;
        long lastMask = -1L >>> -to;
        if (maxWord >= current.length) {
            maxWord = current.length - 1;
            lastMask = -1L;
        }
        long firstMask = -1L << from;
        if (minWord == maxWord) {
            current[minWord] &= ~(firstMask & lastMask);
        } else {
            current[minWord] &= ~firstMask;
            Arrays.fill(current, minWord + 1, maxWord, 0L);
            current[maxWord] &= ~lastMask;
        }
        recount(current);
    }

    @Override
    public void clear() {
        long[] current = words;
        if (current != null) {
            Arrays.fill(current, 0L);
        }
        size = 0;
    }

    @Override
    public void flip(int from, int to) {
        NatSetUtil.checkRange(from, to);
        if (from == to) {
            return;
        }
        long[] current = words;
        if (current == null) {
            if (to - from <= MAX_ARRAY_SIZE) {
                for (int index = from; index < to; index++) {
                    flip(index);
                }
                return;
            }
            current = switchToWords(Math.max(to - 1, last()));
        }
        int maxWord = (to - 1) >>> WORD_SHIFT;
        long[] target = maxWord < current.length ? current : growWords(current, maxWord + 1);
        int minWord = from >>> WORD_SHIFT;
        long firstMask = -1L << from;
        long lastMask = -1L >>> -to;
        if (minWord == maxWord) {
            target[minWord] ^= firstMask & lastMask;
        } else {
            target[minWord] ^= firstMask;
            for (int index = minWord + 1; index < maxWord; index++) {
                target[index] = ~target[index];
            }
            target[maxWord] ^= lastMask;
        }
        recount(target);
    }

    // Bulk operations

    @Override
    public void and(NatSet other) {
        if (other == this) { // NOPMD - identity is the point of the check
            return;
        }
        long[] current = words;
        if (current == null) {
            int kept = 0;
            for (int index = 0; index < size; index++) {
                int element = elements[index];
                if (other.contains(element)) {
                    elements[kept] = element;
                    kept += 1;
                }
            }
            size = kept;
            return;
        }
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (otherWords == null) {
            long[] kept = new long[current.length];
            other.forEach(element -> {
                int wordIndex = element >>> WORD_SHIFT;
                if (wordIndex < current.length) {
                    kept[wordIndex] |= current[wordIndex] & (1L << element);
                }
            });
            System.arraycopy(kept, 0, current, 0, current.length);
        } else {
            int common = Math.min(current.length, otherWords.length);
            for (int index = 0; index < common; index++) {
                current[index] &= otherWords[index];
            }
            Arrays.fill(current, common, current.length, 0L);
        }
        recount(current);
    }

    @Override
    public void or(NatSet other) {
        if (other == this || other.isEmpty()) { // NOPMD - identity is the point of the check
            return;
        }
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (otherWords == null) {
            other.forEach(this::set);
            return;
        }
        long[] current = words;
        if (current == null) {
            current = switchToWords(Math.max(last(), other.last()));
        }
        long[] target = otherWords.length <= current.length ? current : growWords(current, otherWords.length);
        for (int index = 0; index < otherWords.length; index++) {
            target[index] |= otherWords[index];
        }
        recount(target);
    }

    @Override
    public void andNot(NatSet other) {
        if (other == this) { // NOPMD - identity is the point of the check
            clear();
            return;
        }
        long[] current = words;
        long[] otherWords = NatSetUtil.wordsOf(other);
        if (current != null && otherWords != null) {
            int common = Math.min(current.length, otherWords.length);
            for (int index = 0; index < common; index++) {
                current[index] &= ~otherWords[index];
            }
            recount(current);
        } else if (current == null) {
            int kept = 0;
            for (int index = 0; index < size; index++) {
                int element = elements[index];
                if (!other.contains(element)) {
                    elements[kept] = element;
                    kept += 1;
                }
            }
            size = kept;
        } else {
            other.forEach(this::clear);
        }
    }

    @Override
    public void xor(NatSet other) {
        if (other == this) { // NOPMD - identity is the point of the check
            clear();
            return;
        }
        long[] otherWords = NatSetUtil.wordsOf(other);
        long[] current = words;
        if (otherWords == null) {
            other.forEach(this::flip);
            return;
        }
        if (current == null) {
            current = switchToWords(Math.max(last(), other.last()));
        }
        long[] target = otherWords.length <= current.length ? current : growWords(current, otherWords.length);
        for (int index = 0; index < otherWords.length; index++) {
            target[index] ^= otherWords[index];
        }
        recount(target);
    }

    // Combination

    @Override
    public NatSet union(NatSet other) {
        MutableNatSetImpl union = new MutableNatSetImpl(this);
        union.or(other);
        return ImmutableNatSet.freeze(union);
    }

    @Override
    public NatSet intersection(NatSet other) {
        MutableNatSetImpl intersection = new MutableNatSetImpl(this);
        intersection.and(other);
        return ImmutableNatSet.freeze(intersection);
    }

    @Override
    public NatSet difference(NatSet other) {
        MutableNatSetImpl difference = new MutableNatSetImpl(this);
        difference.andNot(other);
        return ImmutableNatSet.freeze(difference);
    }

    // Views, copies, bridges

    @Override
    public Set<Integer> boxed() {
        return this;
    }

    @Override
    public BitSet toBitSet() {
        long[] current = words;
        return current == null ? copyInto(new BitSet()) : BitSet.valueOf(current);
    }

    @Override
    public BitSet copyInto(BitSet target) {
        long[] current = words;
        if (current == null) {
            for (int index = 0; index < size; index++) {
                target.set(elements[index]);
            }
        } else {
            target.or(BitSet.valueOf(current));
        }
        return target;
    }

    // Set

    @Override
    public boolean add(Integer element) {
        if (contains((int) element)) {
            return false;
        }
        set(element);
        return true;
    }

    @Override
    public boolean remove(Object o) {
        if (!contains(o)) {
            return false;
        }
        clear((Integer) o);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o == this || NatSetUtil.setEquals(this, o);
    }

    @Override
    public int hashCode() {
        long[] current = words;
        return current == null ? NatSetUtil.arrayHash(elements, size) : NatSetUtil.wordsHash(current);
    }

    @Override
    public String toString() {
        return NatSetUtil.toString(this);
    }
}
