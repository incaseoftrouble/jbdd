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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * What a binary decision diagram can compute, representing boolean functions. Note that, together with a set of variables,
 * boolean functions also can be viewed as a <em>set</em> of assignments, namely those which satisfy the function.
 *
 * <p>Note that for the sake of performance, most required properties of the arguments are only
 * checked though {@code assert} statements. With disabled assertions, undefined behaviour might
 * occur with invalid arguments. Especially, the BDD may appear to be in a working state for a long
 * time after an invalid call.</p>
 */
// TODO AndExistsSimplify and similar (quantify + apply + simplify at the same time)
public interface BinaryDecisionDiagram extends BooleanDecisionDiagram, BooleanTerminalDecisionDiagram<BitSet, Cube> {
    /**
     * The fraction of all assignments satisfying {@code function}: {@link #countSatisfyingAssignments(int)} divided by
     * {@code 2^}{@link #numberOfVariables()}, which is the same over any set of variables containing the function's
     * support. It therefore does not change when variables are created.
     *
     * <p>The value is best-effort, computed in {@code double} rather than exactly:</p>
     * <ul>
     *   <li>it is within a relative error of about {@code d * 2^-53} of the exact fraction, {@code d} being the
     *   number of levels below the function's root - and exact over at most 53 variables;</li>
     *   <li>fractions below {@code 2^-1022} lose precision and those below {@code 2^-1074} (e.g. a cube over more
     *   than 1074 variables) underflow to {@code 0};</li>
     *   <li>fractions within {@code 2^-54} of 1 round to {@code 1}. The complement's fraction is computed as
     *   precisely as the function's own, so ask for {@code satisfyingFraction(not(function))} when the distance to 1
     *   matters.</li>
     * </ul>
     * <p>So {@code 0} and {@code 1} are returned for {@literal false} and {@literal true}, but not only for them. Use
     * {@link #countSatisfyingAssignments(int)} for an exact answer.</p>
     */
    double satisfyingFraction(int function);

    /**
     * The probability that an assignment drawn uniformly at random from {@code domain} satisfies {@code function}:
     * {@link #countSatisfyingAssignmentsIn(int, int)} divided by {@link #countSatisfyingAssignments(int)} of the
     * domain, in one pass that builds nothing. Like {@link #satisfyingFraction(int)}, it does not depend on the number
     * of variables, and it equals that for the {@literal true} domain.
     *
     * <p>Best-effort, as {@link #satisfyingFraction(int)} is:</p>
     * <ul>
     *   <li>within a relative error of about {@code d * 2^-53}, {@code d} being the number of levels below the
     *   roots - and correctly rounded over at most 53 variables;</li>
     *   <li>the probability of {@code not(function)} is computed as precisely, so ask for that when the distance to 1
     *   matters;</li>
     *   <li>precision degrades once the assignments satisfying both make up less than {@code 2^-1022} of all
     *   assignments. A domain that small as a whole is counted exactly instead, which is slow but not wrong.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code domain} is {@literal false}, which has no assignment to draw
     */
    double satisfyingFractionIn(int function, int domain);

    /**
     * Creates a new variable and returns the BDD function representing it. The implementation guarantees that
     * variables are always allocated sequentially starting from 0, i.e. {@code
     * getVariable(createVariable()) == numberOfVariables() - 1}.
     *
     * @return The node representing the new variable.
     */
    int createVariable();

    /**
     * Creates {@code count} many variables and returns their respective BDD function. The first created
     * variable is at first position of the array.
     *
     * @throws IllegalArgumentException if count is not positive.
     */
    default int[] createVariables(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive");
        }
        int[] array = new int[count];
        for (int i = 0; i < count; i++) {
            array[i] = createVariable();
        }
        return array;
    }

    /**
     * Determines whether the given boolean {@code function} represents a variable.
     *
     * @param function The function to be checked.
     * @return If the {@code function} represents a variable.
     */
    boolean isVariable(int function);

    /**
     * Determines whether the given boolean {@code function} represents a negated variable.
     *
     * @param function The function to be checked.
     * @return If the {@code function} represents a negated variable.
     */
    boolean isVariableNegated(int function);

    /**
     * Determines whether the given boolean {@code function} represents a variable or its negation.
     *
     * @param function The BDD function to be checked.
     * @return If the {@code function} represents a variable.
     */
    boolean isVariableOrNegated(int function);

    /**
     * Returns the function which represents the variable with given {@code variableNumber}. The variable
     * must already have been created.
     *
     * @param variableNumber The number of the requested variable.
     * @return The corresponding function.
     */
    int variableFunction(int variableNumber);

    /**
     * Checks whether the given boolean {@code function} evaluates to {@code true} under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the function under the given assignment.
     */
    boolean evaluate(int function, boolean[] assignment);

    /**
     * Creates the conjunction of all {@code variables}.
     *
     * @param variables
     *     The variables to build the conjunction.
     *
     * @return The conjunction of specified variables.
     */
    default int conjunction(int... variables) {
        if (variables.length == 0) {
            return trueFunction();
        }
        BitSet variableSet = new BitSet(numberOfVariables());
        for (int variable : variables) {
            variableSet.set(variable);
        }
        return conjunction(variableSet);
    }

    /**
     * Creates the conjunction of all {@code variables}.
     *
     * @param variables
     *     The variables to build the conjunction.
     *
     * @return The conjunction of specified variables.
     */
    int conjunction(BitSet variables);

    /**
     * Creates the disjunction of all {@code variables}.
     *
     * @param variables
     *     The variables to build the disjunction.
     *
     * @return The disjunction of specified variables.
     */
    default int disjunction(int... variables) {
        if (variables.length == 0) {
            return falseFunction();
        }
        BitSet variableSet = new BitSet(numberOfVariables());
        for (int variable : variables) {
            variableSet.set(variable);
        }
        return disjunction(variableSet);
    }

    /**
     * Creates the disjunction of all {@code variables}.
     *
     * @param variables
     *     The variables to build the disjunction.
     *
     * @return The disjunction of specified variables.
     */
    int disjunction(BitSet variables);

    /**
     * Constructs the <i>composition</i> of the given boolean {@code function} with the boolean functions in {@code variableNodes}.
     * Formally, if {@code function} is {@code f(x_1, x_2, ..., x_n)}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i = variableNodes[i]}.
     *
     * <p>The {@code variableNodes} array can contain less than {@code n} entries, then only the first variables are replaced.
     * Furthermore, {@code placeholder} can be used as an entry to denote "don't replace this variable" (which semantically
     * is the same as saying "replace this variable by itself"). After the call, the {@code placeholder} entries will be
     * replaced by the actual corresponding variable nodes. </p>
     *
     * @param function
     *     The function to be composed.
     * @param variableMapping
     *     The boolean functions with which each variable should be replaced.
     *
     * @return The composed function.
     */
    int compose(int function, int[] variableMapping);

    default int composeSimplify(int function, int[] variableMapping, int domain) {
        return simplify(compose(function, variableMapping), domain);
    }

    /**
     * {@code function} with every variable of the {@code restriction} fixed to its value there, so the result no
     * longer depends on them.
     *
     * @see #compose(int, int[])
     */
    int restrict(int function, Cube restriction);

    /**
     * Registers a {@code compose} operation bound to a fixed {@code variableMapping} - see
     * {@link RegisteredOperation}.
     */
    RegisteredOperation.Unary registerCompose(int[] variableMapping);

    /**
     * Like {@link #registerCompose}, but for the domain-restricted {@link #composeSimplify} form.
     */
    RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping);

    /**
     * Registers an {@code exists} operation bound to a fixed set of {@code quantifiedVariables} - see
     * {@link RegisteredOperation}. The set is read here and may be changed afterwards.
     *
     * @see #exists(int, BitSet)
     */
    RegisteredOperation.Unary registerExists(BitSet quantifiedVariables);

    /**
     * The function of the cube {@code path}: {@link Cube#support()} fixed to
     * {@link Cube#assignment()}, every other variable free. In a sense, the inverse of path enumeration.
     */
    default int of(Cube path) {
        // Held referenced throughout, the constant included: a diagram may count references on its leaves.
        int cube = reference(trueFunction());
        for (int variable = path.support.nextSetBit(0);
                variable >= 0;
                variable = path.support.nextSetBit(variable + 1)) {
            int literal = path.assignment.get(variable)
                    ? variableFunction(variable)
                    : reference(not(variableFunction(variable)));
            cube = updateWith(and(cube, literal), cube);
            if (!path.assignment.get(variable)) {
                dereference(literal);
            }
        }
        return dereference(cube);
    }

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
     * ({@code {x1, x2}} above is prime but not in it), unlike {@link #primeImplicants(int)}.
     *
     * <p>Complementing first turns this into a CNF cover: each cube of the complement is a conjunction that
     * falsifies {@code function}, so negating it gives a clause, and the clauses conjoined are
     * {@code function}.
     */
    default List<Cube> implicants(int function) {
        // TODO Native implementation
        // The memo is per call and a plain map on purpose: the whole cost of this is sharing sub-results
        // across the DAG, and an evictable cache would make that exponential rather than merely slow.
        return implicantsRecursive(function, new HashMap<>());
    }

    private List<Cube> implicantsRecursive(int function, Map<Integer, List<Cube>> memo) {
        if (function == trueFunction()) {
            return List.of(Cube.empty());
        }
        if (function == falseFunction()) {
            return List.of();
        }

        List<Cube> cached = memo.get(function);
        if (cached != null) {
            return cached;
        }

        int variable = decisionVariable(function);
        int high = highOf(function);
        int low = lowOf(function);

        List<Cube> highCubes = implicantsRecursive(high, memo);
        List<Cube> lowCubes = implicantsRecursive(low, memo);

        /* An implicant of the high cofactor implies the whole function without naming the variable exactly when
         * it implies the low cofactor as well - on the true branch it holds by assumption. Dually for the low
         * cofactor. The cubes kept without the variable can then duplicate or subsume each other and the others. */
        List<Cube> implicants = new ArrayList<>(highCubes.size() + lowCubes.size());
        Map<Integer, Boolean> impliesLow = new HashMap<>();
        for (Cube cube : highCubes) {
            impliesLow.clear();
            implicants.add(cubeImplies(cube, low, impliesLow) ? cube : cube.with(variable, true));
        }
        Map<Integer, Boolean> impliesHigh = new HashMap<>();
        for (Cube cube : lowCubes) {
            impliesHigh.clear();
            implicants.add(cubeImplies(cube, high, impliesHigh) ? cube : cube.with(variable, false));
        }
        implicants = Cube.antichain(implicants);
        memo.put(function, implicants);
        return implicants;
    }

    /** Whether every valuation of {@code cube} satisfies {@code function}: a walk along it, without building. */
    private boolean cubeImplies(Cube cube, int function, Map<Integer, Boolean> memo) {
        if (function == trueFunction()) {
            return true;
        }
        if (function == falseFunction()) {
            return false;
        }
        Boolean known = memo.get(function);
        if (known != null) {
            return known;
        }
        int variable = decisionVariable(function);
        boolean implies = cube.fixes(variable)
                ? cubeImplies(cube, cube.value(variable) ? highOf(function) : lowOf(function), memo)
                : cubeImplies(cube, highOf(function), memo) && cubeImplies(cube, lowOf(function), memo);
        memo.put(function, implies);
        return implies;
    }

    /**
     * {@code function} of {@code source}, another binary decision diagram, rebuilt in this one with each variable
     * {@code v} of it read as {@code variableMapping(v)} here, which must exist. The source is only read; the
     * result is not referenced.
     *
     * <p>One memoized pass over the source's nodes, each rebuilt as the if-then-else of its mapped variable over its
     * rebuilt children. Where that variable lies above both children in this diagram's order - an order-preserving
     * mapping between diagrams ordered alike, say - the if-then-else is a single node, so the copy is linear in the
     * source; elsewhere it restructures as far as the orders demand.
     */
    default int adopt(BinaryDecisionDiagram source, int function, IntUnaryOperator variableMapping) {
        // TODO Native implementation
        // TODO try-finally not needed, exceptions inside BDD recursion mean corruption anyway
        // Every rebuilt node stays referenced until the end, so no collection in between invalidates the memo.
        Map<Integer, Integer> adopted = new HashMap<>();
        try {
            return adoptRecursive(source, function, variableMapping, adopted);
        } finally {
            for (int rebuilt : adopted.values()) {
                dereference(rebuilt);
            }
        }
    }

    private int adoptRecursive(
            BinaryDecisionDiagram source,
            int function,
            IntUnaryOperator variableMapping,
            Map<Integer, Integer> adopted) {
        if (function == source.trueFunction()) {
            return trueFunction();
        }
        if (function == source.falseFunction()) {
            return falseFunction();
        }
        Integer known = adopted.get(function);
        if (known != null) {
            return known;
        }
        int high = adoptRecursive(source, source.highOf(function), variableMapping, adopted);
        int low = adoptRecursive(source, source.lowOf(function), variableMapping, adopted);
        int variable = variableMapping.applyAsInt(source.decisionVariable(function));
        int rebuilt = ifThenElse(variableFunction(variable), high, low);
        adopted.put(function, reference(rebuilt));
        return rebuilt;
    }

    /**
     * All prime implicants of {@code function}: the cubes that imply it and stop doing so when any one literal is
     * dropped. Their disjunction is {@code function}, and unlike {@link #implicants(int)} the result does not
     * depend on the diagram - it is the Blake canonical form, the same set under any variable order (listed in an
     * order that does depend on it). There can be exponentially many.
     *
     * <p>{@code true} yields the empty cube only, {@code false} nothing.
     */
    default List<Cube> primeImplicants(int function) {
        // TODO Native implementation
        // TODO try-finally not needed, exceptions inside BDD recursion mean corruption anyway
        // Both memos are per call and plain maps, as in implicants. Every conjunction of cofactors stays referenced
        // until the end, so that no memoized id is collected and reused for another function in between.
        List<Integer> conjunctions = new ArrayList<>();
        reference(function);
        try {
            return primeImplicantsRecursive(function, new HashMap<>(), new HashMap<>(), conjunctions);
        } finally {
            for (int conjunction : conjunctions) {
                dereference(conjunction);
            }
            dereference(function);
        }
    }

    private List<Cube> primeImplicantsRecursive(
            int function, Map<Integer, List<Cube>> memo, Map<Long, Integer> conjunctionMemo, List<Integer> held) {
        if (function == trueFunction()) {
            return List.of(Cube.empty());
        }
        if (function == falseFunction()) {
            return List.of();
        }

        List<Cube> cached = memo.get(function);
        if (cached != null) {
            return cached;
        }

        int variable = decisionVariable(function);
        int high = highOf(function);
        int low = lowOf(function);

        /* A prime without the variable implies both cofactors, so it is a prime of their conjunction, and every
         * prime of the conjunction is one of the function. A prime with the variable positive is the variable and a
         * prime of the high cofactor that does not imply the low one - and a prime of the high cofactor implies the
         * low one exactly when it is a prime of the conjunction. Dually for the negative literal. */
        long pair = ((long) high << Integer.SIZE) | Integer.toUnsignedLong(low);
        Integer both = conjunctionMemo.get(pair);
        if (both == null) {
            both = reference(and(high, low));
            held.add(both);
            conjunctionMemo.put(pair, both);
        }
        List<Cube> shared = primeImplicantsRecursive(both, memo, conjunctionMemo, held);
        List<Cube> highPrimes = primeImplicantsRecursive(high, memo, conjunctionMemo, held);
        List<Cube> lowPrimes = primeImplicantsRecursive(low, memo, conjunctionMemo, held);

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
    default Optional<Cube> shortestPath(int function) {
        // TODO Native implementation
        if (function == falseFunction()) {
            return Optional.empty();
        }
        // Exact lengths as themselves, the lower bound of a subtree left early as its negation.
        Map<Integer, Integer> lengths = new HashMap<>();
        int length = shortestPathLength(function, Integer.MAX_VALUE, lengths);

        BitSet assignment = new BitSet();
        BitSet support = new BitSet();
        int node = function;
        for (int remaining = length; remaining > 0; remaining--) {
            int variable = decisionVariable(node);
            support.set(variable);
            int low = lowOf(node);
            // Low first, as forEachPath: every child on a shortest path got its exact length.
            if (exactShortestPathLength(low, lengths) == remaining - 1) {
                node = low;
            } else {
                assignment.set(variable);
                node = highOf(node);
                assert exactShortestPathLength(node, lengths) == remaining - 1;
            }
        }
        assert node == trueFunction();
        return Optional.of(Cube.of(assignment, support));
    }

    /** The length of the shortest path of {@code function} if below {@code bound}, else a lower bound of at least it. */
    private int shortestPathLength(int function, int bound, Map<Integer, Integer> lengths) {
        if (function == trueFunction()) {
            return 0;
        }
        if (function == falseFunction()) {
            return Integer.MAX_VALUE;
        }
        Integer known = lengths.get(function);
        if (known != null && (known > 0 || -known >= bound)) {
            return Math.abs(known);
        }
        if (bound <= 1) {
            // Every decision node is at least one decision away from true.
            return 1;
        }

        int low = shortestPathLength(lowOf(function), bound - 1, lengths);
        int best = low == Integer.MAX_VALUE ? low : low + 1;
        int high = shortestPathLength(highOf(function), Math.min(bound, best) - 1, lengths);
        int length = Math.min(best, high == Integer.MAX_VALUE ? high : high + 1);
        // Below the bound, both children were searched far enough for this to be exact; otherwise each is a
        // lower bound and so is their minimum.
        lengths.put(function, length < bound ? length : -length);
        return length;
    }

    private int exactShortestPathLength(int function, Map<Integer, Integer> lengths) {
        if (function == trueFunction()) {
            return 0;
        }
        Integer known = lengths.get(function);
        return known == null || known < 0 ? -1 : known;
    }
}
