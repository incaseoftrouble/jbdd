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

import de.tum.in.jbdd.collections.IntIntHashMap;
import de.tum.in.jbdd.collections.IntObjectHashMap;
import de.tum.in.jbdd.collections.MutableNatSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Computations on a {@link BinaryDecisionDiagram} built from its public operations alone: they work on any
 * implementation, and none has a native counterpart.
 */
public final class BddUtil {
    private BddUtil() {}

    /**
     * A cover of {@code function} by implicants: cubes whose disjunction is exactly {@code function}, where
     * a {@link Cube} is read as the cube fixing {@link Cube#support()} to
     * {@link Cube#assignment()} and leaving every other variable free.
     *
     * <p>Guaranteed: every cube implies {@code function}, their disjunction is {@code function} exactly, and
     * no cube's literals are a superset of another's - a longer cube saying the same thing is dropped. The
     * cubes are <em>not</em> guaranteed to be prime, nor to be the smallest such cover; which cover comes out
     * depends on the diagram, so it is deterministic for a given variable order but not preserved by
     * reordering. {@code true} yields one cube, the empty one; {@code false} yields nothing.
     *
     * <p>A cube of one cofactor records the decision variable only where it needs it - where it does not imply
     * the other cofactor already - which is what makes the cubes shorter than the paths through the diagram:
     * {@code x0 | x1} comes out as {@code {x0}, {x1}} rather than as the three paths that reach a true leaf,
     * {@code x0 ^ x1} keeps both literals and comes out as {@code {x0, !x1}, {!x0, x1}}, and
     * {@code (x0 & x1) | (!x0 & x2) | x3} as {@code {x0, x1}, {!x0, x2}, {x3}}. Each cube is a cube of a
     * cofactor with or without the variable, never a combination of cubes of both: the cover has no resolvents
     * ({@code {x1, x2}} above is prime but not in it), unlike {@link #primeImplicants(BinaryDecisionDiagram, int)}.
     *
     * <p>Complementing first turns this into a CNF cover: each cube of the complement is a conjunction that
     * falsifies {@code function}, so negating it gives a clause, and the clauses conjoined are
     * {@code function}.
     */
    public static List<Cube> implicants(BinaryDecisionDiagram bdd, int function) {
        // The memo is per call and a plain map on purpose: the whole cost of this is sharing sub-results
        // across the DAG, and an evictable cache would make that exponential rather than merely slow.
        return implicantsRecursive(bdd, function, new IntObjectHashMap<>());
    }

    private static List<Cube> implicantsRecursive(
            BinaryDecisionDiagram bdd, int function, IntObjectHashMap<List<Cube>> memo) {
        if (function == bdd.trueFunction()) {
            return List.of(Cube.empty());
        }
        if (function == bdd.falseFunction()) {
            return List.of();
        }

        List<Cube> cached = memo.get(function);
        if (cached != null) {
            return cached;
        }

        int variable = bdd.decisionVariable(function);
        int high = bdd.highOf(function);
        int low = bdd.lowOf(function);

        List<Cube> highCubes = implicantsRecursive(bdd, high, memo);
        List<Cube> lowCubes = implicantsRecursive(bdd, low, memo);

        /* An implicant of the high cofactor implies the whole function without naming the variable exactly when
         * it implies the low cofactor as well - on the true branch it holds by assumption. Dually for the low
         * cofactor. The cubes kept without the variable can then duplicate or subsume each other and the others. */
        List<Cube> implicants = new ArrayList<>(highCubes.size() + lowCubes.size());
        IntIntHashMap impliesLow = new IntIntHashMap();
        for (Cube cube : highCubes) {
            impliesLow.clear();
            implicants.add(cubeImplies(bdd, cube, low, impliesLow) ? cube : cube.with(variable, true));
        }
        IntIntHashMap impliesHigh = new IntIntHashMap();
        for (Cube cube : lowCubes) {
            impliesHigh.clear();
            implicants.add(cubeImplies(bdd, cube, high, impliesHigh) ? cube : cube.with(variable, false));
        }
        implicants = Cube.antichain(implicants);
        memo.put(function, implicants);
        return implicants;
    }

    /** Whether every valuation of {@code cube} satisfies {@code function}: a walk along it, without building. */
    private static boolean cubeImplies(BinaryDecisionDiagram bdd, Cube cube, int function, IntIntHashMap memo) {
        if (function == bdd.trueFunction()) {
            return true;
        }
        if (function == bdd.falseFunction()) {
            return false;
        }
        // 1 for implied, 0 for not.
        int known = memo.get(function, -1);
        if (known >= 0) {
            return known == 1;
        }
        int variable = bdd.decisionVariable(function);
        boolean implies = cube.fixes(variable)
                ? cubeImplies(bdd, cube, cube.value(variable) ? bdd.highOf(function) : bdd.lowOf(function), memo)
                : cubeImplies(bdd, cube, bdd.highOf(function), memo)
                        && cubeImplies(bdd, cube, bdd.lowOf(function), memo);
        memo.put(function, implies ? 1 : 0);
        return implies;
    }

    /**
     * All prime implicants of {@code function}: the cubes that imply it and stop doing so when any one literal is
     * dropped. Their disjunction is {@code function}, and unlike {@link #implicants(BinaryDecisionDiagram, int)} the result does not
     * depend on the diagram - it is the Blake canonical form, the same set under any variable order (listed in an
     * order that does depend on it). There can be exponentially many.
     *
     * <p>{@code true} yields the empty cube only, {@code false} nothing.
     */
    public static List<Cube> primeImplicants(BinaryDecisionDiagram bdd, int function) {
        // Both memos are per call and plain maps, as in implicants. Every conjunction of cofactors stays referenced
        // until the end, so that no memoized id is collected and reused for another function in between.
        List<Integer> conjunctions = new ArrayList<>();
        bdd.reference(function);
        List<Cube> primes =
                primeImplicantsRecursive(bdd, function, new IntObjectHashMap<>(), new HashMap<>(), conjunctions);
        for (int conjunction : conjunctions) {
            bdd.dereference(conjunction);
        }
        bdd.dereference(function);
        return primes;
    }

    private static List<Cube> primeImplicantsRecursive(
            BinaryDecisionDiagram bdd,
            int function,
            IntObjectHashMap<List<Cube>> memo,
            Map<Long, Integer> conjunctionMemo,
            List<Integer> held) {
        if (function == bdd.trueFunction()) {
            return List.of(Cube.empty());
        }
        if (function == bdd.falseFunction()) {
            return List.of();
        }

        List<Cube> cached = memo.get(function);
        if (cached != null) {
            return cached;
        }

        int variable = bdd.decisionVariable(function);
        int high = bdd.highOf(function);
        int low = bdd.lowOf(function);

        /* A prime without the variable implies both cofactors, so it is a prime of their conjunction, and every
         * prime of the conjunction is one of the function. A prime with the variable positive is the variable and a
         * prime of the high cofactor that does not imply the low one - and a prime of the high cofactor implies the
         * low one exactly when it is a prime of the conjunction. Dually for the negative literal. */
        long pair = ((long) high << Integer.SIZE) | Integer.toUnsignedLong(low);
        Integer both = conjunctionMemo.get(pair);
        if (both == null) {
            both = bdd.reference(bdd.and(high, low));
            held.add(both);
            conjunctionMemo.put(pair, both);
        }
        List<Cube> shared = primeImplicantsRecursive(bdd, both, memo, conjunctionMemo, held);
        List<Cube> highPrimes = primeImplicantsRecursive(bdd, high, memo, conjunctionMemo, held);
        List<Cube> lowPrimes = primeImplicantsRecursive(bdd, low, memo, conjunctionMemo, held);

        Set<Cube> sharedSet = new HashSet<>(shared);
        List<Cube> primes = new ArrayList<>(shared);
        for (Cube cube : highPrimes) {
            if (!sharedSet.contains(cube)) {
                primes.add(cube.with(variable, true));
            }
        }
        for (Cube cube : lowPrimes) {
            if (!sharedSet.contains(cube)) {
                primes.add(cube.with(variable, false));
            }
        }
        primes = List.copyOf(primes);
        memo.put(function, primes);
        return primes;
    }

    /**
     * A path of {@code function} to true with the fewest decisions, as the cube fixing the variables it decides:
     * the first such path in {@code forEachPath} order (low before high), so of equally short paths, the one a
     * walk keeping the first shortest path it meets would keep. Empty for {@code false}; {@code true} yields the
     * empty cube.
     *
     * <p>A memoized recursion over the diagram, bounded by the shortest path found so far: a subtree that cannot
     * beat it is left early, and what that showed about it is remembered as a lower bound.
     */
    public static Optional<Cube> shortestPath(BinaryDecisionDiagram bdd, int function) {
        if (function == bdd.falseFunction()) {
            return Optional.empty();
        }
        // Exact lengths as themselves, the lower bound of a subtree left early as its negation.
        IntIntHashMap lengths = new IntIntHashMap();
        int length = shortestPathLength(bdd, function, Integer.MAX_VALUE, lengths);

        MutableNatSet assignment = MutableNatSet.create();
        MutableNatSet support = MutableNatSet.create();
        int node = function;
        for (int remaining = length; remaining > 0; remaining--) {
            int variable = bdd.decisionVariable(node);
            support.set(variable);
            int low = bdd.lowOf(node);
            // Low first, as forEachPath: every child on a shortest path got its exact length.
            if (exactShortestPathLength(bdd, low, lengths) == remaining - 1) {
                node = low;
            } else {
                assignment.set(variable);
                node = bdd.highOf(node);
                assert exactShortestPathLength(bdd, node, lengths) == remaining - 1;
            }
        }
        assert node == bdd.trueFunction();
        return Optional.of(Cube.of(assignment, support));
    }

    /** The length of the shortest path of {@code function} if below {@code bound}, else a lower bound of at least it. */
    private static int shortestPathLength(BinaryDecisionDiagram bdd, int function, int bound, IntIntHashMap lengths) {
        if (function == bdd.trueFunction()) {
            return 0;
        }
        if (function == bdd.falseFunction()) {
            return Integer.MAX_VALUE;
        }
        int known = lengths.get(function, Integer.MIN_VALUE);
        if (known != Integer.MIN_VALUE && (known > 0 || -known >= bound)) {
            return Math.abs(known);
        }
        if (bound <= 1) {
            // Every decision node is at least one decision away from true.
            return 1;
        }

        int low = shortestPathLength(bdd, bdd.lowOf(function), bound - 1, lengths);
        int best = low == Integer.MAX_VALUE ? low : low + 1;
        int high = shortestPathLength(bdd, bdd.highOf(function), Math.min(bound, best) - 1, lengths);
        int length = Math.min(best, high == Integer.MAX_VALUE ? high : high + 1);
        // Below the bound, both children were searched far enough for this to be exact; otherwise each is a
        // lower bound and so is their minimum.
        lengths.put(function, length < bound ? length : -length);
        return length;
    }

    private static int exactShortestPathLength(BinaryDecisionDiagram bdd, int function, IntIntHashMap lengths) {
        if (function == bdd.trueFunction()) {
            return 0;
        }
        int known = lengths.get(function, -1);
        return Math.max(known, -1);
    }

    /**
     * {@link BinaryDecisionDiagram#adopt} through the interface alone, for a diagram of which neither side is
     * native: each source node rebuilt as the if-then-else of its mapped variable over its rebuilt children.
     */
    public static int adopt(
            BinaryDecisionDiagram bdd, BinaryDecisionDiagram source, int function, IntUnaryOperator variableMapping) {
        // Every rebuilt node stays referenced until the end, so no collection in between invalidates the memo.
        IntIntHashMap adopted = new IntIntHashMap();
        int result = adoptRecursive(bdd, source, function, variableMapping, adopted);
        adopted.forEach((node, rebuilt) -> bdd.dereference(rebuilt));
        return result;
    }

    private static int adoptRecursive(
            BinaryDecisionDiagram bdd,
            BinaryDecisionDiagram source,
            int function,
            IntUnaryOperator variableMapping,
            IntIntHashMap adopted) {
        if (function == source.trueFunction()) {
            return bdd.trueFunction();
        }
        if (function == source.falseFunction()) {
            return bdd.falseFunction();
        }
        int known = adopted.get(function, bdd.placeholder());
        if (known != bdd.placeholder()) {
            return known;
        }
        int high = adoptRecursive(bdd, source, source.highOf(function), variableMapping, adopted);
        int low = adoptRecursive(bdd, source, source.lowOf(function), variableMapping, adopted);
        int variable = variableMapping.applyAsInt(source.decisionVariable(function));
        int rebuilt = bdd.ifThenElse(bdd.variableFunction(variable), high, low);
        adopted.put(function, bdd.reference(rebuilt));
        return rebuilt;
    }
}
