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

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/** Obtained from {@link BinaryFactoryContext#bddSets()} - there's no standalone way to build one,
 * since every {@code BddSetFactory} needs a {@link Bdd} to share (see {@link DdContext}). */
public interface BddSetFactory {
    /** The empty set. */
    BddSet empty();

    /** The set of all valuations. */
    BddSet universe();

    /** The set of all valuations where {@code variable} is true. */
    BddSet var(int variable);

    /** {@link #universe()} if {@code true}, {@link #empty()} otherwise. */
    BddSet of(boolean booleanConstant);

    /** The valuations in {@code cube}: those agreeing with it on its support. */
    BddSet of(Cube cube);

    /**
     * The set of {@code expression}, a propositional expression of the caller's own type that JBDD reads through
     * {@code structure}.
     */
    <E> BddSet of(E expression, ExpressionStructure<E> structure);

    /** The union of the cubes fixing {@code support} as each of {@code valuations} assigns it. */
    default BddSet of(Iterable<NatSet> valuations, NatSet support) {
        List<Cube> cubes = new ArrayList<>();
        valuations.forEach(valuation -> cubes.add(Cube.of(valuation, support)));
        return union(cubes);
    }

    /** The valuations in any of {@code cubes}. */
    default BddSet union(Iterable<Cube> cubes) {
        BddSet result = empty();
        for (Cube cube : cubes) {
            result = result.union(of(cube));
        }
        return result;
    }

    /** The union of {@code sets}. */
    default BddSet union(BddSet... sets) {
        if (sets.length == 0) {
            return empty();
        }
        BddSet set = sets[0];
        for (int i = 1; i < sets.length; i++) {
            set = set.union(sets[i]);
        }
        return set;
    }

    /** The intersection of {@code sets}. */
    default BddSet intersection(BddSet... sets) {
        if (sets.length == 0) {
            return universe();
        }
        BddSet set = sets[0];
        for (int i = 1; i < sets.length; i++) {
            set = set.intersection(sets[i]);
        }
        return set;
    }

    /** The valuations that are in {@code then} where {@code condition} holds and in {@code otherwise}
     * elsewhere - one recursion rather than the union of two intersections. */
    BddSet ifThenElse(BddSet condition, BddSet then, BddSet otherwise);

    /**
     * {@code set}, typically of another context, rebuilt here with each variable {@code v} of it read as {@code
     * variableMapping(v)}; variables that do not exist here yet are created.
     */
    BddSet adopt(BddSet set, IntUnaryOperator variableMapping);

    /**
     * {@code folder} over the diagram below {@code roots}, its result per root: each node folded once across all of
     * them, children before their node and the high child before the low one, and the complement of a node's result
     * computed once, where a complement edge or root needs it. The folder runs inside the walk, which only reads the
     * diagram; it may build sets.
     */
    <R extends @Nullable Object> List<R> fold(List<? extends BddSet> roots, BddSet.Folder<R> folder);

    /** {@link #adopt(BddSet, IntUnaryOperator)} with every variable read as itself. */
    default BddSet adopt(BddSet set) {
        return adopt(set, IntUnaryOperator.identity());
    }

    /**
     * Keeps {@code set}'s diagram in the table for the rest of the factory's life, whether or not anything
     * still names it.
     */
    void pin(BddSet set);

    /**
     * Binds {@code quantifiedVariables} once - see {@link BddSet.Quantifier}. The set is read here and may
     * be changed afterwards.
     */
    BddSet.Quantifier registerExists(NatSet quantifiedVariables);

    /**
     * Binds {@code mapping} over {@code replacedVariables} once - see {@link BddSet.VariableReplacer}. The
     * handle replaces exactly those variables and leaves every other one alone.
     */
    BddSet.VariableReplacer registerReplaceVariables(NatSet replacedVariables, IntFunction<BddSet> mapping);

    /**
     * Binds {@code mapping} over {@code relabeledVariables} once - see {@link BddSet.VariableReplacer} and
     * {@link #registerReplaceVariables}, whose contract this shares.
     */
    BddSet.VariableReplacer registerRelabelVariables(NatSet relabeledVariables, IntUnaryOperator mapping);
}
