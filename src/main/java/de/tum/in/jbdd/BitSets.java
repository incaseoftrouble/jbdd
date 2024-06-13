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
package de.tum.in.jbdd;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class BitSets {
    private BitSets() {}

    @SuppressWarnings("UseOfClone")
    public static BitSet copyOf(BitSet set) {
        return (BitSet) set.clone();
    }

    public static boolean isSubset(BitSet set, BitSet of) {
        if (set.cardinality() > of.cardinality()) {
            return false;
        }
        if (set.length() > of.length()) {
            return false;
        }
        BitSet copy = copyOf(set);
        copy.andNot(of);
        return copy.isEmpty();
    }

    public static int[] toArray(BitSet set) {
        int[] array = new int[set.cardinality()];
        int pos = 0;
        for (int bit = set.nextSetBit(0); bit >= 0; bit = set.nextSetBit(bit + 1)) {
            array[pos] = bit;
            pos += 1;
        }
        return array;
    }

    public static Iterator<BitSet> powerSetIterator(BitSet support) {
        if (support.isEmpty()) {
            return Collections.singleton(new BitSet()).iterator();
        }
        return new PowerIteratorShift(support);
    }

    public static Iterator<BitSet> powerSetIterator(int size) {
        if (size == 0) {
            return Collections.singleton(new BitSet()).iterator();
        }
        return new PowerIterator(size);
    }

    private static final class PowerIteratorShift implements Iterator<BitSet> {
        private final BitSet bitSet;
        private final int[] restrictionPositions;
        private final BitSet variableRestriction;
        private int assignment;

        PowerIteratorShift(BitSet restriction) {
            this(restriction.length(), restriction);
        }

        PowerIteratorShift(int size, BitSet restriction) {
            assert restriction.cardinality() > 0;
            this.bitSet = new BitSet(size);
            this.variableRestriction = restriction;
            this.assignment = 0;
            this.restrictionPositions = new int[restriction.cardinality()];

            restrictionPositions[0] = restriction.nextSetBit(0);
            for (int i = 1; i < restrictionPositions.length; i++) {
                restrictionPositions[i] = restriction.nextSetBit(restrictionPositions[i - 1] + 1);
            }
        }

        @Override
        public boolean hasNext() {
            return !Objects.equals(bitSet, variableRestriction);
        }

        @Override
        public BitSet next() {
            if (assignment == 1 << restrictionPositions.length) {
                throw new NoSuchElementException("No next element");
            }

            bitSet.clear();

            for (int restrictionPosition = 0;
                    restrictionPosition < restrictionPositions.length;
                    restrictionPosition++) {
                if (((assignment >>> restrictionPosition) & 1) == 1) {
                    bitSet.set(restrictionPositions[restrictionPosition]);
                }
            }
            assignment += 1;
            return bitSet;
        }
    }

    static final class PowerIterator implements Iterator<BitSet> {
        private final BitSet iteration;
        private final int[] base;
        private int numSetBits = -1;

        private PowerIterator(int size) {
            base = new int[size];
            Arrays.setAll(base, i -> i);
            iteration = new BitSet(size);
        }

        private PowerIterator(BitSet base) {
            this.base = toArray(base);
            iteration = new BitSet(base.length());
        }

        @Override
        public boolean hasNext() {
            return numSetBits < base.length;
        }

        @Override
        public BitSet next() {
            if (numSetBits == -1) {
                numSetBits = 0;
                return iteration;
            }

            if (numSetBits == base.length) {
                throw new NoSuchElementException("No next element");
            }

            for (int index : base) {
                if (iteration.get(index)) {
                    iteration.clear(index);
                    numSetBits -= 1;
                } else {
                    iteration.set(index);
                    numSetBits += 1;
                    break;
                }
            }

            return iteration;
        }
    }
}
