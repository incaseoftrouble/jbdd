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

import java.util.BitSet;
import java.util.Collection;
import java.util.function.IntPredicate;

/**
 * A {@link NatSet} to be modified, the holder's own: what a method returning a copy returns. {@link BitSet}'s
 * mutators under their names.
 * A few elements far apart are held as a sorted array, anything else as words; {@link #optimize()} reconsiders
 * that, which nothing else ever undoes.
 */
public interface MutableNatSet extends NatSet {
    static MutableNatSet create() {
        return new MutableNatSetImpl();
    }

    /** An empty set that holds any subset of {@code [0, capacity)} as words from the start, without growing. */
    static MutableNatSet dense(int capacity) {
        return MutableNatSetImpl.dense(capacity);
    }

    static MutableNatSet of(int... elements) {
        return MutableNatSetImpl.of(elements);
    }

    /** The naturals from {@code from} inclusive to {@code to} exclusive, as words; empty if {@code to <= from}. */
    static MutableNatSet range(int from, int to) {
        MutableNatSet set = dense(Math.max(to, 0));
        if (from < to) {
            set.set(from, to);
        }
        return set;
    }

    static MutableNatSet copyOf(NatSet set) {
        return MutableNatSetImpl.copyOf(set);
    }

    /** The set whose words are {@code words} - see {@link NatSet#valueOf}. */
    static MutableNatSet valueOf(long... words) {
        return MutableNatSetImpl.valueOf(words);
    }

    static MutableNatSet copyOf(BitSet set) {
        return MutableNatSetImpl.copyOf(set);
    }

    static MutableNatSet copyOf(Collection<Integer> elements) {
        return MutableNatSetImpl.copyOf(elements);
    }

    void set(int index);

    void set(int index, boolean value);

    void set(int from, int to);

    void set(int from, int to, boolean value);

    void clear(int index);

    void clear(int from, int to);

    void clear();

    void flip(int index);

    void flip(int from, int to);

    void and(NatSet other);

    void or(NatSet other);

    void andNot(NatSet other);

    void xor(NatSet other);

    /** Removes every element satisfying {@code filter}; whether any did. */
    boolean removeIf(IntPredicate filter);

    /** {@link #shifted(int)} in place: adds {@code amount} to every element, dropping those that would be negative. */
    void shift(int amount);

    /**
     * This set's elements, taken over without copying, and this set cleared: the end of building a set that is kept.
     * {@link NatSet#copyOf(NatSet)} is the copying form.
     */
    NatSet freezeAndClear();

    /** Chooses the smaller of the two representations for the current elements. */
    void optimize();
}
