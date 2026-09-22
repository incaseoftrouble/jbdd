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
import java.util.List;
import java.util.Map;

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
     * <p>A literal is recorded only where {@code function} is genuinely not monotone in that variable, which
     * is what makes the cubes shorter than the paths through the diagram: {@code x0 | x1} comes out as
     * {@code {x0}, {x1}} rather than as the three paths that reach a true leaf, while {@code x0 ^ x1} keeps
     * both literals and comes out as {@code {x0, !x1}, {!x0, x1}}.
     *
     * <p>Complementing first turns this into a CNF cover: each cube of the complement is a conjunction that
     * falsifies {@code function}, so negating it gives a clause, and the clauses conjoined are
     * {@code function}.
     */
    default List<Cube> implicants(int function) {
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

        /* An implicant of the high cofactor implies the whole function without naming the variable
         * exactly when high implies low - on the true branch it holds by assumption, on the false branch
         * it follows. Dually for the low cofactor. So a literal is recorded only where the function is
         * genuinely not monotone in that variable here, which is what keeps the cubes short.
         *
         * Each side's cubes are an antichain already, and a cube carrying a literal on the variable never
         * subsumes one lacking it. So only a cube of a side that gets the literal can be subsumed, and only
         * by a cube of the other side - which must then lack the literal itself. */
        boolean highNeedsLiteral = !implies(high, low);
        boolean lowNeedsLiteral = !implies(low, high);
        assert highNeedsLiteral || lowNeedsLiteral : "Both cofactors equal";
        List<Cube> implicants = new ArrayList<>(highCubes.size() + lowCubes.size());
        for (Cube cube : highCubes) {
            if (!highNeedsLiteral) {
                implicants.add(cube);
            } else if (lowNeedsLiteral || lowCubes.stream().noneMatch(cube::implies)) {
                implicants.add(cube.with(variable, true));
            }
        }
        for (Cube cube : lowCubes) {
            if (!lowNeedsLiteral) {
                implicants.add(cube);
            } else if (highNeedsLiteral || highCubes.stream().noneMatch(cube::implies)) {
                implicants.add(cube.with(variable, false));
            }
        }
        implicants = List.copyOf(implicants);
        memo.put(function, implicants);
        return implicants;
    }
}
