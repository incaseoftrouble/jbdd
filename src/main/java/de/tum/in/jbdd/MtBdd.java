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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public interface MtBdd<V> extends DecisionDiagram {
    /**
     * The underlying BDD with which this structure shares its variables.
     */
    Bdd bdd();

    /**
     * The type of values of this structure.
     */
    Class<V> valueType();

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
    V evaluate(int function, boolean[] assignment);

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
    V evaluate(int function, BitSet assignment);

    /**
     * Creates the constant function with given {@code value}.
     */
    int of(V value);

    /**
     * Creates a function that evaluates to the given child functions.
     */
    int of(int variable, int trueChild, int falseChild);

    /**
     * Returns any assignment of the given {@code function} leading to the given {@code values}, if any.
     */
    Optional<BitSet> anyAssignment(int function, Predicate<? super V> values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values}.
     */
    BigInteger countAssignments(int function, Predicate<? super V> values);

    /**
     * Counts the number of assignments under which the given {@code function} evaluates to the given {@code values},
     * only considering variables in the {@code support}.
     */
    BigInteger countAssignments(int function, Predicate<? super V> values, BitSet support);

    Iterator<BitSet> assignmentIterator(int function, Predicate<? super V> values);

    Iterator<BitSet> assignmentIterator(int function, Predicate<? super V> values, BitSet support);

    /**
     * Executes the given action for each assignment under which the given {@code function} evaluates to the given
     * {@code values}.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     */
    default void forEachSolution(int function, Predicate<? super V> values, Consumer<? super BitSet> action) {
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
    default void forEachPath(int function, BiConsumer<? super BitSet, ? super V> action) {
        forEachPath(function, (path, pathSupport, value) -> action.accept(path, value));
    }

    /**
     * Executes the given {@code action} for all <em>minimal</em> assignment of the given {@code function}.
     *
     * <p>Minimal solutions are all point-wise smallest assignments that satisfy the function.</p>
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     *
     * <p><b>Note:</b> The passed bit sets are modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to the consumer.</p>
     *
     * @param function
     *     The function whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    void forEachPath(int function, TerminalPathConsumer<? super V> action);

    /**
     * Computes the co-domain of the given {@code function}.
     */
    default Set<V> valuesOf(int function) {
        Set<V> values = new HashSet<>();
        forEachValue(function, values::add);
        return values;
    }

    /**
     * Determines whether all values in the co-domain of the given {@code function} match the {@code predicate}.
     */
    boolean allValuesMatch(int function, Predicate<? super V> predicate);

    /**
     * Determines whether any value in the co-domain of the given {@code function} matches the {@code predicate}.
     */
    boolean anyValueMatches(int function, Predicate<? super V> predicate);

    /**
     * Calls the given {@code action} for each value in the co-domain of the given {@code function}
     * <em>at least</em> once.
     */
    void forEachValue(int function, Consumer<? super V> action);

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
     *     The function to be composed.
     * @param variableMapping
     *     The boolean functions with which each variable should be replaced.
     *
     * @return The composed function.
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
    int update(int function, int assignments, V value);

    /**
     * Compute the function that for each assignment {@code a} evaluates to {@code map(function1(a), function2(a))}.
     */
    int apply(int function1, int function2, BiFunction<? super V, ? super V, ? extends V> map);

    /**
     * Constructs the function obtained by composing the given {@code function} with {@code map}, i.e.
     * {@code map(function(input))}.
     */
    <T> int map(int function, Function<? super V, ? extends T> map, MtBdd<T> other);

    /**
     * Returns a view on this structure with values remapped. The given mapping needs to be an injection for
     * consistency.
     */
    <T> MtBdd<T> viewAs(Function<? super V, ? extends T> injection);

    /**
     * Creates the boolean function representing all assignments under which the given {@code function}
     * evaluates to the given {@code values}.
     */
    int mapBoolean(int function, Predicate<? super V> values);

    /**
     * Creates the inverse of the given {@code function}, i.e. a mapping from each value to the boolean
     * function describing all assignments under which the function evaluates to that value.
     *
     * @see #mapBoolean(int, Predicate)
     */
    Inverse<V> invert(int function);

    /**
     * Creates the split of the given {@code function}. Suppose {@code function} is {@code f(x_1, ..., x_n}}
     * and {@code splitVariables} are all even variables. Then, the result of this method is a function
     * {@code g(x_2, x_4, ..., x_{n-1})} which yields for every assignment to these variables a function
     * {@code h(x_1, x_3, ..., x_n)} that evaluates to {@code f(x_1, ..., x_n)}.
     */
    int split(int function, BitSet splitVariables, MtBdd<MtBdd<V>> other);

    @FunctionalInterface
    interface TerminalPathConsumer<V> {
        void accept(BitSet path, BitSet support, V value);
    }

    interface Inverse<V> {
        int functionFor(V value);

        Set<V> support();
    }
}
