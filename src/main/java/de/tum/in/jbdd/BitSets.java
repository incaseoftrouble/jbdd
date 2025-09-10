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
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

public final class BitSets {
    private BitSets() {}

    public static BitSet of(int value) {
        BitSet set = new BitSet(value + 1);
        set.set(value);
        return set;
    }

    public static BitSet of(int... values) {
        BitSet set = new BitSet();
        for (int value : values) {
            set.set(value);
        }
        return set;
    }

    public static BitSet of(Iterator<Integer> values) {
        BitSet set = new BitSet();
        while (values.hasNext()) {
            set.set(values.next());
        }
        return set;
    }

    public static BitSet of(Iterable<Integer> values) {
        return of(values.iterator());
    }

    public static BitSet of(IntSupplier values) {
        BitSet set = new BitSet();
        while (true) {
            int value = values.getAsInt();
            if (value < 0) {
                return set;
            }
            set.set(value);
        }
    }

    public static BitSet of(IntStream values) {
        BitSet set = new BitSet();
        values.forEach(set::set);
        return set;
    }

    public static <V> BitSet of(Iterator<V> iterator, ToIntFunction<? super V> mapper) {
        BitSet set = new BitSet();
        while (iterator.hasNext()) {
            set.set(mapper.applyAsInt(iterator.next()));
        }
        return set;
    }

    public static <V> BitSet of(Iterable<V> values, ToIntFunction<? super V> mapper) {
        return of(values.iterator(), mapper);
    }

    @SuppressWarnings("UseOfClone")
    public static BitSet copyOf(BitSet set) {
        return (BitSet) set.clone();
    }

    public static boolean isSubset(BitSet set, BitSet of) {
        int cardinality = set.cardinality();
        if (cardinality > of.cardinality()) {
            return false;
        }
        if (cardinality == 0) {
            return true;
        }
        if (cardinality == 1) {
            return of.get(set.nextSetBit(0));
        }

        int length = set.length();
        if (cardinality < length / 32) {
            // Very sparse set, avoid copying it
            for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
                if (!of.get(i)) {
                    return false;
                }
            }
            return true;
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

    public static void forEach(BitSet bitSet, IntConsumer action) {
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            action.accept(i);
        }
    }

    public static boolean anyMatch(BitSet bitSet, IntPredicate predicate) {
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            if (predicate.test(i)) {
                return true;
            }
        }
        return false;
    }

    public static boolean allMatch(BitSet bitSet, IntPredicate predicate) {
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            if (!predicate.test(i)) {
                return false;
            }
        }
        return true;
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
