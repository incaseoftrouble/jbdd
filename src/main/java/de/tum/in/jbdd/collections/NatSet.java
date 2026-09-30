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
import java.util.Comparator;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * A finite set of naturals, read through primitives. The sets this interface's factories and operations return
 * never change, so they are shared rather than copied; a {@link MutableNatSet} is a {@code NatSet} too, which its
 * reader does not modify. A method returning a set the caller may modify returns a {@link MutableNatSet} of the
 * caller's own; {@link MutableNatSet#copyOf(NatSet)} turns any set into one.
 *
 * <p>Equal to any {@link Set} of the same {@link Integer}s, with {@link Set}'s hash code, and printed as one is
 * ({@code [0, 3]}). {@link #boxed()} is that set, a view.
 *
 * <p>The factories take naturals, checked by assertion only; {@link MutableNatSet}'s mutators throw for a negative
 * index as {@link BitSet}'s do.
 *
 * <p>Only this package implements it.
 */
public interface NatSet {
    /** By size, then lexicographically on the elements in ascending order. */
    Comparator<NatSet> ORDER = NatSetUtil::compare;

    /** The empty set. */
    static NatSet of() {
        return ImmutableNatSet.EMPTY;
    }

    /** The set of {@code element}. */
    static NatSet of(int element) {
        return ImmutableNatSet.singleton(element);
    }

    /** The set of {@code elements}, in any order and with repetitions. */
    static NatSet of(int... elements) {
        return ImmutableNatSet.of(elements);
    }

    /** The naturals from {@code from} inclusive to {@code to} exclusive; empty if {@code to <= from}. */
    static NatSet range(int from, int to) {
        return ImmutableNatSet.range(from, to);
    }

    /** A set that never changes with the elements of {@code set}: {@code set} itself if it never changes. */
    static NatSet copyOf(NatSet set) {
        return ImmutableNatSet.copyOf(set);
    }

    static NatSet copyOf(BitSet set) {
        return ImmutableNatSet.copyOf(set);
    }

    static NatSet copyOf(Collection<Integer> elements) {
        return ImmutableNatSet.copyOf(elements);
    }

    /** Whether {@code element} is in this set; {@code false} for a negative one. */
    boolean contains(int element);

    /** The number of elements. */
    int size();

    /** {@link #size()}, under {@link BitSet}'s name. */
    default int cardinality() {
        return size();
    }

    boolean isEmpty();

    boolean containsAll(NatSet other);

    boolean intersects(NatSet other);

    /** The smallest element, {@code -1} if empty. */
    int first();

    /** The largest element, {@code -1} if empty. */
    int last();

    /**
     * The smallest element at least {@code from}, {@code -1} if there is none - as {@link BitSet#nextSetBit(int)}.
     *
     * @throws IndexOutOfBoundsException if {@code from} is negative
     */
    int nextSetBit(int from);

    /**
     * The largest element at most {@code from}, {@code -1} if there is none - as {@link BitSet#previousSetBit(int)}.
     *
     * @throws IndexOutOfBoundsException if {@code from} is below {@code -1}
     */
    int previousSetBit(int from);

    /**
     * The smallest natural at least {@code from} not in this set - as {@link BitSet#nextClearBit(int)}.
     *
     * @throws IndexOutOfBoundsException if {@code from} is negative
     */
    int nextClearBit(int from);

    /** One more than the largest element, {@code 0} if empty - as {@link BitSet#length()}. */
    int length();

    /**
     * Hands each element to {@code action}, in ascending order. The fastest way over all elements; to stop early,
     * loop over {@link #nextSetBit(int)}.
     */
    void forEach(IntConsumer action);

    /** The elements in ascending order. */
    IntStream intStream();

    /** The elements in ascending order. */
    int[] toIntArray();

    /** The union, a set that never changes; may be an operand that never changes. */
    NatSet union(NatSet other);

    /** The intersection, a set that never changes; may be an operand that never changes. */
    NatSet intersection(NatSet other);

    /** This set without the elements of {@code other}, a set that never changes; may be this set if it never changes. */
    NatSet difference(NatSet other);

    /** This set as a {@code Set<Integer>}, a view: unmodifiable unless this set is a {@link MutableNatSet}. */
    Set<Integer> boxed();

    /** The elements as a new {@link BitSet}. */
    BitSet toBitSet();

    /** Adds the elements to {@code target} and returns it. */
    BitSet copyInto(BitSet target);

    /** Equal to any {@link Set} of the same elements. */
    @Override
    boolean equals(Object o);

    /** The sum of the elements, as {@link Set#hashCode()}. */
    @Override
    int hashCode();
}
