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

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/**
 * What both {@link NatSet} implementations share: the two stores - the first {@code size} entries of an ascending
 * {@code int[]}, or words - read by static functions, and what is defined over the interface alone.
 */
final class NatSetUtil {
    /** Element {@code e} is bit {@code e} (modulo 64) of word {@code e >>> WORD_SHIFT}. */
    static final int WORD_SHIFT = Integer.numberOfTrailingZeros(Long.SIZE);

    /** The most elements held as an array. */
    static final int MAX_ARRAY_SIZE = 16;
    /** Words {@link #wordsForEach} samples before choosing how to walk the rest (as naturals-util: 1024 bits). */
    static final int SAMPLE_WORDS = 16;
    /** Average run length from which walking run by run beats extracting bit by bit (as naturals-util). */
    static final int RUN_LENGTH_THRESHOLD = 4;

    private NatSetUtil() {}

    /** Whether words over {@code [0, max]} take no more memory than {@code count} elements in an array. */
    static boolean useWords(int count, int max) {
        return count > 0 && (count > MAX_ARRAY_SIZE || 2 * wordCount(max + 1) <= count);
    }

    /** The number of words holding {@code [0, bits)}. */
    static int wordCount(int bits) {
        return (bits + Long.SIZE - 1) >>> WORD_SHIFT;
    }

    /** The words of {@code set}, {@code null} if it is held as an array. */
    static long @Nullable [] wordsOf(NatSet set) {
        return set instanceof MutableNatSetImpl ? ((MutableNatSetImpl) set).words : ((ImmutableNatSet) set).words();
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
        long word = words[wordIndex] & (-1L >>> -(from + 1));
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

    /**
     * Hands each element of {@code words} to {@code action}, ascending. As naturals-util's {@code BitSets.forEach}:
     * the first {@link #SAMPLE_WORDS} words are walked run by run, counting runs and elements; the rest run by run
     * if runs average at least {@link #RUN_LENGTH_THRESHOLD} elements (one loop per run instead of one bit
     * extraction per element), bit by bit otherwise (cheaper per element when runs are short).
     */
    static void wordsForEach(long[] words, IntConsumer action) {
        int sampled = Math.min(words.length, SAMPLE_WORDS);
        int elements = 0;
        int runs = 0;
        for (int index = 0; index < sampled; index++) {
            long word = words[index];
            while (word != 0) {
                int start = Long.numberOfTrailingZeros(word);
                int end = start + Long.numberOfTrailingZeros(~(word >>> start));
                int base = index << WORD_SHIFT;
                for (int element = base + start; element < base + end; element++) {
                    action.accept(element);
                }
                elements += end - start;
                runs += 1;
                word = end == Long.SIZE ? 0 : word & (-1L << end);
            }
        }
        if (elements >= RUN_LENGTH_THRESHOLD * runs) {
            for (int index = sampled; index < words.length; index++) {
                long word = words[index];
                while (word != 0) {
                    int start = Long.numberOfTrailingZeros(word);
                    int end = start + Long.numberOfTrailingZeros(~(word >>> start));
                    int base = index << WORD_SHIFT;
                    for (int element = base + start; element < base + end; element++) {
                        action.accept(element);
                    }
                    word = end == Long.SIZE ? 0 : word & (-1L << end);
                }
            }
        } else {
            for (int index = sampled; index < words.length; index++) {
                long word = words[index];
                while (word != 0) {
                    action.accept((index << WORD_SHIFT) + Long.numberOfTrailingZeros(word));
                    word &= word - 1;
                }
            }
        }
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
        for (int element = other.nextSetBit(0); element >= 0; element = other.nextSetBit(element + 1)) {
            if (!set.contains(element)) {
                return false;
            }
        }
        return true;
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
        NatSet smaller = other.size() < set.size() ? other : set;
        NatSet larger = smaller == set ? other : set; // NOPMD - identity is the point of the check
        for (int element = smaller.nextSetBit(0); element >= 0; element = smaller.nextSetBit(element + 1)) {
            if (larger.contains(element)) {
                return true;
            }
        }
        return false;
    }

    static int compare(NatSet first, NatSet second) {
        int sizes = Integer.compare(first.size(), second.size());
        if (sizes != 0) {
            return sizes;
        }
        int i = first.nextSetBit(0);
        int j = second.nextSetBit(0);
        while (i >= 0) {
            if (i != j) {
                return Integer.compare(i, j);
            }
            i = first.nextSetBit(i + 1);
            j = second.nextSetBit(j + 1);
        }
        return 0;
    }

    static String toString(NatSet set) {
        StringBuilder builder = new StringBuilder(2 + 4 * set.size()).append('[');
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(i);
        }
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
        return containsAll(set, natSet);
    }
}
