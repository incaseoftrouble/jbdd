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
package de.tum.in.jbdd;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

final class ScopedAssignments {
    private static final int RANDOM_SAMPLE_SIZE = 256;

    private ScopedAssignments() {}

    static Iterable<boolean[]> of(int length, int maxVariables, BitSet... variableSets) {
        BitSet relevant = BitSets.lazyUnion(variableSets);
        int variableCount = relevant.cardinality();
        if (variableCount <= maxVariables) {
            return () -> new PowerSetIterator(relevant, length);
        }
        if (variableCount == length) {
            return () -> new SimplePowerSetIterator(length);
        }
        int seed = Arrays.hashCode(variableSets) ^ HashUtil.hash(maxVariables);
        return () -> new RandomAssignmentIterator(relevant, length, RANDOM_SAMPLE_SIZE, new Random(seed));
    }

    private static final class RandomAssignmentIterator implements Iterator<boolean[]> {
        private final boolean[] assignment;
        private final int[] positions;
        private final Random random;
        private final int sampleSize;
        private int produced = 0;

        RandomAssignmentIterator(BitSet relevant, int length, int sampleSize, Random random) {
            this.assignment = new boolean[length];
            this.positions = relevant.stream().toArray();
            this.random = random;
            this.sampleSize = sampleSize;
        }

        @Override
        public boolean hasNext() {
            return produced < sampleSize;
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public boolean[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            for (int position : positions) {
                assignment[position] = random.nextBoolean();
            }
            produced++;
            return assignment;
        }
    }

    static final class SimplePowerSetIterator implements Iterator<boolean[]> {
        private final int length;
        private final boolean[] next;
        private boolean hasNext = true;

        SimplePowerSetIterator(int length) {
            this.length = length;
            this.next = new boolean[length];
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public boolean[] next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            for (int i = 0; i < length; i++) {
                if (next[i]) {
                    next[i] = false;
                } else {
                    next[i] = true;
                    return next;
                }
            }
            hasNext = false;
            return next;
        }
    }

    static final class PowerSetIterator implements Iterator<boolean[]> {
        private final int[] indices;
        private final boolean[] next;
        private boolean hasNext = true;

        PowerSetIterator(boolean[] base) {
            int length = base.length;

            int[] indices = new int[length];
            int count = 0;
            for (int i = 0; i < length; i++) {
                if (base[i]) {
                    indices[count] = i;
                    count += 1;
                }
            }
            this.indices = Arrays.copyOf(indices, count);
            this.next = new boolean[length];
        }

        PowerSetIterator(BitSet base, int length) {
            this.indices = base.stream().toArray();
            this.next = new boolean[length];
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public boolean[] next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            for (int index : indices) {
                if (next[index]) {
                    next[index] = false;
                } else {
                    next[index] = true;
                    return next;
                }
            }
            hasNext = false;
            return next;
        }
    }
}
