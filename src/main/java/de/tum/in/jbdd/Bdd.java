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
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * This interface contains various BDD operations.
 *
 * <p>Note that for the sake of performance, most required properties of the arguments are only
 * checked though {@code assert} statements. With disabled assertions, undefined behaviour might
 * occur with invalid arguments. Especially, the BDD may appear to be in a working state for a long
 * time after an invalid call.</p>
 */
public interface Bdd extends BinaryTerminalDecisionDiagram {
    int high(int node);

    int low(int node);

    /**
     * Determines whether the given {@code node} represents a variable.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a variable.
     */
    default boolean isVariable(int node) {
        return !isLeaf(node) && low(node) == falseNode() && high(node) == trueNode();
    }

    /**
     * Determines whether the given {@code node} represents a negated variable.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a negated variable.
     */
    default boolean isVariableNegated(int node) {
        return !isLeaf(node) && low(node) == trueNode() && high(node) == falseNode();
    }

    /**
     * Determines whether the given {@code node} represents a variable or it's negation.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a variable.
     */
    default boolean isVariableOrNegated(int node) {
        return isVariable(node) || isVariableNegated(node);
    }

    /**
     * Returns the node which represents the variable with given {@code variableNumber}. The variable
     * must already have been created.
     *
     * @param variableNumber The number of the requested variable.
     * @return The corresponding node.
     */
    int variableNode(int variableNumber);

    /**
     * Creates a new variable and returns the node representing it. The implementation guarantees that
     * variables are always allocated sequentially starting from 0, i.e. {@code
     * getVariable(createVariable()) == numberOfVariables() - 1}.
     *
     * @return The node representing the new variable.
     */
    int createVariable();

    /**
     * Creates {@code count} many variables and returns their respective nodes. The first created
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
     * Checks whether the given {@code node} evaluates to {@code true} under the given variable
     * assignment specified by {@code assignment}. This method is significantly faster than its
     * {@link BitSet} counterpart {@link #evaluate(int, BitSet)}.
     *
     * @param node
     *     The node to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the node under the given assignment.
     */
    boolean evaluate(int node, boolean[] assignment);

    /**
     * Checks whether the given {@code node} evaluates to {@code true} under the given variable
     * assignment specified by {@code assignment}.
     *
     * @param node
     *     The node to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the node under the given assignment.
     */
    boolean evaluate(int node, BitSet assignment);

    /**
     * Returns any satisfying assignment.
     *
     * @throws java.util.NoSuchElementException
     *     if there is no satisfying assignment, i.e. the given {@code node} is {@literal false}.
     */
    BitSet getSatisfyingAssignment(int node);

    /**
     * Constructs the node representing the <i>composition</i> of the function represented by {@code
     * node} with the functions represented by the entries of {@code variableNodes}. More formally, if
     * {@code f(x_1, x_2, ..., x_n)} is the function represented by {@code node}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i =
     * variableNodes[i]}}<p> The {@code variableNodes} array can contain less than {@code n}
     * entries, then only the first variables are replaced. Furthermore, {@code placeholder} can be
     * used as an entry to denote "don't replace this variable" (which semantically is the same as
     * saying "replace this variable by itself"). Note that after the call the {@code placeholder}
     * entries will be replaced by the actual corresponding variable nodes. </p>
     *
     * @param node
     *     The node to be composed.
     * @param variableMapping
     *     The nodes of the functions with which each variable should be replaced.
     *
     * @return The node representing the composed function.
     */
    int compose(int node, int[] variableMapping);

    /**
     * Computes the restriction of the given {@code node}, where all variables specified by {@code
     * restrictedVariables} are replaced by the value given in {@code restrictedVariableValues}.
     * Formally, if {@code node} represents {@code f(x_1, ..., x_n)}, this method computes the node
     * representing {@code f(x_1, ..., x_{i_1-1}, c_1, x_{i_1+1}, ..., x_{i_2-1}, c_2, x_{i_2+1}, ...,
     * x_n}, where {@code i_k} are the elements of the {@code restrictedVariables} set and
     * {@code c_k := restrictedVariableValues.get(i_k)}. This is semantically equivalent to calling
     * compose where all specified variables are replaced by {@code true} or {@code false}, but
     * slightly faster.
     *
     * @param node
     *     The node to be restricted.
     * @param restrictedVariables
     *     The variables used in the restriction.
     * @param restrictedVariableValues
     *     The values of the restricted variables.
     *
     * @return The restricted node.
     *
     * @see #compose(int, int[])
     */
    int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues);

    /**
     * Returns an iterator over {@code all} satisfying assignments of the given node. In other words,
     * this call is equivalent to
     * {@code
     *   Set&lt;BitSet&gt; solutions = new HashSet&lt;&gt;();
     *   for (BitSet valuation : powerSet) {
     *     if (bdd.evaluate(node, valuation)) {
     *       solutions.add(valuation);
     *     }
     *   }
     *   return solutions.iterator();
     * }
     * where {@code powerSet} is the power set over all variables, i.e. all possible valuations.
     *
     * <p><b>Note:</b> The passed bit sets are modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to {@link Iterator#next()}.</p>
     */
    Iterator<BitSet> solutionIterator(int node);

    Iterator<BitSet> solutionIterator(int node, BitSet support);

    /**
     * Executes the given action for each satisfying assignment of the function represented by
     * {@code node}.
     *
     * @param node
     *     The node whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    default void forEachSolution(int node, Consumer<? super BitSet> action) {
        solutionIterator(node).forEachRemaining(action);
    }

    default void forEachSolution(int node, BitSet support, Consumer<? super BitSet> action) {
        solutionIterator(node, support).forEachRemaining(action);
    }

    /**
     * Iteratively computes all (minimal) solutions of the function represented by {@code node} and
     * executes the given {@code action} with it. The returned solutions are all assignments
     * representing a path from node to {@code true} in the graph induced by the BDD structure.
     * The solutions are generated in lexicographic ascending order.
     *
     * <p><b>Note:</b> The passed bit set is modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to the consumer.</p>
     *
     * @param node
     *     The node whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    default void forEachPath(int node, Consumer<? super BitSet> action) {
        forEachPath(node, (path, pathSupport) -> action.accept(path));
    }

    /**
     * Iteratively computes all (minimal) solutions of the function represented by {@code node} and
     * executes the given {@code action} with it. The returned solutions are all assignments
     * representing a path from node to {@code true} in the graph induced by the BDD structure.
     * The solutions are generated in lexicographic ascending order. Additionally, the action will be
     * provided the set of relevant variables for each solution.
     *
     * <p><b>Note:</b> The passed bit sets are modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to the consumer.</p>
     *
     * @param node
     *     The node whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    void forEachPath(int node, BiConsumer<BitSet, BitSet> action);

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
            return trueNode();
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
            return falseNode();
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
