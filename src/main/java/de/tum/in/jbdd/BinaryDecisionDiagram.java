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

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.PrimitiveIterator;
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
public interface BinaryDecisionDiagram extends BooleanDecisionDiagram, BooleanTerminalDecisionDiagram<NatSet, Cube> {
    /** A satisfying assignment, fresh and the caller's own. */
    @Override
    MutableNatSet satisfyingAssignment(int function);

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
     *   <li>however small the domain: the probability loses precision only when it is itself below
     *   {@code 2^-1022}.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if {@code domain} is {@literal false}, which has no assignment to draw
     */
    double satisfyingFractionIn(int function, int domain);

    /**
     * Constructs the generalized cofactor of {@code function} w.r.t. {@code domain} (Coudert &amp; Madre),
     * also written {@code f @ domain}: The result agrees with {@code function} wherever {@code domain} holds,
     * and elsewhere takes the value of {@code function} at the nearest {@code domain}-satisfying assignment
     * (variables decided top-down, flipped only when {@code domain} forces it) - a specific canonical
     * choice, unlike {@link #simplify}'s arbitrary one, so the result may depend on variables {@code
     * function} did not and is not guaranteed to stay bounded in size.
     */
    int constrain(int function, int domain);

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
        MutableNatSet variableSet = MutableNatSet.dense(numberOfVariables());
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
    int conjunction(NatSet variables);

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
        MutableNatSet variableSet = MutableNatSet.dense(numberOfVariables());
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
    int disjunction(NatSet variables);

    /**
     * Constructs the <i>composition</i> of the given boolean {@code function} with the boolean functions in
     * {@code variableMapping}. Formally, if {@code function} is {@code f(x_1, x_2, ..., x_n)}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i = variableMapping[i]}.
     *
     * <p>The {@code variableMapping} array can contain less than {@code n} entries, then only the first variables are
     * replaced. Furthermore, {@link #placeholder()} can be used as an entry to denote "don't replace this variable"
     * (which semantically is the same as saying "replace this variable by itself"). The array is only read.</p>
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
     * longer depends on them. Each variable of the restriction must exist ({@link IllegalArgumentException}
     * otherwise).
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
     * @see #exists(int, NatSet)
     */
    RegisteredOperation.Unary registerExists(NatSet quantifiedVariables);

    /**
     * The function of the cube {@code path}: {@link Cube#support()} fixed to
     * {@link Cube#assignment()}, every other variable free. In a sense, the inverse of path enumeration.
     */
    default int of(Cube path) {
        // Held referenced throughout, the constant included: a diagram may count references on its leaves.
        int cube = reference(trueFunction());
        PrimitiveIterator.OfInt iterator = path.support().iterator();
        while (iterator.hasNext()) {
            int variable = iterator.nextInt();
            int literal = path.assignment().contains(variable)
                    ? variableFunction(variable)
                    : reference(not(variableFunction(variable)));
            cube = updateWith(and(cube, literal), cube);
            if (!path.assignment().contains(variable)) {
                dereference(literal);
            }
        }
        return dereference(cube);
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
    int adopt(BinaryDecisionDiagram source, int function, IntUnaryOperator variableMapping);
}
