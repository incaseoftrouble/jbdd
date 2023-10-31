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

import java.util.BitSet;

public interface DecisionDiagram {
    /**
     * A special reserved placeholder distinct from any possible node value, which may be used as a placeholder in some operations.
     * Needs to stay constant throughout the life of the diagram.
     *
     * @return A placeholder value
     */
    int placeholder();

    /**
     * Determines whether the given {@code node} represents a constant.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a constant.
     */
    boolean isLeaf(int node);

    /**
     * Gets the variable of the given {@code node} or {@code -1} for a leaf.
     */
    int variableOf(int node);

    /**
     * Returns the number of variables in this decision diagram.
     *
     * @return The number of variables.
     */
    int numberOfVariables();

    /**
     * Returns the reference count of the given node or {@literal -1} if this number can't be
     * accurately determined (e.g., when a node is saturated).
     */
    int referenceCount(int node);

    int saturateNode(int node);

    /**
     * Checks if the given {@code node} is saturated. This can happen if the node is explicitly marked
     * as saturated or gets referenced too often.
     *
     * @param node The node to be checked
     * @return Whether the node is saturated
     * @see #saturateNode(int)
     */
    boolean isNodeSaturated(int node);

    /**
     * Increases the reference count of the specified {@code node}.
     *
     * @param node The to be referenced node
     * @return The given node, to be used for chaining.
     */
    int reference(int node);

    /**
     * Decreases the reference count of the specified {@code node}.
     *
     * @param node The to be de-referenced node
     * @return The given node, to be used for chaining.
     */
    int dereference(int node);

    /**
     * Decreases the reference count of the specified {@code nodes}.
     *
     * @param nodes The to be de-referenced nodes
     */
    default void dereference(int... nodes) {
        for (int node : nodes) {
            dereference(node);
        }
    }

    /**
     * Counts the number of referenced or saturated nodes.
     *
     * @return Number of referenced nodes.
     */
    int referencedNodeCount();

    int activeNodeCount();

    /**
     * Computes the <b>support</b> of the function represented by the given {@code node}. The support
     * of a function are all variables which have an influence on its value.
     *
     * @param node The node whose support should be computed.
     * @return A bit set with bit {@code i} is set iff the {@code i}-th variable is in the support.
     */
    default BitSet support(int node) {
        return supportTo(node, new BitSet(numberOfVariables()));
    }

    /**
     * Computes the <b>support</b> of the given {@code node} and writes it in the {@code bitSet}.
     * Note that the {@code bitSet} is not cleared, the support variables are added to the set.
     *
     * @param node   The node whose support should be computed.
     * @param bitSet The BitSet used to store the result.
     * @return The given bitset, useful for chaining.
     * @see #support(int)
     */
    default BitSet supportTo(int node, BitSet bitSet) {
        BitSet filter = new BitSet(numberOfVariables());
        filter.set(0, numberOfVariables());
        return supportFilteredTo(node, bitSet, filter);
    }

    default BitSet supportFiltered(int node, BitSet filter) {
        return supportFilteredTo(node, new BitSet(numberOfVariables()), filter);
    }

    /**
     * Computes the <b>support</b> of the given {@code node} and writes it in the {@code bitSet}.
     * Only considers variables in the given {@code filter}. Note that the {@code bitSet} is not
     * cleared, the support variables are added to the set.
     *
     * @param node   The node whose support should be computed.
     * @param bitSet The BitSet used to store the result.
     * @return The given bitset, useful for chaining.
     * @see #support(int)
     */
    BitSet supportFilteredTo(int node, BitSet bitSet, BitSet filter);

    /**
     * Auxiliary function useful for updating node variables. It dereferences {@code inputNode} and
     * references {@code result}. This is useful for assignments like {@code node = f(node, ...)}
     * where {@code f} is some operation on the BDD. In this case, calling {@code node =
     * updateWith(bdd, f(node, ...), inputNode)} updates the references as needed and leaves the other
     * parameters untouched.
     *
     * <p>This would be more concise when implemented using method references, but these are
     * comparatively heavyweight.</p>
     *
     * @param result    The result of some operation on this BDD.
     * @param inputNode The node which gets assigned the value of the result.
     * @return The given {@code result}.
     */
    default int updateWith(int result, int inputNode) {
        if (result != inputNode) {
            reference(result);
            dereference(inputNode);
        }
        return result;
    }

    /**
     * Returns a string containing some statistics about the Bdd. The content and formatting of this
     * string may change drastically and are only intended as human-readable output.
     */
    String statistics();

    /**
     * A wrapper class to guard some node in an area where exceptions can occur. It increases the
     * reference count of the given node and decreases it when it's closed.
     */
    final class ReferenceGuard implements AutoCloseable {
        public final DecisionDiagram diagram;
        public final int node;

        public ReferenceGuard(int node, Bdd diagram) {
            this.node = diagram.reference(node);
            this.diagram = diagram;
        }

        @Override
        public void close() {
            diagram.dereference(node);
        }
    }
}
