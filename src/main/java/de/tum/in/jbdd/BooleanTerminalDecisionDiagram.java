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
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface BooleanTerminalDecisionDiagram<S, P> extends DecisionDiagram {
    /**
     * Returns the boolean function representing {@code true}.
     */
    int trueFunction();

    /**
     * Returns the boolean function representing {@code false}.
     */
    int falseFunction();

    /**
     * Checks whether the given boolean {@code function} evaluates to {@code true} under the given {@code assignment}.
     *
     * @param function
     *     The function to evaluate.
     * @param assignment
     *     The variable assignment.
     *
     * @return The truth value of the node under the given assignment.
     */
    boolean evaluate(int function, S assignment);

    /**
     * Returns any satisfying assignment of the given boolean {@code function}.
     *
     * @throws java.util.NoSuchElementException
     *     if there is no satisfying assignment, i.e. the given {@code function} is {@literal false}.
     */
    S satisfyingAssignment(int function);

    /**
     * Counts the number of satisfying assignments for the given boolean {@code function}.
     */
    BigInteger countSatisfyingAssignments(int function);

    /**
     * Counts the number of satisfying assignments for the given boolean {@code function}, only considering variables in the
     * {@code support}.
     */
    BigInteger countSatisfyingAssignments(int function, BitSet support);

    /**
     * Returns an iterator over all satisfying assignments of the given boolean {@code function}. In other words,
     * this call is equivalent to
     * {@code
     *   Set&lt;S&gt; solutions = new HashSet&lt;&gt;();
     *   for (S valuation : valuations) {
     *     if (this.evaluate(function, valuation)) {
     *       solutions.add(valuation);
     *     }
     *   }
     *   return solutions.iterator();
     * }
     * where {@code valuations} is the set of all possible valuations.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     *
     * <p><b>Note:</b> The passed objects may be modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to {@link Iterator#next()}.</p>
     */
    Iterator<S> solutionIterator(int function);

    Iterator<S> solutionIterator(int function, BitSet support);

    /**
     * Executes the given action for each satisfying assignment of the given boolean {@code function}.
     *
     * <p>The solutions are generated in lexicographic ascending order.</p>
     *
     * @param function
     *     The function whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    default void forEachSolution(int function, Consumer<? super S> action) {
        solutionIterator(function).forEachRemaining(action);
    }

    default void forEachSolution(int function, BitSet support, Consumer<? super S> action) {
        solutionIterator(function, support).forEachRemaining(action);
    }

    Iterator<P> pathIterator(int function);

    /**
     * Executes the given {@code action} for all <em>minimal</em> solutions of the given boolean {@code function}.
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
    void forEachPath(int function, Consumer<? super P> action);

    void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super P> action);

    boolean anyPathMatches(int function, Predicate<? super P> predicate);

    /**
     * Checks whether the boolean {@code function1} implies {@code function2}, i.e. if every valuation under
     * which {@code function1} evaluates to true also evaluates to true on {@code function2}. This is
     * equivalent to checking if {@link #implication(int, int)} with {@code function1} and {@code function2}
     * as parameters is equal to {@link #trueFunction()} and equal to checking whether {@code function1} equals
     * {@code function1 OR function2}, but faster.
     */
    boolean implies(int function1, int function2);

    /**
     * Checks whether there exists an assignment for which both {@code function1} and {@code function2}
     * evaluate to true. This is equivalent to checking if {@link #and(int, int)} with {@code function1}
     * and {@code function2} as parameters is not equal to {@link #falseFunction()}, but faster.
     */
    boolean intersects(int function1, int function2);

    /**
     * Constructs the boolean function {@code function1 AND function2}.
     */
    int and(int function1, int function2);

    /**
     * Constructs the boolean function {@code function1 AND NOT function2}.
     */
    int andNot(int function1, int function2);

    /**
     * Constructs the boolean function {@code function1 EQUIVALENT function2}.
     */
    int equivalence(int function1, int function2);

    /**
     * Constructs the function obtained by existential quantification of the boolean {@code function} with all variables
     * specified by {@code quantifiedVariables}. Formally, let {@code function} be {@code f(x_1, ..., x_m)} and
     * {@code x_1, ..., x_m} all variables for which {@code quantifiedVariables} is set. This method then constructs
     * {@code E x_1 E x_2 ... E x_n f(x_1, ..., x_m)}.
     *
     * @param function
     *     The function representing the basis of the quantification.
     * @param quantifiedVariables
     *     The variables which should be quantified over.
     *
     * @return The quantified function.
     */
    int exists(int function, BitSet quantifiedVariables);

    /**
     * Constructs the function obtained by forall quantification of the boolean {@code function} with all variables
     * specified by {@code quantifiedVariables}. Formally, let {@code function} be {@code f(x_1, ..., x_m)} and
     * {@code x_1, ..., x_m} all variables for which {@code quantifiedVariables} is set. This method then constructs
     * {@code A x_1 A x_2 ... A x_n f(x_1, ..., x_m)}.
     *
     * @param function
     *     The function representing the basis of the quantification.
     * @param quantifiedVariables
     *     The variables which should be quantified over.
     *
     * @return The quantified function.
     */
    int forall(int function, BitSet quantifiedVariables);

    /**
     * Constructs the boolean function {@code function1 IMPLIES function2}.
     */
    int implication(int function1, int function2);

    /**
     * Constructs the boolean function {@code NOT {@code function}}.
     */
    int not(int function);

    /**
     * Constructs the boolean function {@code function1 NAND function2}.
     */
    int notAnd(int function1, int function2);

    /**
     * Constructs the boolean function {@code function1 OR function2}.
     */
    int or(int function1, int function2);

    /**
     * Constructs the boolean function {@code function1 XOR function2}.
     */
    int xor(int function1, int function2);

    /**
     * Constructs the boolean function {@code IF ifFunction THEN thenFunction ELSE elseFunction}.
     */
    int ifThenElse(int ifFunction, int thenFunction, int elseFunction);

    /**
     * Constructs a simplified version of the given {@code function} which is equivalent to it for all assignments
     * where {@code domain} is true. This is equivalent to {@code IF domain THEN function ELSE x} where {@code x}
     * is any function.
     */
    int simplify(int function, int domain);
}
