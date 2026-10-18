/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import de.tum.in.jbdd.collections.NatSets;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/** The tests' helpers over {@link NatSet}; the sets they return are mutable, as the tests' own valuations are. */
final class NatSetFixtures {
    private NatSetFixtures() {}

    static MutableNatSet of(int... elements) {
        return MutableNatSet.of(elements);
    }

    /** The set whose words are {@code words}, as {@link java.util.BitSet#valueOf(long[])} reads them. */
    static MutableNatSet valueOf(long... words) {
        return MutableNatSet.copyOf(java.util.BitSet.valueOf(words));
    }

    static MutableNatSet range(int from, int to) {
        MutableNatSet set = MutableNatSet.create();
        set.set(from, to);
        return set;
    }

    static MutableNatSet copyOf(NatSet set) {
        return MutableNatSet.copyOf(set);
    }

    static boolean isSubset(NatSet set, NatSet of) {
        return of.containsAll(set);
    }

    static void forEach(NatSet set, IntConsumer action) {
        set.forEach(action);
    }

    static MutableNatSet map(NatSet source, IntUnaryOperator mapping) {
        return NatSets.map(source, mapping);
    }

    static void map(NatSet source, MutableNatSet target, IntUnaryOperator mapping) {
        NatSets.map(source, target, mapping);
    }

    static void difference(MutableNatSet target, NatSet from, NatSet minus) {
        NatSets.difference(target, from, minus);
    }

    static boolean increment(MutableNatSet number, NatSet positions) {
        return NatSets.increment(number, positions);
    }

    static MutableNatSet union(NatSet... sets) {
        MutableNatSet union = MutableNatSet.copyOf(sets[0]);
        for (int index = 1; index < sets.length; index++) {
            union.or(sets[index]);
        }
        return union;
    }

    static NatSet lazyUnion(NatSet... sets) {
        return sets.length == 1 ? sets[0] : union(sets);
    }

    /** Every subset of {@code support}, as one set changed in place from step to step. */
    static Iterator<NatSet> powerSetIterator(NatSet support) {
        if (support.isEmpty()) {
            return Collections.<NatSet>singleton(MutableNatSet.create()).iterator();
        }
        return new PowerIterator(support.toIntArray());
    }

    /** Every subset of {@code [0, size)}, as one set changed in place from step to step. */
    static Iterator<NatSet> powerSetIterator(int size) {
        return powerSetIterator(NatSet.range(0, size));
    }

    private static final class PowerIterator implements Iterator<NatSet> {
        private final int[] base;
        private final MutableNatSet iteration = MutableNatSet.create();
        private long remaining;

        PowerIterator(int[] base) {
            this.base = base;
            this.remaining = 1L << base.length;
        }

        @Override
        public boolean hasNext() {
            return remaining > 0;
        }

        @Override
        public NatSet next() {
            if (remaining == 0) {
                throw new NoSuchElementException();
            }
            if (remaining < 1L << base.length) {
                for (int index : base) {
                    if (iteration.contains(index)) {
                        iteration.clear(index);
                    } else {
                        iteration.set(index);
                        break;
                    }
                }
            }
            remaining -= 1;
            return iteration;
        }
    }
}
