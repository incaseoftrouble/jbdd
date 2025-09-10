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
import java.util.function.IntConsumer;

abstract class BooleanBase<S, P> implements BooleanTerminalDecisionDiagram<S, P>, NodeBasedDecisionDiagram {
    static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);
    static final int[] EMPTY_INT_ARRAY = new int[0];
    static final int TRUE = Integer.MAX_VALUE;
    static final int FALSE = complement(TRUE);

    abstract BooleanCache cache();

    abstract NodeTable table();

    abstract BddConfiguration configuration();

    public int tableSize() {
        return table().size();
    }

    boolean check() {
        return table().check();
    }

    void invalidateCache() {
        cache().tableSizeChanged();
    }

    void clearCacheAfterGC(int reclaimedNodes) {
        // Delete cache entries which are no longer valid
        // If we reclaimed a lot of nodes, we won't be able to save much
        if (configuration().useCachePartialInvalidate() && reclaimedNodes < tableSize() / 2) {
            cache().partialInvalidate();
        } else {
            cache().tableSizeChanged();
        }
    }

    /**
     * Perform garbage collection by freeing up dead nodes.
     *
     * @return Number of reclaimed nodes.
     */
    public int forceGc() {
        table().markAllReferencedNodes();
        int reclaimedNodes = table().reclaimUnmarkedNodes();
        cache().partialInvalidate();
        assert table().isNoneMarked();
        return reclaimedNodes;
    }

    boolean isValidNonConstantFunction(int function) {
        return table().isValidDecisionNode(positive(function));
    }

    // Reference counting

    @Override
    public int reference(int function) {
        assert isValidFunction(function);
        int positive = positive(function);
        if (positive != TRUE) {
            table().referenceNode(positive);
        }
        return function;
    }

    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        int positive = positive(function);
        if (positive != TRUE) {
            table().dereferenceNode(positive);
        }
        return function;
    }

    @Override
    public int updateWith(int result, int input) {
        int resultNode = positive(result);
        int node1 = positive(input);
        if (resultNode != node1) {
            if (resultNode != TRUE) {
                table().referenceNode(resultNode);
            }
            if (node1 != TRUE) {
                table().dereferenceNode(node1);
            }
        }
        return result;
    }

    @Override
    public int consume(int result, int input1, int input2) {
        // result + 1, input1 - 1, input2 - 1
        int resultNode = positive(result);
        int node1 = positive(input1);
        int node2 = positive(input2);
        if (resultNode == node1) {
            if (node2 != TRUE) {
                table().dereferenceNode(node2);
            }
        } else {
            if (resultNode != node2) {
                if (resultNode != TRUE) {
                    table().referenceNode(resultNode);
                }
                if (node2 != TRUE) {
                    table().dereferenceNode(node2);
                }
            }
            if (node1 != TRUE) {
                table().dereferenceNode(node1);
            }
        }
        return result;
    }

    @Override
    public int nodeReferenceCount(int node) {
        return table().nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return node == TRUE || table().isSaturatedNode(node);
    }

    @Override
    public void forEachSupportVariable(int function, IntConsumer action) {
        table().forEachVariable(function, action);
    }

    @Override
    public void forEachSupportFiltered(int function, BitSet filter, IntConsumer action) {
        table().forEachVariable(function, filter, action);
    }

    @Override
    public int referencedNodeCount() {
        return table().referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return table().nodeCount();
    }

    // Nodes

    @Override
    public int trueFunction() {
        return TRUE;
    }

    @Override
    public int falseFunction() {
        return FALSE;
    }

    /**
     * Determines if the given {@code function} is valid. For most operations it is required that this is the case.
     *
     * @param function The function to be checked.
     * @return If {@code} is valid or root function.
     * @see #isConstant(int)
     */
    public boolean isValidFunction(int function) {
        int positive = positive(function);
        return positive == TRUE || table().isValidDecisionNode(positive);
    }

    @Override
    public int nodeFor(int function) {
        assert isValidFunction(function);
        return positive(function);
    }

    @Override
    public int decisionVariable(int function) {
        assert isValidNonConstantFunction(function);
        return table().variable(positive(function));
    }

    @Override
    public int placeholder() {
        return NodeTable.PLACEHOLDER;
    }

    @Override
    public boolean isConstant(int function) {
        return function == TRUE || function == FALSE;
    }

    public boolean isPositive(int function) {
        return function > 0;
    }

    @Override
    public int andNot(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return and(function1, complement(function2));
    }

    @Override
    public int equivalence(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return complement(xor(function1, function2));
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        assert isValidFunction(function);
        return complement(exists(complement(function), quantifiedVariables));
    }

    @Override
    public int implication(int function1, int function2) {
        return complement(and(function1, complement(function2)));
    }

    @Override
    public int not(int function) {
        assert isValidFunction(function);
        return complement(function);
    }

    @Override
    public int notAnd(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return complement(and(function1, function2));
    }

    @Override
    public int or(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return complement(and(complement(function1), complement(function2)));
    }

    String format(int reference) {
        if (reference == TRUE) {
            return "TRUE";
        }
        if (reference == FALSE) {
            return "FALSE";
        }
        return String.format("%s%d", isPositive(reference) ? "" : "!", positive(reference));
    }

    static int positive(int function) {
        return function < 0 ? -function : function;
    }

    static int complement(int function) {
        return -function;
    }

    static int complementIf(int function, boolean condition) {
        return condition ? complement(function) : function;
    }

    static boolean isComplementFunction(int function) {
        return function < 0;
    }

    static boolean isTrue(int function, boolean lookingFor) {
        return lookingFor ? (function == TRUE) : (function == FALSE);
    }

    static boolean isFalse(int function, boolean lookingFor) {
        return lookingFor ? (function == FALSE) : (function == TRUE);
    }

    public abstract String statistics();
}
