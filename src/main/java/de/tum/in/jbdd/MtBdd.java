/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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

import java.math.BigInteger;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

public interface MtBdd extends BooleanDecisionDiagram {
    /**
     * The underlying BDD with which this structure shares its variables.
     */
    Bdd bdd();

    /**
     * Checks which value the given {@code function} evaluates to under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the function under the given assignment.
     */
    int evaluate(int function, boolean[] assignment);

    /**
     * Checks which value the given {@code function} evaluates to under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the node under the given assignment.
     */
    int evaluate(int function, BitSet assignment);

    /**
     * Creates the constant function with given {@code value}.
     */
    int of(int value);

    /**
     * Creates a function that evaluates to the given child functions.
     */
    int of(int variable, int trueChild, int falseChild);

    /**
     * Returns any assignment of the given {@code function} leading to the given {@code values}, if any.
     */
    Optional<BitSet> anyAssignment(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values}.
     */
    BigInteger countAssignments(int function, IntPredicate values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values},
     * only considering variables in the {@code support}.
     */
    BigInteger countAssignments(int function, IntPredicate values, BitSet support);

    Iterator<BitSet> assignmentIterator(int function, IntPredicate values);

    Iterator<BitSet> assignmentIterator(int function, IntPredicate values, BitSet support);

    /**
     * Executes the given action for each assignment under which the given {@code function} evaluates to the given
     * {@code values}.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     */
    default void forEachSolution(int function, IntPredicate values, Consumer<? super BitSet> action) {
        assignmentIterator(function, values).forEachRemaining(action);
    }

    /**
     * Executes the given {@code action} for all <em>minimal</em> assignment of the given {@code function}.
     *
     * <p>Minimal solutions are all point-wise smallest assignments that satisfy the function.</p>
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     *
     * <p><b>Note:</b> The passed bit set is modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to the consumer.</p>
     *
     * @param function
     *     The function whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    void forEachPath(int function, BiConsumer<BinaryPath, Integer> action);

    /**
     * Computes the co-domain of the given {@code function}.
     */
    default BitSet valuesOf(int function) {
        BitSet values = new BitSet();
        forEachValue(function, values::set);
        return values;
    }

    /**
     * Determines whether all values in the co-domain of the given {@code function} match the {@code predicate}.
     */
    default boolean allValuesMatch(int function, IntPredicate predicate) {
        return valuesOf(function).stream().allMatch(predicate);
    }

    /**
     * Determines whether any value in the co-domain of the given {@code function} matches the {@code predicate}.
     */
    default boolean anyValueMatches(int function, IntPredicate predicate) {
        return !allValuesMatch(function, predicate.negate());
    }

    /**
     * Calls the given {@code action} for each value in the co-domain of the given {@code function}
     * <em>at least</em> once.
     */
    void forEachValue(int function, IntConsumer action);

    /**
     * Constructs the <i>composition</i> of the given {@code function} with the boolean functions in {@code variableNodes}.
     * Formally, if {@code function} is {@code f(x_1, x_2, ..., x_n)}, this method returns
     * {@code f(f_1(x_1, ..., x_n), ..., f_n(x_1, ..., x_n))}, where {@code f_i = variableNodes[i]}.
     *
     * <p>The {@code variableNodes} array can contain less than {@code n} entries, then only the first variables are replaced.
     * Furthermore, {@code placeholder} can be used as an entry to denote "don't replace this variable" (which semantically
     * is the same as saying "replace this variable by itself"). After the call, the {@code placeholder} entries will be
     * replaced by the actual corresponding variable nodes. </p>
     *
     * @param function
     *     The MTBDD function to be composed.
     * @param variableMapping
     *     The boolean functions (in the underlying BDD) with which each variable should be replaced.
     *
     * @return The composed MTBDD function.
     */
    int compose(int function, int[] variableMapping);

    /**
     * Computes the restriction of the given {@code function}, where all variables specified by {@code
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
     * Compute the function where all given {@code assignments} (represented as function in the underlying BDD)
     * evaluates to the given {@code value}.
     */
    int update(int function, int assignments, int value);

    /**
     * Compute the function that for each assignment {@code a} evaluates to {@code map(function1(a), function2(a))}.
     */
    int apply(int function1, int function2, IntBinaryOperator map);

    /**
     * Compute the function that for each assignment {@code a} evaluates to {@code map(f_1(a), ..., f_m(a))}.
     */
    int apply(int[] functions, ToIntFunction<int[]> map);

    /**
     * Constructs the function obtained by composing the given {@code function} with {@code map}, i.e.
     * {@code map(function(input))}.
     */
    default int map(int function, IntUnaryOperator map) {
        return apply(new int[] {function}, a -> map.applyAsInt(a[0]));
    }

    /**
     * Constructs the boolean function in the associated BDD which evaluates to true exactly for those
     * valuations on which the given functions yield the same value.
     */
    int agreement(int function1, int function2);

    /**
     * Creates the boolean function representing all assignments under which the given {@code function}
     * evaluates to the given {@code values} in the underlying {@link #bdd() Bdd}.
     *
     * @see #agreement(int, int)
     */
    int mapBoolean(int function, IntPredicate values);

    /**
     * Creates the inverse of the given {@code function}, i.e. a mapping from each value to the boolean
     * function describing all assignments under which the function evaluates to that value.
     *
     * @see #mapBoolean(int, IntPredicate)
     */
    Inverse invert(int function);

    /**
     * Creates the split of the given {@code function}. Suppose {@code function} is {@code f(x_1, ..., x_n}}
     * and {@code splitVariables} are all even variables. Then, the result of this method is a function
     * {@code g(x_2, x_4, ..., x_{n-1})} which yields for every assignment to these variables a function
     * {@code h(x_1, x_3, ..., x_n)} that evaluates to {@code f(x_1, ..., x_n)}.
     */
    int split(int function, BitSet splitVariables);

    /**
     * Creates the product of the given {@code functions}. Suppose each function is {@code f_i(x_1, ..., x_n}},
     * then their product is a function {@code f(x)} that yields {@code [f_1(x), ..., f_m(x)]}. The returned
     * function indexes the {@code values} map. The outputs of {@code f} do not need to be dense.
     */
    default Product cartesianProduct(int[] functions) {
        Map<int[], Integer> product = new HashMap<>();
        int function = apply(functions, values -> product.computeIfAbsent(values, k -> product.size()));
        Map<Integer, int[]> results = new HashMap<>();
        product.forEach((k, v) -> results.put(v, k));

        return new Product() {
            @Override
            public int function() {
                return function;
            }

            @Override
            public Map<Integer, int[]> values() {
                return results;
            }
        };
    }

    /**
     * Constructs a simplified version of the given {@code function} which is equivalent to it for all assignments
     * where {@code domain} is true.
     *
     * @param function A function in this MTBDD
     * @param domain A function in the underlying BDD
     * @return The simplified MTBDD function
     */
    int simplify(int function, int domain);

    interface Inverse {
        int functionFor(int value);

        BitSet values();
    }

    interface Product {
        int function();

        Map<Integer, int[]> values();
    }
}
