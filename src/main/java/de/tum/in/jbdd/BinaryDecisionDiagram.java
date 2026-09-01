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

import java.util.BitSet;

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
public interface BinaryDecisionDiagram
        extends BooleanDecisionDiagram, BooleanTerminalDecisionDiagram<BitSet, BinaryPath> {
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
     * Registers a {@code compose} operation bound to a fixed {@code variableMapping}.
     */
    RegisteredOperation.Unary registerCompose(int[] variableMapping);

    /**
     * Like {@link #registerCompose}, but for the domain-restricted {@link #composeSimplify} form.
     */
    RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping);

    /**
     * Computes the restriction of the given boolean {@code function}, where all variables specified by {@code
     * restrictedVariables} are replaced by the value given in {@code restrictedVariableValues}.
     * Formally, if {@code function} is {@code f(x_1, ..., x_n)}, this method computes the function
     * {@code f(x_1, ..., x_{i_1-1}, c_1, x_{i_1+1}, ..., x_{i_2-1}, c_2, x_{i_2+1}, ...,
     * x_n}, where {@code i_k} are the elements of the {@code restrictedVariables} set and
     * {@code c_k := restrictedVariableValues.get(i_k)}.
     *
     * @param function
     *     The function to be restricted.
     * @param restrictedVariables
     *     The variables used in the restriction.
     * @param restrictedVariableValues
     *     The values of the restricted variables.
     *
     * @return The restricted function.
     *
     * @see #compose(int, int[])
     */
    int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues);

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
}
