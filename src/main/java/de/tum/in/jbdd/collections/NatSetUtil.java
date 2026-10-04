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
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;

/**
 * What both {@link NatSet} implementations share: the two stores - the first {@code size} entries of an ascending
 * {@code int[]}, or words - read by static functions, and what is defined over the interface alone.
 */
final class NatSetUtil {
    /** Element {@code e} is bit {@code e} (modulo 64) of word {@code e >>> WORD_SHIFT}. */
    static final int WORD_SHIFT = Integer.numberOfTrailingZeros(Long.SIZE);

    /**
     * The most elements held as an array.
     */
    static final int MAX_ARRAY_SIZE = 16;
    /** How many array elements one word costs: words are used once they cost no more than the array. */
    static final int WORD_COST = 2;

    private static final long[] NO_WORDS = new long[0];

    private NatSetUtil() {}

    /**
     * Whether words over {@code [0, max]} take no more memory than {@code count} elements in an array, which the JVM
     * pads to 8 bytes, an even number of elements: a singleton below 64 is a word ({@code int[1]} and {@code long[1]}
     * both take 24 bytes), and so are three elements below 128.
     */
    static boolean useWords(int count, int max) {
        return count > 0 && (count > MAX_ARRAY_SIZE || WORD_COST * wordCount(max + 1) <= count + (count & 1));
    }

    /** The number of words holding {@code [0, bits)}. */
    static int wordCount(int bits) {
        return (bits + Long.SIZE - 1) >>> WORD_SHIFT;
    }

    /** The words of {@code set}, {@code null} if it is held as an array. */
    static long @Nullable [] wordsOf(NatSet set) {
        return set instanceof MutableNatSetImpl ? ((MutableNatSetImpl) set).words : ((ImmutableNatSet) set).words();
    }

    /** The words of {@code set}, none for the empty set, {@code null} if it is held as a non-empty array. */
    static long @Nullable [] wordsOrNone(NatSet set) {
        return set.isEmpty() ? NO_WORDS : wordsOf(set);
    }

    /**
     * The elements of {@code set}, the first {@code set.size()} entries, if it is held as an array; else
     * {@code null}.
     */
    static int @Nullable [] elementsOf(NatSet set) {
        return set instanceof MutableNatSetImpl
                ? ((MutableNatSetImpl) set).elements()
                : ((ImmutableNatSet) set).elements();
    }

    /** Word {@code index} of {@code words}, zero past its end. */
    static long word(long[] words, int index) {
        return index < words.length ? words[index] : 0L;
    }

    // Arrays

    static boolean arrayContains(int[] elements, int size, int element) {
        for (int index = 0; index < size; index++) {
            int present = elements[index];
            if (present >= element) {
                return present == element;
            }
        }
        return false;
    }

    static int arrayNext(int[] elements, int size, int from) {
        for (int index = 0; index < size; index++) {
            if (elements[index] >= from) {
                return elements[index];
            }
        }
        return -1;
    }

    static int arrayPrevious(int[] elements, int size, int from) {
        for (int index = size - 1; index >= 0; index--) {
            if (elements[index] <= from) {
                return elements[index];
            }
        }
        return -1;
    }

    static int arrayNextClear(int[] elements, int size, int from) {
        int candidate = from;
        for (int index = 0; index < size; index++) {
            int element = elements[index];
            if (element == candidate) {
                candidate += 1;
            } else if (element > candidate) {
                break;
            }
        }
        return candidate;
    }

    static int arrayHash(int[] elements, int size) {
        // The words the elements make, built on the fly, so that both representations hash alike.
        long hash = 0;
        int index = 0;
        while (index < size) {
            int wordIndex = elements[index] >>> WORD_SHIFT;
            long word = 0;
            do {
                word |= 1L << elements[index];
                index += 1;
            } while (index < size && elements[index] >>> WORD_SHIFT == wordIndex);
            hash = hashStep(hash, wordIndex, word);
        }
        return hashFinish(hash);
    }

    // Words

    static boolean wordsContain(long[] words, int element) {
        int wordIndex = element >>> WORD_SHIFT;
        return wordIndex < words.length && (words[wordIndex] & (1L << element)) != 0;
    }

    static int wordsNext(long[] words, int from) {
        int wordIndex = from >>> WORD_SHIFT;
        if (wordIndex >= words.length) {
            return -1;
        }
        long word = words[wordIndex] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(word);
            }
            wordIndex += 1;
            if (wordIndex == words.length) {
                return -1;
            }
            word = words[wordIndex];
        }
    }

    static int wordsPrevious(long[] words, int from) {
        int wordIndex = from >>> WORD_SHIFT;
        if (wordIndex >= words.length) {
            return wordsLength(words) - 1;
        }
        long word = words[wordIndex] & (-1L >>> (-(from + 1) & 63));
        while (true) {
            if (word != 0) {
                return ((wordIndex + 1) << WORD_SHIFT) - 1 - Long.numberOfLeadingZeros(word);
            }
            if (wordIndex == 0) {
                return -1;
            }
            wordIndex -= 1;
            word = words[wordIndex];
        }
    }

    static int wordsNextClear(long[] words, int from) {
        int wordIndex = from >>> WORD_SHIFT;
        if (wordIndex >= words.length) {
            return from;
        }
        long word = ~words[wordIndex] & (-1L << from);
        while (true) {
            if (word != 0) {
                return (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(word);
            }
            wordIndex += 1;
            if (wordIndex == words.length) {
                return wordIndex << WORD_SHIFT;
            }
            word = ~words[wordIndex];
        }
    }

    static int wordsLength(long[] words) {
        for (int index = words.length - 1; index >= 0; index--) {
            if (words[index] != 0) {
                return ((index + 1) << WORD_SHIFT) - Long.numberOfLeadingZeros(words[index]);
            }
        }
        return 0;
    }

    static int wordsCount(long[] words) {
        int count = 0;
        for (long word : words) {
            count += Long.bitCount(word);
        }
        return count;
    }

    /**
     * The hash code of a set: its non-zero words in ascending order, each with its index, through murmur3's
     * finalizer. Zero words do not count, so trailing ones and the representation do not matter.
     */
    static int wordsHash(long[] words) {
        long hash = 0;
        for (int index = 0; index < words.length; index++) {
            long word = words[index];
            if (word != 0) {
                hash = hashStep(hash, index, word);
            }
        }
        return hashFinish(hash);
    }

    private static long hashStep(long hash, int index, long word) {
        long mixed = word + index * 0xC2B2AE3D27D4EB4FL;
        mixed = (mixed ^ (mixed >>> 33)) * 0xFF51AFD7ED558CCDL;
        mixed = (mixed ^ (mixed >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return hash * 0x9E3779B97F4A7C15L + (mixed ^ (mixed >>> 33));
    }

    private static int hashFinish(long hash) {
        return Long.hashCode(hash);
    }

    /** Hands the elements of {@code words} to {@code action}, ascending. */
    static void wordsForEach(long[] words, IntConsumer action) {
        for (int index = 0; index < words.length; index++) {
            long word = words[index];
            while (word != 0) {
                action.accept((index << WORD_SHIFT) + Long.numberOfTrailingZeros(word));
                word &= word - 1;
            }
        }
    }

    /**
     * Hands the elements of {@code words} to {@code proceed}, ascending, as long as it returns {@code true}; whether it
     * saw all of them.
     */
    static boolean wordsWhile(long[] words, IntPredicate proceed) {
        for (int index = 0; index < words.length; index++) {
            long word = words[index];
            while (word != 0) {
                if (!proceed.test((index << WORD_SHIFT) + Long.numberOfTrailingZeros(word))) {
                    return false;
                }
                word &= word - 1;
            }
        }
        return true;
    }

    /** The first {@code size} entries of {@code elements}, ascending. */
    static final class ArrayIterator implements PrimitiveIterator.OfInt {
        private final int[] elements;
        private final int size;
        private int index = 0;

        ArrayIterator(int[] elements, int size) {
            this.elements = elements;
            this.size = size;
        }

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public int nextInt() {
            if (index >= size) {
                throw new NoSuchElementException();
            }
            int element = elements[index];
            index += 1;
            return element;
        }
    }

    /** Whether some element of the first {@code size} entries of {@code elements} satisfies {@code predicate}. */
    static boolean arrayAnyMatch(int[] elements, int size, IntPredicate predicate) {
        for (int index = 0; index < size; index++) {
            if (predicate.test(elements[index])) {
                return true;
            }
        }
        return false;
    }

    /** Writes the union of two ascending arrays to {@code target}, ascending; how many it wrote. */
    static int arrayUnion(int[] first, int firstSize, int[] second, int secondSize, int[] target) {
        int i = 0;
        int j = 0;
        int count = 0;
        while (i < firstSize && j < secondSize) {
            int a = first[i];
            int b = second[j];
            target[count] = Math.min(a, b);
            count += 1;
            if (a <= b) {
                i += 1;
            }
            if (b <= a) {
                j += 1;
            }
        }
        System.arraycopy(first, i, target, count, firstSize - i);
        count += firstSize - i;
        System.arraycopy(second, j, target, count, secondSize - j);
        return count + secondSize - j;
    }

    /** Writes the intersection of two ascending arrays to {@code target}, ascending; how many it wrote. */
    static int arrayIntersection(int[] first, int firstSize, int[] second, int secondSize, int[] target) {
        int i = 0;
        int j = 0;
        int count = 0;
        while (i < firstSize && j < secondSize) {
            int a = first[i];
            int b = second[j];
            if (a == b) {
                target[count] = a;
                count += 1;
            }
            if (a <= b) {
                i += 1;
            }
            if (b <= a) {
                j += 1;
            }
        }
        return count;
    }

    /** Writes the elements of the first ascending array not in the second to {@code target}; how many it wrote. */
    static int arrayDifference(int[] first, int firstSize, int[] second, int secondSize, int[] target) {
        int j = 0;
        int count = 0;
        for (int i = 0; i < firstSize; i++) {
            int a = first[i];
            while (j < secondSize && second[j] < a) {
                j += 1;
            }
            if (j == secondSize || second[j] != a) {
                target[count] = a;
                count += 1;
            }
        }
        return count;
    }

    /** Whether every element of the first {@code size} entries of {@code elements} satisfies {@code predicate}. */
    static boolean arrayAllMatch(int[] elements, int size, IntPredicate predicate) {
        for (int index = 0; index < size; index++) {
            if (!predicate.test(elements[index])) {
                return false;
            }
        }
        return true;
    }

    /** The elements of {@code words}, ascending: a cursor on a word, which hands out and clears its lowest bit. */
    static final class WordsIterator implements PrimitiveIterator.OfInt {
        private final long[] words;
        private int wordIndex = -1;
        // The current word's elements not handed out yet; zero once every word is through.
        private long word = 0;

        WordsIterator(long[] words) {
            this.words = words;
            nextWord();
        }

        private void nextWord() {
            while (word == 0 && wordIndex + 1 < words.length) {
                wordIndex += 1;
                word = words[wordIndex];
            }
        }

        @Override
        public boolean hasNext() {
            return word != 0;
        }

        @Override
        public int nextInt() {
            long current = word;
            if (current == 0) {
                throw new NoSuchElementException();
            }
            int element = (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(current);
            word = current & (current - 1);
            if (word == 0) {
                nextWord();
            }
            return element;
        }
    }

    /**
     * {@code words} with every element shifted by {@code amount} (towards 0 if negative), those that would be
     * negative dropped: a new array, possibly with trailing zero words.
     */
    static long[] shiftedWords(long[] words, int amount) {
        assert amount > Integer.MIN_VALUE : amount;
        int distance = Math.abs(amount);
        int wordShift = distance >>> WORD_SHIFT;
        int bitShift = distance & (Long.SIZE - 1);
        if (amount >= 0) {
            long[] shifted = new long[words.length + wordShift + (bitShift == 0 ? 0 : 1)];
            for (int index = 0; index < words.length; index++) {
                long word = words[index];
                shifted[index + wordShift] |= word << bitShift;
                if (bitShift != 0) {
                    shifted[index + wordShift + 1] |= word >>> (Long.SIZE - bitShift);
                }
            }
            return shifted;
        }
        if (wordShift >= words.length) {
            return new long[0];
        }
        long[] shifted = new long[words.length - wordShift];
        for (int index = wordShift; index < words.length; index++) {
            long word = words[index];
            shifted[index - wordShift] |= word >>> bitShift;
            if (bitShift != 0 && index > wordShift) {
                shifted[index - wordShift - 1] |= word << (Long.SIZE - bitShift);
            }
        }
        return shifted;
    }

    /** The index of the first of the ascending {@code elements[0, size)} that stays natural when shifted by amount. */
    static int arrayFirstKept(int[] elements, int size, int amount) {
        int first = 0;
        // Elements are natural and amount is negative here, so the sum cannot overflow.
        while (amount < 0 && first < size && elements[first] + amount < 0) {
            first += 1;
        }
        return first;
    }

    /** {@code words} without trailing zero words, a copy. */
    static long[] trimmedWords(long[] words) {
        return Arrays.copyOf(words, wordCount(wordsLength(words)));
    }

    /** The ascending elements, as words without trailing zero words. */
    static long[] arrayToWords(int[] elements, int size) {
        long[] words = new long[size == 0 ? 0 : wordCount(elements[size - 1] + 1)];
        for (int index = 0; index < size; index++) {
            words[elements[index] >>> WORD_SHIFT] |= 1L << elements[index];
        }
        return words;
    }

    /** The elements of {@code words}, of which there are {@code count}, as an ascending array. */
    static int[] wordsToArray(long[] words, int count) {
        int[] array = new int[count];
        int index = 0;
        for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
            long word = words[wordIndex];
            while (word != 0) {
                array[index] = (wordIndex << WORD_SHIFT) + Long.numberOfTrailingZeros(word);
                index += 1;
                word &= word - 1;
            }
        }
        return array;
    }

    // Over the interface

    /**
     * The element of {@code set} after {@code element}, {@code -1} if there is none: {@code nextSetBit(element + 1)},
     * which would overflow after {@link Integer#MAX_VALUE}, a valid element.
     */
    static int nextAfter(NatSet set, int element) {
        return element == Integer.MAX_VALUE ? -1 : set.nextSetBit(element + 1);
    }

    @SuppressWarnings("ObjectEquality")
    static boolean containsAll(NatSet set, NatSet other) {
        if (other == set) { // NOPMD - identity is the point of the check
            return true;
        }
        if (other.size() > set.size()) {
            return false;
        }
        long[] words = wordsOf(set);
        long[] otherWords = wordsOf(other);
        if (words != null && otherWords != null) {
            for (int index = 0; index < otherWords.length; index++) {
                long bits = otherWords[index];
                if (bits != 0 && (index >= words.length || (bits & ~words[index]) != 0)) {
                    return false;
                }
            }
            return true;
        }
        if (words != null) {
            // Mostly a word against an empty set or a singleton. In the loop rather than a helper: a call site not hot
            // enough inlines no more than 35 bytes.
            int[] elements = elementsOf(other);
            assert elements != null;
            int size = other.size();
            for (int index = 0; index < size; index++) {
                if (!wordsContain(words, elements[index])) {
                    return false;
                }
            }
            return true;
        }
        return other.allMatch(set::contains);
    }

    @SuppressWarnings("ObjectEquality")
    static boolean intersects(NatSet set, NatSet other) {
        long[] words = wordsOf(set);
        long[] otherWords = wordsOf(other);
        if (words != null && otherWords != null) {
            int common = Math.min(words.length, otherWords.length);
            for (int index = 0; index < common; index++) {
                if ((words[index] & otherWords[index]) != 0) {
                    return true;
                }
            }
            return false;
        }
        if (words != null || otherWords != null) {
            // An array against words: its elements tested in the words, in the loop as in containsAll.
            NatSet array = words == null ? set : other;
            long[] against = words == null ? otherWords : words;
            int[] elements = elementsOf(array);
            assert against != null && elements != null;
            int size = array.size();
            for (int index = 0; index < size; index++) {
                if (wordsContain(against, elements[index])) {
                    return true;
                }
            }
            return false;
        }
        NatSet smaller = other.size() < set.size() ? other : set;
        NatSet larger = smaller == set ? other : set; // NOPMD - identity is the point of the check
        return smaller.anyMatch(larger::contains);
    }

    static int compare(NatSet first, NatSet second) {
        int sizes = Integer.compare(first.size(), second.size());
        if (sizes != 0) {
            return sizes;
        }
        long[] firstWords = wordsOf(first);
        long[] secondWords = wordsOf(second);
        if (firstWords != null && secondWords != null) {
            // The smallest element in one set only decides: up to it both agree, and there the one holding it has the
            // smaller element. Of equal sizes, sets equal up to the shorter's end are equal.
            int common = Math.min(firstWords.length, secondWords.length);
            int index = Arrays.mismatch(firstWords, 0, common, secondWords, 0, common);
            if (index < 0) {
                return 0;
            }
            long difference = firstWords[index] ^ secondWords[index];
            return (firstWords[index] & Long.lowestOneBit(difference)) == 0 ? 1 : -1;
        }
        int[] firstElements = elementsOf(first);
        int[] secondElements = elementsOf(second);
        if (firstElements != null && secondElements != null) {
            int size = first.size();
            return Arrays.compare(firstElements, 0, size, secondElements, 0, size);
        }
        PrimitiveIterator.OfInt firstIterator = first.iterator();
        PrimitiveIterator.OfInt secondIterator = second.iterator();
        while (firstIterator.hasNext()) {
            int i = firstIterator.nextInt();
            int j = secondIterator.nextInt();
            if (i != j) {
                return Integer.compare(i, j);
            }
        }
        return 0;
    }

    static String toString(NatSet set) {
        StringBuilder builder = new StringBuilder(2 + 4 * set.size()).append('[');
        set.forEach((int element) -> {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(element);
        });
        return builder.append(']').toString();
    }

    /** Whether {@code set} equals {@code other}: a {@link NatSet} of the same elements. */
    static boolean setEquals(NatSet set, Object other) {
        if (!(other instanceof NatSet)) {
            return false;
        }
        NatSet natSet = (NatSet) other;
        int size = set.size();
        if (size != natSet.size()) {
            return false;
        }
        // Only an immutable set knows its hash code without computing it.
        if (set instanceof ImmutableNatSet
                && natSet instanceof ImmutableNatSet
                && set.hashCode() != natSet.hashCode()) {
            return false;
        }
        long[] words = wordsOf(set);
        long[] otherWords = wordsOf(natSet);
        if (words != null && otherWords != null) {
            // A mutable set's words may run past its last element. Of equal sizes, sets with equal words up to the
            // shorter's end leave no element for the longer's rest.
            int common = Math.min(words.length, otherWords.length);
            boolean equal = Arrays.equals(words, 0, common, otherWords, 0, common);
            assert !equal || wordsLength(words) == wordsLength(otherWords);
            return equal;
        }
        if (words == null && otherWords == null) {
            int[] elements = elementsOf(set);
            int[] otherElements = elementsOf(natSet);
            assert elements != null && otherElements != null;
            return Arrays.equals(elements, 0, size, otherElements, 0, size);
        }
        return containsAll(set, natSet);
    }
}
