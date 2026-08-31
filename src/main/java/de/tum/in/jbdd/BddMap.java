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

import java.util.BitSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Symbolic representation of a {@code Map<BitSet, V>} (total - every valuation has some value), backed by
 * an {@link MtBdd}.
 */
public interface BddMap<V> {
    BddMapFactory<V> factory();

    /** The value at the given valuation. */
    V evaluate(BitSet assignment);

    /** The variables this map's value actually depends on. */
    BitSet support();

    /** The variables actually consulted by {@link #evaluate} at {@code assignment} - a witness for that
     * specific valuation, possibly much smaller than {@link #support()}. */
    BitSet supportAt(BitSet assignment);

    /** Every value this map actually takes. */
    Set<V> values();

    /** Whether this map takes only a single value. */
    default boolean isConstant() {
        return values().size() == 1;
    }

    /** The set of valuations mapping to {@code value}. */
    BddSet domainOf(V value);

    /** The set of valuations whose value matches {@code predicate}. */
    BddSet where(Predicate<V> predicate);

    /** Is {@code value} wherever {@code domain} holds, and agrees with this map elsewhere. */
    BddMap<V> update(BddSet domain, V value);

    /** Combines this map and {@code other} point-wise via {@code combiner}. */
    default BddMap<V> apply(BddMap<V> other, BinaryOperator<V> combiner) {
        return apply(other, combiner, factory());
    }

    /**
     * Like {@link #apply(BddMap, BinaryOperator)}, but lets {@code operator} declare algebraic properties
     * (commutative/neutral/absorbing) the underlying {@link MtBdd} apply can exploit - see {@link
     * BddMapBinaryOperator}. {@code other} must share this map's {@link #factory()}.
     */
    BddMap<V> apply(BddMap<V> other, BddMapBinaryOperator<V> operator);

    /**
     * Combines this map and {@code other} (possibly value-typed differently) point-wise via {@code
     * combiner}, landing the result in {@code destination}.
     */
    <W, O> BddMap<O> apply(BddMap<W> other, BiFunction<V, W, O> combiner, BddMapFactory<O> destination);

    /** Transforms every value via {@code function}, staying within this map's own {@link #factory()}. Not
     * assumed cheap - if {@code function} is known to be injective, use {@link
     * BddMapFactory#createRelabeling} instead, which is. */
    default BddMap<V> map(UnaryOperator<V> function) {
        return map(function, factory());
    }

    /**
     * Transforms every value via {@code function}, landing the result in {@code destination}. This builds
     * a new MTBDD structure; if {@code function} is known to be injective, use {@link BddMapFactory#createRelabeling}
     * instead.
     */
    <O> BddMap<O> map(Function<V, O> function, BddMapFactory<O> destination);

    /** The set of valuations on which this map and {@code other} agree. */
    BddSet agreement(BddMap<V> other);

    /** The set of valuations on which this map and {@code other} disagree - the complement of {@link
     * #agreement}. */
    default BddSet difference(BddMap<V> other) {
        return agreement(other).complement();
    }

    BddMap<V> restrict(BitSet restrictedVariables, BitSet restrictedVariableValues);

    /** Substitutes each variable by the given predicate (see {@link Bdd#compose}); {@code null} leaves a variable untouched. */
    BddMap<V> replaceVariables(@Nullable BddSet[] variableMapping);

    /** Renames variables per {@code mapping} - see {@link BddSet#relabelVariables}. */
    BddMap<V> relabelVariables(IntUnaryOperator mapping);

    /** Agrees with this map on {@code domain}; unspecified (but canonical) elsewhere - see {@link MtBdd#constrain}. */
    BddMap<V> constrain(BddSet domain);

    /** Agrees with this map on {@code domain}; unspecified elsewhere - see {@link MtBdd#simplify}. */
    BddMap<V> simplify(BddSet domain);

    /**
     * Splits this map into a meta-map over just {@code splitVariables}, whose value at each valuation of
     * those variables is the residual map over the remaining variables - see {@link MtBdd#split}.
     */
    BddMap<BddMap<V>> split(BitSet splitVariables, BddMapFactory<BddMap<V>> destination);

    interface Relabeler<V, O> {
        /** The destination factory every {@link #relabel} call targets. */
        BddMapFactory<O> into();

        BddMap<O> relabel(BddMap<V> map);
    }
}
