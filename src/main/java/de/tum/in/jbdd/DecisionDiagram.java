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

    int high(int node);

    int low(int node);

    /**
     * Gets the variable of the given {@code node}.
     */
    int variable(int node);

    /**
     * Determines whether the given {@code node} represents a constant, i.e. {@code true} or {@code false}.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a constant.
     */
    boolean isLeaf(int node);

    /**
     * Determines whether the given {@code node} represents a variable.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a variable.
     */
    boolean isVariable(int node);

    /**
     * Determines whether the given {@code node} represents a negated variable.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a negated variable.
     */
    boolean isVariableNegated(int node);

    /**
     * Determines whether the given {@code node} represents a variable or it's negation.
     *
     * @param node The node to be checked.
     * @return If the {@code node} represents a variable.
     */
    boolean isVariableOrNegated(int node);

    /**
     * Returns the number of variables in this BDD.
     *
     * @return The number of variables.
     */
    int numberOfVariables();

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
     * Returns the reference count of the given node or {@literal -1} if this number can't be
     * accurately determined (e.g., when a node is saturated).
     */
    int referenceCount(int node);

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
        reference(result);
        dereference(inputNode);
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
