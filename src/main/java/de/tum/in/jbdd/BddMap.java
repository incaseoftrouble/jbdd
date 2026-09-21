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
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Symbolic representation of a {@code Function<BitSet, V>} (total over the domain - every valuation has some value),
 * backed by an {@link MtBdd}.
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

    /** Every value this map actually takes (i.e. its image). */
    Set<V> values();

    /** Whether this map takes only a single value. */
    boolean isConstant();

    /** The set of valuations mapping to {@code value}. */
    BddSet domainOf(V value);

    /** The set of valuations whose value matches {@code predicate}. */
    BddSet where(Predicate<? super V> predicate);

    /**
     * The set of valuations on which this map's value and {@code other}'s satisfy {@code predicate}.
     *
     * <p>Note: For equality, see {@link #agreement(BddMap)}</p>
     */
    <W> BddSet where(BddMap<W> other, BiPredicate<? super V, ? super W> predicate);

    /**
     * Like {@link #where(BddMap, BiPredicate)}, but lets {@code predicate} declare properties
     * (symmetric/reflexive) the traversal can exploit - see {@link BddMapBinaryPredicate}.
     */
    <W extends V> BddSet where(BddMap<W> other, BddMapBinaryPredicate<? super V> predicate);

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
     *
     * <p>In general, the entire tree needs to be traversed, to detect if two values are merged into one.
     * If {@code function} is known to be injective, use {@link Values#createRelabeling} instead, which is
     * much faster.</p> */
    default BddMap<V> map(Function<? super V, ? extends V> function) {
        return map(function, valueDomain());
    }

    /**
     * Transforms every value via {@code function}, landing the result in {@code destination}. This builds
     * a new MTBDD structure; if {@code function} is known to be injective, use {@link Values#createRelabeling}
     * instead.
     */
    <O> BddMap<O> map(Function<? super V, ? extends O> function, Values<O> destination);

    /**
     * The set of valuations on which this map and {@code other} agree. Note that {@code other}
     * does <em>not</em> need to be over the same {@link #valueDomain()}.
     */
    default BddSet agreement(BddMap<? extends V> other) {
        return where(other, BddMapBinaryPredicate.equality());
    }

    /** The set of valuations on which this map and {@code other} disagree - the complement of {@link
     * #agreement}. */
    default BddSet difference(BddMap<? extends V> other) {
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

    /**
     * A value transform applying to every map over one numbering at once, created by
     * {@link Values#createRelabeling}.
     */
    interface Relabeler<V, O> {
        /** The destination every {@link #relabel} call targets. */
        Values<O> into();

        /** {@code map}, with the registered injection applied to each of its values. */
        BddMap<O> relabel(BddMap<V> map);
    }

    /**
     * A pre-built {@link BddMap#apply(BddMap, BddMapBinaryOperator)} over one numbering, created by
     * {@link Values#registerApply} - see {@link RegisteredOperation} for when to prefer one.
     */
    @FunctionalInterface
    interface Operator<V> extends BinaryOperator<BddMap<V>>, RegisteredOperation {
        /** {@code left.apply(right, operator)}; both maps must be over the registered numbering. */
        @Override
        BddMap<V> apply(BddMap<V> left, BddMap<V> right);

        /**
         * A map agreeing with {@link #apply} on {@code domain} and unspecified elsewhere - the
         * {@link BddMap#simplify} form of this operation.
         */
        default BddMap<V> applyIn(BddMap<V> left, BddMap<V> right, BddSet domain) {
            return apply(left, right).simplify(domain);
        }
    }

    /**
     * A pre-built {@link BddMap#replaceVariables(BddSet[])}, created by
     * {@link BddMapFactory#registerReplaceVariables}. Replacing variables never looks at a terminal, so one
     * instance serves maps over any numbering and each result stays over the numbering its operand came
     * from.
     */
    @SuppressWarnings("PMD.ImplicitFunctionalInterface")
    interface VariableReplacer extends RegisteredOperation {
        <V> BddMap<V> replace(BddMap<V> map);

        /** As {@link Operator#applyIn}, the {@link BddMap#simplify} form of this operation. */
        default <V> BddMap<V> replaceIn(BddMap<V> map, BddSet domain) {
            return replace(map).simplify(domain);
        }
    }

    /**
     * A pre-built {@link BddMap#map(Function, Values)} into a fixed destination, created by
     * {@link Values#registerMap}.
     */
    @FunctionalInterface
    interface Mapper<V, O> extends Function<BddMap<V>, BddMap<O>>, RegisteredOperation {
        /** {@code map.map(function, destination)}; {@code map} must be over the registered numbering. */
        @Override
        BddMap<O> apply(BddMap<V> map);
    }

    /**
     * A pre-built {@link BddMap#apply(BddMap, BiFunction, Values)} - the cross-numbering combination -
     * created by {@link Values#registerCombine}: both operand numberings and the destination are fixed at
     * registration.
     */
    @FunctionalInterface
    interface Combiner<V, W, O> extends BiFunction<BddMap<V>, BddMap<W>, BddMap<O>>, RegisteredOperation {
        /** {@code left.apply(right, combiner, destination)}; each map must be over its registered numbering. */
        @Override
        BddMap<O> apply(BddMap<V> left, BddMap<W> right);
    }

    /** A pre-built {@link BddMap#where(Predicate)}, created by {@link Values#registerWhere(Predicate)}. */
    @FunctionalInterface
    interface Selector<V> extends Function<BddMap<V>, BddSet>, RegisteredOperation {
        /** The valuations where the registered predicate holds of {@code map}'s value. */
        @Override
        BddSet apply(BddMap<V> map);
    }

    /**
     * A pre-built {@link BddMap#where(BddMap, BddMapBinaryPredicate)}, created by
     * {@link Values#registerWhere(BddMapBinaryPredicate)}.
     */
    @FunctionalInterface
    interface Relation<V> extends BiFunction<BddMap<V>, BddMap<V>, BddSet>, RegisteredOperation {
        /** The valuations where the registered predicate holds of the two maps' values. */
        @Override
        BddSet apply(BddMap<V> left, BddMap<V> right);
    }
}
