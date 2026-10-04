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
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * A finite set of naturals, read through primitives. The sets this interface's factories and operations return
 * never change, so they are shared rather than copied: a result may be one of its operands, where that operand
 * never changes. A {@link MutableNatSet} is a {@code NatSet} too, which its reader does not modify. A method
 * returning a set the caller may modify returns a {@link MutableNatSet} of the caller's own;
 * {@link MutableNatSet#copyOf(NatSet)} turns any set into one.
 *
 * <p>Equal to any {@code NatSet} of the same elements, whatever its class or representation, and printed as a
 * {@link Set} is ({@code [0, 3]}). It is not a {@link Set}: {@link #boxed()} is that, a view, to be avoided where a
 * primitive operation serves.
 *
 * <p>Arguments are naturals (and ranges ordered), checked by assertion only.
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

    /** The set of {@code elements} (they may be in any order and with repetitions). */
    static NatSet of(int... elements) {
        return ImmutableNatSet.of(elements);
    }

    /** The naturals from {@code from} inclusive to {@code to} exclusive; empty if {@code to <= from}. */
    static NatSet range(int from, int to) {
        return ImmutableNatSet.range(from, to);
    }

    /** The set of {@code set}'s elements. */
    static NatSet copyOf(NatSet set) {
        return ImmutableNatSet.copyOf(set);
    }

    /** The set whose words are {@code words}: element {@code i} is bit {@code i % 64} of word {@code i / 64}. */
    static NatSet valueOf(long[] words) {
        return ImmutableNatSet.valueOf(words);
    }

    /** The set of {@code set}'s bits. */
    static NatSet copyOf(BitSet set) {
        return ImmutableNatSet.copyOf(set);
    }

    /** The set of {@code elements}, all naturals. */
    static NatSet copyOf(Collection<Integer> elements) {
        return ImmutableNatSet.copyOf(elements);
    }

    /** Whether {@code element} is in this set; {@code false} for a negative one. */
    boolean contains(int element);

    /** The number of elements. */
    int size();

    boolean isEmpty();

    /** Whether every element of {@code other} is in this set. */
    boolean containsAll(NatSet other);

    /** Whether some element is in both sets. */
    boolean intersects(NatSet other);

    /** The smallest element, {@code -1} if empty. */
    int first();

    /** The largest element, {@code -1} if empty. */
    int last();

    /** The smallest element, {@code defaultValue} if empty. */
    default int firstOr(int defaultValue) {
        int first = first();
        return first < 0 ? defaultValue : first;
    }

    /** The largest element, {@code defaultValue} if empty. */
    default int lastOr(int defaultValue) {
        int last = last();
        return last < 0 ? defaultValue : last;
    }

    /**
     * The smallest element at least {@code from}, {@code -1} if there is none - as {@link BitSet#nextSetBit(int)}.
     * A loop {@code nextSetBit(e + 1)} must stop after {@link Integer#MAX_VALUE}, which is a valid element.
     */
    int nextSetBit(int from);

    /**
     * The largest element at most {@code from}, {@code -1} if there is none - as {@link BitSet#previousSetBit(int)}.
     */
    int previousSetBit(int from);

    /**
     * The smallest natural at least {@code from} not in this set - as {@link BitSet#nextClearBit(int)}.
     */
    int nextClearBit(int from);

    /** One more than the largest element, {@code 0} if empty - as {@link BitSet#length()}. */
    int length();

    /**
     * The number of elements less than {@code element}: {@code subSet(0, element).size()}, i.e. the index
     * {@code element} has in ascending order - {@code 0} for a negative {@code element}.
     */
    int rank(int element);

    /** Hands each element to {@code action}, in ascending order: the fastest way over all elements. */
    void forEach(IntConsumer action);

    /** The elements in ascending order, for a walk that may stop early. */
    PrimitiveIterator.OfInt iterator();

    /** Whether some element satisfies {@code predicate}, testing in ascending order until one does. */
    boolean anyMatch(IntPredicate predicate);

    /** Whether every element satisfies {@code predicate}, testing in ascending order until one does not. */
    boolean allMatch(IntPredicate predicate);

    default boolean noneMatch(IntPredicate predicate) {
        return !anyMatch(predicate);
    }

    /** The elements in ascending order. */
    IntStream intStream();

    /** The elements in ascending order. */
    int[] toIntArray();

    /** The union. */
    NatSet union(NatSet other);

    /** The intersection. */
    NatSet intersection(NatSet other);

    /** This set without the elements of {@code other}. */
    NatSet difference(NatSet other);

    /**
     * Every element plus {@code amount} (towards 0 if negative), dropping those that would be negative. The result's
     * elements must stay ints.
     */
    NatSet shifted(int amount);

    /** The elements in {@code [from, to)}; empty if {@code to <= from}. */
    NatSet subSet(int from, int to);

    /**
     * The elements in {@code [from, to)}, each less {@code from}: {@code subSet(from, to).shifted(-from)}, as
     * {@link BitSet#get(int, int)} reads a range.
     */
    NatSet slice(int from, int to);

    /**
     * This set as a {@code Set<Integer>}, a new view: unmodifiable unless this set is a {@link MutableNatSet}, and
     * with {@link Set}'s equality and hash code.
     */
    Set<Integer> boxed();

    /** The elements as words, as {@link #valueOf} reads them, without trailing zero words. */
    long[] toLongArray();

    /** The elements as a new {@link BitSet}. */
    BitSet toBitSet();

    /** Adds the elements to {@code target} and returns it. */
    BitSet copyInto(BitSet target);

    /** Equal to any {@code NatSet} of the same elements, and to nothing else. */
    @Override
    boolean equals(Object o);

    /** A hash of the elements' words, the same for either class and representation (not {@link Set}'s). */
    @Override
    int hashCode();
}
