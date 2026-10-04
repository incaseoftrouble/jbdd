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

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Symbolic representation of a {@code Set<NatSet>}. Deliberately exposes no operation that assumes or
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
    boolean contains(NatSet valuation);

    /** Whether every element of {@code valuationSet} is also an element of this set. */
    boolean containsAll(BddSet valuationSet);

    /** The dual of {@link #containsAll(BddSet)}: whether this set is a subset of {@code other}. */
    default boolean subsetOf(BddSet other) {
        return other.containsAll(this);
    }

    /** Any element of this set, if non-empty. */
    Optional<MutableNatSet> element();

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
    BddSet exists(NatSet quantifiedVariables);

    /** Universally quantifies {@code quantifiedVariables}, i.e. an element remains iff it does for every value of them. */
    BddSet forall(NatSet quantifiedVariables);

    /** Elements in exactly one of this set and {@code other}. */
    BddSet symmetricDifference(BddSet other);

    /** Elements of this set that are not in {@code other}. */
    BddSet difference(BddSet other);

    /**
     * This set with every variable of the {@code restriction} fixed to its value there, so the result no longer
     * depends on them.
     *
     * @see BinaryDecisionDiagram#restrict(int, Cube)
     */
    BddSet restrict(Cube restriction);

    /** Agrees with this set on {@code domain}; unspecified (but canonical) elsewhere.
     *
     * @see BooleanTerminalDecisionDiagram#constrain(int, int) */
    BddSet constrain(BddSet domain);

    /** Agrees with this set on {@code domain}; unspecified elsewhere.
     *
     * @see BooleanTerminalDecisionDiagram#simplify(int, int) */
    BddSet simplify(BddSet domain);

    /** Renames variables per {@code mapping}. */
    BddSet relabelVariables(IntUnaryOperator mapping);

    /** Like {@link #relabelVariables}, but replaces each variable by an arbitrary set instead of another variable. */
    BddSet replaceVariables(IntFunction<BddSet> mapping);

    /**
     * The variable this set's outermost decision is taken on, or empty if it is constant. Together with
     * {@link #high()} and {@link #low()} this is the Shannon decomposition, which is what a structural
     * recursion over a set needs and the only thing of the representation it is told.
     */
    OptionalInt decisionVariable();

    /** This set with {@link #decisionVariable()} true. The set must not be constant. */
    BddSet high();

    /** This set with {@link #decisionVariable()} false. The set must not be constant. */
    BddSet low();

    /**
     * A cover of this set by implicants, cubes whose union is exactly this set.
     *
     * @see BinaryDecisionDiagram#implicants(int)
     */
    List<Cube> implicants();

    /**
     * All prime implicants of this set, independent of the variable order.
     *
     * @see BinaryDecisionDiagram#primeImplicants(int)
     */
    List<Cube> primeImplicants();

    /**
     * Splits this set into a map over just {@code splitVariables}, whose value at each of their valuations is
     * what this set restricts to there - a set over the remaining variables. Its {@link BddMap#inverse()}
     * partitions the valuations of {@code splitVariables} by residual. {@code destination} must belong to
     * this set's context.
     *
     * @see BddMap#split(NatSet, Values)
     */
    BddMap<BddSet> split(NatSet splitVariables, Values<BddSet> destination);

    /** The variables this set actually depends on, cached: the same immutable set on every call. */
    NatSet support();

    /** The variables actually consulted by {@link #contains(NatSet)} at {@code valuation} - a witness for
     * that specific valuation, possibly much smaller than {@link #support()}. */
    MutableNatSet supportAt(NatSet valuation);

    /** Walks elements, treating every variable outside {@code support} as "don't care" (doubling the count). */
    Cursor<NatSet> cursor(NatSet support);

    /** Counts elements the same way {@link #cursor(NatSet)} does. */
    BigInteger size(NatSet support);

    /**
     * The fraction of all valuations in this set, independent of how many variables exist. Best-effort, see {@link
     * BinaryDecisionDiagram#satisfyingFraction(int)}.
     */
    double satisfyingFraction();

    /**
     * The probability that a valuation drawn uniformly at random from {@code domain} is in this set. Best-effort, see
     * {@link BinaryDecisionDiagram#satisfyingFractionIn(int, int)}.
     *
     * @throws IllegalArgumentException if {@code domain} is empty
     */
    double satisfyingFractionIn(BddSet domain);

    /** Calls {@code consumer} once per element, treating variables outside {@code support} as "don't care". */
    void forEach(NatSet support, Consumer<? super NatSet> consumer);

    /**
     * Calls {@code action} once per path of the diagram to a true leaf, read as the cube fixing
     * {@link Cube#support()} to {@link Cube#assignment()}. The cubes are disjoint and their union
     * is this set; which cubes come out depends on the variable order. The path handed out is the walk's
     * working state - see {@link Cursor}.
     */
    void forEachPath(Consumer<? super Cube> action);

    /**
     * The shortest of the {@link #forEachPath paths}, the first of them in that order; empty for the empty set.
     *
     * @see BinaryDecisionDiagram#shortestPath(int)
     */
    Optional<Cube> shortestPath();

    /** {@code folder} over this set's diagram - see {@link BddSetFactory#fold}. */
    default <R extends @Nullable Object> R fold(Folder<R> folder) {
        return factory().fold(List.of(this), folder).get(0);
    }

    /** Like {@link #forEachPath}, stopping at the first path {@code predicate} accepts; whether one did. */
    boolean anyPathMatches(Predicate<? super Cube> predicate);

    /**
     * What {@link BddSetFactory#fold} computes per node of a set's diagram, from the results of the node's children.
     * The diagram shares a node between a function and its complement, so {@code false} is the complement of
     * {@code true}.
     *
     * @param <R> the result per node, {@code @Nullable} where the folder may return {@code null}: the children's
     *     results it is handed are its own, so with {@code R} non-null it never sees {@code null}
     */
    interface Folder<R extends @Nullable Object> {
        /** The result of {@code true}. */
        R trueValue();

        /** The result of a node deciding on {@code variable}, from the results where it holds and where not. */
        R decision(int variable, R high, R low);

        /** The result of the complement of a function whose result is {@code result}. */
        R complement(R result);
    }

    /**
     * A pre-built {@link BddSet#exists(NatSet)} over a fixed variable set, created by
     * {@link BddSetFactory#registerExists} - see {@link RegisteredOperation} for when to prefer one.
     */
    @FunctionalInterface
    interface Quantifier extends UnaryOperator<BddSet>, RegisteredOperation {
        @Override
        BddSet apply(BddSet set);
    }

    /**
     * A pre-built {@link BddSet#replaceVariables(IntFunction)} or
     * {@link BddSet#relabelVariables(IntUnaryOperator)}, created by
     * {@link BddSetFactory#registerReplaceVariables} / {@link BddSetFactory#registerRelabelVariables}.
     */
    @FunctionalInterface
    interface VariableReplacer extends UnaryOperator<BddSet>, RegisteredOperation {
        /** The replacement that changes nothing. */
        static VariableReplacer identity() {
            return IdentityReplacer.INSTANCE;
        }

        @Override
        BddSet apply(BddSet set);

        /** The single instance behind {@link #identity()}; an enum so that it stays one. */
        enum IdentityReplacer implements VariableReplacer {
            INSTANCE;

            @Override
            public BddSet apply(BddSet set) {
                return set;
            }
        }
    }
}
