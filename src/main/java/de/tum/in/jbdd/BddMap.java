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
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Symbolic representation of a {@code Map<BitSet, V>} (total - every valuation has some value), backed by
 * an {@link MtBdd}.
 */
public interface BddMap<V> {
    /** The factory backing this map - one per {@link BinaryFactoryContext}. */
    BddMapFactory factory();

    /** The value numbering this map is built over. Two maps over the same domain offer fast, "native"
     * operations, such as O(1) equality check, etc. */
    Values<V> valueDomain();

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
    BddSet where(Predicate<? super V> predicate);

    /** Is {@code value} wherever {@code domain} holds, and agrees with this map elsewhere. */
    BddMap<V> update(BddSet domain, V value);

    /** Combines this map and {@code other} point-wise via {@code combiner}. */
    default BddMap<V> apply(BddMap<V> other, BiFunction<? super V, ? super V, ? extends V> combiner) {
        return apply(other, combiner, valueDomain());
    }

    /**
     * Like {@link #apply(BddMap, BiFunction)}, but lets {@code operator} declare algebraic properties
     * (commutative/neutral/absorbing) the underlying {@link MtBdd} apply can exploit - see {@link
     * BddMapBinaryOperator}. {@code other} must share this map's {@link #valueDomain()}.
     */
    BddMap<V> apply(BddMap<V> other, BddMapBinaryOperator<V> operator);

    /**
     * Combines this map and {@code other} (possibly value-typed differently) point-wise via {@code
     * combiner}, landing the result in {@code destination}.
     */
    <W, O> BddMap<O> apply(
            BddMap<W> other, BiFunction<? super V, ? super W, ? extends O> combiner, Values<O> destination);

    /** Transforms every value via {@code function}, staying within this map's own {@link #valueDomain()}.
     * To do so, the entire tree needs to be traversed, to detect if two values are merged into one.
     * If {@code function} is known to be injective, use {@link Values#createRelabeling} instead, which is
     * much faster. */
    default BddMap<V> map(Function<? super V, ? extends V> function) {
        return map(function, valueDomain());
    }

    /**
     * Transforms every value via {@code function}, landing the result in {@code destination}. This builds
     * a new MTBDD structure; if {@code function} is known to be injective, use {@link Values#createRelabeling}
     * instead.
     */
    <O> BddMap<O> map(Function<? super V, ? extends O> function, Values<O> destination);

    /** The set of valuations on which this map and {@code other} agree. {@code other} must share this
     * map's {@link #valueDomain()}. */
    BddSet agreement(BddMap<V> other);

    /** The set of valuations on which this map and {@code other} disagree - the complement of {@link
     * #agreement}. {@code other} must share this map's {@link #valueDomain()}. */
    default BddSet difference(BddMap<V> other) {
        return agreement(other).complement();
    }

    BddMap<V> restrict(BitSet restrictedVariables, BitSet restrictedVariableValues);

    /** Substitutes each variable by the given predicate; {@code null} leaves a variable untouched.
     *
     * @see Bdd#compose(int, int[]) */
    BddMap<V> replaceVariables(@Nullable BddSet[] variableMapping);

    /** Renames variables per {@code mapping}.
     *
     * @see BddSet#relabelVariables(IntUnaryOperator)  */
    BddMap<V> relabelVariables(IntUnaryOperator mapping);

    /** Agrees with this map on {@code domain}; unspecified (but canonical) elsewhere.
     *
     * @see MtBdd#constrain(int, int) */
    BddMap<V> constrain(BddSet domain);

    /** Agrees with this map on {@code domain}; unspecified elsewhere.
     *
     * @see MtBdd#simplify(int, int) . */
    BddMap<V> simplify(BddSet domain);

    /**
     * Splits this map into a meta-map over just {@code splitVariables}, whose value at each valuation of
     * those variables is the residual map over the remaining variables.
     *
     * @see MtBdd#split(int, BitSet)
     */
    BddMap<BddMap<V>> split(BitSet splitVariables, Values<BddMap<V>> destination);

    interface Relabeler<V, O> {
        /** The destination every {@link #relabel} call targets. */
        Values<O> into();

        BddMap<O> relabel(BddMap<V> map);
    }
}
