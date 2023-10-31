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
import java.util.function.Consumer;

public interface Mdd extends BinaryTerminalDecisionDiagram {
    int declareVariable(int domain);

    int makeNode(int variable, boolean[] values);

    int follow(int node, int value);

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
    boolean evaluate(int node, int[] assignment);

    /**
     * Returns any satisfying assignment.
     *
     * @throws java.util.NoSuchElementException
     *     if there is no satisfying assignment, i.e. the given {@code node} is {@literal false}.
     */
    int[] getSatisfyingAssignment(int node);

    /**
     * Returns an iterator over {@code all} satisfying assignments of the given node.
     *
     * <p><b>Note:</b> The returned array is modified in-place. If all solutions should be gathered
     * into a set or similar, they have to be cloned after each call to {@link Iterator#next()}.</p>
     */
    Iterator<int[]> solutionIterator(int node);

    Iterator<int[]> solutionIterator(int node, BitSet support);

    /**
     * Executes the given action for each satisfying assignment of the function represented by
     * {@code node}.
     *
     * @param node
     *     The node whose solutions should be computed.
     * @param action
     *     The action to be performed on these solutions.
     */
    default void forEachSolution(int node, Consumer<int[]> action) {
        solutionIterator(node).forEachRemaining(action);
    }

    default void forEachSolution(int node, BitSet support, Consumer<int[]> action) {
        solutionIterator(node, support).forEachRemaining(action);
    }

    void forEachPath(int node, Consumer<int[]> action);

    int restrict(int node, int[] values);

    // TODO analog of compose?
}
