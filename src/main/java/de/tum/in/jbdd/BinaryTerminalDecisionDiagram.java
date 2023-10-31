/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

public interface BinaryTerminalDecisionDiagram extends DecisionDiagram {
    /**
     * Returns the node representing {@code true}.
     */
    int trueNode();

    /**
     * Returns the node representing {@code false}.
     */
    int falseNode();

    /**
     * Counts the number of satisfying assignments for the function represented by this node.
     */
    BigInteger countSatisfyingAssignments(int node);

    /**
     * Counts the number of satisfying assignments for the function represented by this node,
     * only considering variables in the {@code support}.
     */
    BigInteger countSatisfyingAssignments(int node, BitSet support);

    /**
     * Constructs the node representing {@code node1 AND node2}.
     */
    int and(int node1, int node2);

    /**
     * Constructs the node representing {@code node1 EQUIVALENT node2}.
     */
    int equivalence(int node1, int node2);

    /**
     * Constructs the node representing the function obtained by existential quantification of {@code
     * node} with all variables specified by {@code quantifiedVariables}. Formally, let {@code f(x_1,
     * ..., x_m)} be the function specified by {@code node} and {@code x_1, ..., x_m} all
     * variables for which {@code quantifiedVariables} is set. This method then constructs {@code E x_1 E
     * x_2 ... E x_n f(x_1, ..., x_m)}.
     *
     * @param node
     *     The node representing the basis of the quantification.
     * @param quantifiedVariables
     *     The variables which should be quantified over.
     *
     * @return The node representing the quantification.
     */
    int exists(int node, BitSet quantifiedVariables);

    /**
     * Constructs the node representing the function obtained by forall quantification of {@code
     * node} with all variables specified by {@code quantifiedVariables}. Formally, let {@code f(x_1,
     * ..., x_m)} be the function specified by {@code node} and {@code x_1, ..., x_m} all
     * variables for which {@code quantifiedVariables} is set. This method then constructs {@code A x_1 A
     * x_2 ... A x_n f(x_1, ..., x_m)}.
     *
     * @param node
     *     The node representing the basis of the quantification.
     * @param quantifiedVariables
     *     The variables which should be quantified over.
     *
     * @return The node representing the quantification.
     */
    int forall(int node, BitSet quantifiedVariables);

    /**
     * Constructs the node representing {@code IF {@code ifNode} THEN {@code thenNode} ELSE {@code
     * elseNode}}.
     */
    int ifThenElse(int ifNode, int thenNode, int elseNode);

    /**
     * Constructs the node representing {@code node1 IMPLIES node2}.
     */
    int implication(int node1, int node2);

    /**
     * Checks whether the given {@code node1} implies {@code node2}, i.e. if every valuation under
     * which the function represented by {@code node1} evaluates to true also evaluates to true on
     * {@code node2}. This is equivalent to checking if {@link #implication(int, int)} with {@code
     * node1} and {@code node2} as parameters is equal to {@link #trueNode()} and equal to checking
     * whether {@code node1} equals {@code node1 OR node2}, but faster.
     *
     * <p><b>Note:</b> As many operations are cached, it may be even faster to use an alternative
     * logical representation of implication depending on how the BDD is used before this invocation.
     * E.g. if {@code node1 OR 2} has been computed already, checking if {@code {@code
     * node1} == {@code node1} OR {@code node2}} is a constant time operation.</p>
     *
     * @param node1
     *     The node representing the assumption.
     * @param node2
     *     The node representing the consequence.
     *
     * @return Whether {@code node1 IMPLIES node2} is a tautology.
     */
    boolean implies(int node1, int node2);

    /**
     * Constructs the node representing {@code NOT {@code node}}.
     *
     * @param node
     *     The node to be negated.
     *
     * @return The negation of the given BDD.
     */
    int not(int node);

    /**
     * Constructs the node representing {@code node1 NAND node2}.
     */
    int notAnd(int node1, int node2);

    /**
     * Constructs the node representing {@code node1 OR node2}.
     */
    int or(int node1, int node2);

    /**
     * Constructs the node representing {@code node1 XOR node2}.
     */
    int xor(int node1, int node2);

    /**
     * Auxiliary function useful for updating node variables. It dereferences the inputs and
     * references {@code result}. This is useful for assignments like {@code node = f(in1, in2)} where
     * {@code f} is some operation on this BDD and both {@code in1} and {@code in2} are temporary
     * nodes or not used anymore. In this case, calling {@code node = consume(bdd, node(in1, in2),
     * in1, in2)} updates the references as needed.
     *
     * <p>This would be more concise when implemented using method references, but these are
     * comparatively heavyweight.</p>
     *
     * @param result     The result of some operation on this BDD involving inputNode1 and inputNode2
     * @param inputNode1 First input of the operation.
     * @param inputNode2 Second input of the operation.
     * @return The given {@code result}.
     */
    default int consume(int result, int inputNode1, int inputNode2) {
        reference(result);
        dereference(inputNode1);
        dereference(inputNode2);
        return result;
    }
}
