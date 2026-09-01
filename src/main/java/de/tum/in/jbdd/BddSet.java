/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2018-2023 Tobias Meggendorfer.
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

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Symbolic representation of a {@code Set<BitSet>}. Deliberately exposes no operation that assumes or
 * reveals a fixed variable universe (e.g. no size/iteration without an explicit {@code support}, no
 * structural/node-count introspection) - callers must always be explicit about which variables they mean,
 * so no code accidentally depends on how many variables happen to exist.
 */
public interface BddSet {
    /** The factory this set was created by. */
    BddSetFactory factory();

    /** Whether this set has no elements. */
    boolean isEmpty();

    /** Whether this set contains every valuation. */
    boolean isUniverse();

    /** Whether {@code valuation} is an element of this set. */
    boolean contains(BitSet valuation);

    /** Whether every element of {@code valuationSet} is also an element of this set. */
    boolean containsAll(BddSet valuationSet);

    /** The dual of {@link #containsAll(BddSet)}: whether this set is a subset of {@code other}. */
    default boolean subsetOf(BddSet other) {
        return other.containsAll(this);
    }

    /** Any element of this set, if non-empty. */
    Optional<BitSet> element();

    /** The complement, i.e. every valuation not in this set. */
    BddSet complement();

    BddSet union(BddSet other);

    default BddSet union(BddSet... bddSets) {
        BddSet result = this;
        for (BddSet bddSet : bddSets) {
            result = result.union(bddSet);
        }
        return result;
    }

    /** Whether this set and {@code other} share an element. */
    boolean intersects(BddSet other);

    BddSet intersection(BddSet other);

    default BddSet intersection(BddSet... bddSets) {
        BddSet result = this;
        for (BddSet bddSet : bddSets) {
            result = result.intersection(bddSet);
        }
        return result;
    }

    /** Projects out {@code quantifiedVariables}, i.e. an element remains iff some value for them exists. */
    BddSet exists(BitSet quantifiedVariables);

    /** Elements in exactly one of this set and {@code other}. */
    BddSet symmetricDifference(BddSet other);

    /** Elements of this set that are not in {@code other}. */
    BddSet difference(BddSet other);

    /** Renames variables per {@code mapping}. */
    BddSet relabelVariables(IntUnaryOperator mapping);

    /** Like {@link #relabelVariables}, but replaces each variable by an arbitrary set instead of another variable. */
    BddSet replaceVariables(IntFunction<BddSet> mapping);

    /** The variables this set actually depends on. */
    BitSet support();

    /** The variables actually consulted by {@link #contains(BitSet)} at {@code valuation} - a witness for
     * that specific valuation, possibly much smaller than {@link #support()}. */
    BitSet supportAt(BitSet valuation);

    /** Walks elements, treating every variable outside {@code support} as "don't care" (doubling the count). */
    Cursor<BitSet> cursor(BitSet support);

    /** Counts elements the same way {@link #cursor(BitSet)} does. */
    BigInteger size(BitSet support);

    /** Calls {@code consumer} once per element, treating variables outside {@code support} as "don't care". */
    void forEach(BitSet support, Consumer<? super BitSet> consumer);
}
