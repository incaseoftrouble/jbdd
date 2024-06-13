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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.TooManyFields")
abstract class NodeTable {
    private static final Logger logger = Logger.getLogger(NodeTable.class.getName());

    // Use 0 as "not a node" to make re-allocations slightly more efficient
    public static final int PLACEHOLDER = 0;
    static final int FIRST_NODE = 1;

    // Node metadata helpers

    /* Bits allocated for the reference counter */
    private static final int REFERENCE_COUNT_BIT_SIZE = 14;
    private static final int REFERENCE_COUNT_SATURATED = (1 << REFERENCE_COUNT_BIT_SIZE) - 1;
    private static final int REFERENCE_COUNT_MASK = (1 << REFERENCE_COUNT_BIT_SIZE) - 1;
    private static final int REFERENCE_COUNT_OFFSET = 1;
    /* Bits allocated for the variable number */
    private static final int VARIABLE_BIT_SIZE = 17;

    /* Mask used to indicate invalid nodes */
    private static final int INVALID_NODE_VARIABLE = (1 << VARIABLE_BIT_SIZE) - 1;
    private static final int VARIABLE_OFFSET = REFERENCE_COUNT_OFFSET + REFERENCE_COUNT_BIT_SIZE;
    private static final int MINIMUM_NODE_TABLE_SIZE = Primes.nextPrime(1_000);
    private static final int MAXIMAL_NODE_COUNT = Integer.MAX_VALUE / 2 - 8;

    static {
        //noinspection ConstantValue
        assert VARIABLE_BIT_SIZE + REFERENCE_COUNT_BIT_SIZE + 1 == Integer.SIZE;
    }

    /* Approximation of dead node count. */
    private int approximateDeadNodeCount = 0;
    /* Tracks the index of the last node which is referenced. Invariants on this variable:
     * biggestReferencedNode <= biggestValidNode and if a node has positive reference count, its
     * index is less than or equal to biggestReferencedNode. */
    private int biggestReferencedNode;
    /* Keep track of the last used node to terminate some loops early. The invariant is that if a node
     * is valid, then the node index is less than or equal to biggestValidNode. */
    private int biggestValidNode;
    /* First free (invalid) node, used when a new node is created. */
    private int firstFreeNode;
    /* Number of free (invalid) nodes. Used to determine if the table needs to be grown when adding a
     * node. Potentially, we could instead check if the next chain entry of firstFreeNode is 0. */
    private int freeNodeCount;

    /* Stores the meta-data for BDD nodes, namely the variable number, reference count and a mask used
     * by various internal algorithms. These values are manipulated through static helper functions.
     *
     * Layout: <---VAR---><---REF---><MARK> */
    private int[] nodeData;

    /* Hash map for existing nodes and a linked list for free nodes. The semantics of the "next
     * chain entry" change, depending on whether the node is valid or not.
     *
     * When a node with a certain hash is created, we add a pointer to the corresponding hash bucket
     * obtainable by hashToChainStart. Whenever we add another node with the same value, this
     * node gets added to the chain and one can traverse the chain by repeatedly accessing
     * hashChain on the chain start. If however a node is invalid, the "next chain
     * entry" points to the next free node. This saves some time when creating nodes, as we don't have
     * to scan through the table to find the next node which we can use.
     */
    private int[] hashToChainStart;
    private int[] hashChain;

    // Statistics
    private long createdNodes = 0;
    private long hashChainLookups = 0;
    private long hashChainLookupLength = 0;
    private int growCount = 0;
    private int garbageCollectionCount = 0;
    private long garbageCollectedNodeCount = 0;
    private long garbageCollectionTime = 0;

    /* The work stack is used to store intermediate nodes created by operations. While constructing
     * a new node, e.g. "v1 AND v2", we may need to create multiple intermediate nodes. As
     * during each creation, the node table may run out of space, GC might be called and could
     * delete the intermediately created nodes. Increasing and decreasing the reference counter
     * every time is more expensive than just putting the values on the stack, thus we use this data
     *  */
    private int[] workStack;
    /* Current top of the work stack. */
    private int workStackIndex = 0;

    NodeTable(int initialSize) {
        int size = Math.max(Primes.nextPrime(initialSize), MINIMUM_NODE_TABLE_SIZE);
        nodeData = new int[size];
        hashToChainStart = new int[size];
        hashChain = new int[size];

        firstFreeNode = FIRST_NODE;
        freeNodeCount = size - FIRST_NODE;
        biggestReferencedNode = PLACEHOLDER;
        biggestValidNode = PLACEHOLDER;

        Arrays.fill(nodeData, dataMakeInvalid());
        // Arrays.fill(hashToChainStart, NOT_A_NODE);
        assert Arrays.stream(hashToChainStart).allMatch(i -> i == PLACEHOLDER);

        // Just to ensure a fail-fast
        Arrays.fill(hashChain, 0, FIRST_NODE, Integer.MIN_VALUE);
        for (int i = FIRST_NODE; i < size - 1; i++) {
            hashChain[i] = i + 1;
        }
        hashChain[size - 1] = FIRST_NODE;

        workStack = new int[32];
    }

    // Structure

    public final int variable(int node) {
        assert isValidNode(node);
        return dataGetVariable(nodeData[node]);
    }

    public final int size() {
        return nodeData.length;
    }

    public int freeNodeCount() {
        return freeNodeCount;
    }

    protected abstract int nodeFor(int node);

    protected abstract boolean isConstantPointer(int pointer);

    protected abstract boolean isValidPointer(int pointer);

    // Creating nodes

    protected abstract int positiveHash(int node, int metaData);

    protected int modHash(int hashCode) {
        return hashCode % size();
    }

    protected void connectHashList(int node, int hash) {
        assert isValidNode(node);
        int hashChainStart = hashToChainStart[hash];
        int[] hashChain = this.hashChain;

        // Search the hash list if this node is already in there in order to avoid loops
        int chainLength = 1;
        int currentChain = hashChainStart;
        while (currentChain != PLACEHOLDER) {
            assert isValidNode(currentChain);
            if (currentChain == node) {
                // The node is already contained in the hash list
                return;
            }
            int next = hashChain[currentChain];
            assert next != currentChain;
            currentChain = next;
            chainLength += 1;
        }
        this.hashChainLookupLength += chainLength;
        this.hashChainLookups += 1;

        hashChain[node] = hashChainStart;
        hashToChainStart[hash] = node;
    }

    protected int findNode(int variable, int hash, IntPredicate lookupComparison) {
        int currentLookupNode = hashToChainStart[hash];
        assert currentLookupNode < size() : "Invalid previous entry for " + hash;

        int chainLookups = 1;
        this.hashChainLookups += 1;
        // Search for the node in the hash chain
        while (currentLookupNode != PLACEHOLDER) {
            if ((nodeData[currentLookupNode] >>> VARIABLE_OFFSET) == variable
                    && lookupComparison.test(currentLookupNode)) {
                this.hashChainLookupLength += chainLookups;
                return currentLookupNode;
            }
            assert currentLookupNode != hashChain[currentLookupNode];
            currentLookupNode = hashChain[currentLookupNode];
            chainLookups += 1;
        }
        this.hashChainLookupLength += chainLookups;
        return PLACEHOLDER;
    }

    protected int allocateNode(int variable, int modHash) {
        // Take next free node
        assert freeNodeCount > 0;
        createdNodes += 1;
        int freeNode = firstFreeNode;
        firstFreeNode = this.hashChain[freeNode];
        freeNodeCount--;
        assert !isValidNode(freeNode) : "Overwriting existing node " + freeNode;
        assert FIRST_NODE <= firstFreeNode && firstFreeNode < size() : "Invalid free node " + firstFreeNode;

        // Adjust and write node
        this.nodeData[freeNode] = variable << VARIABLE_OFFSET;
        if (biggestValidNode < freeNode) {
            biggestValidNode = freeNode;
        }
        connectHashList(freeNode, modHash);
        return freeNode;
    }

    // Reference counting

    public int nodeReferenceCount(int node) {
        assert isValidNodeOrPlaceholder(node);
        return node == PLACEHOLDER ? -1 : dataGetReferenceCountOrSaturated(nodeData[node]);
    }

    public void referenceNode(int node) {
        assert isValidNode(node);
        int metadata = nodeData[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (referenceCount == REFERENCE_COUNT_SATURATED) {
            return;
        }
        assert 0 <= dataGetReferenceCount(metadata);

        nodeData[node] = dataIncreaseReferenceCount(metadata);
        // Can't decrease approximateDeadNodeCount here - we may reference a node for the first time.
        if (node > biggestReferencedNode) {
            biggestReferencedNode = node;
        }
    }

    public void dereferenceNode(int node) {
        assert isValidNode(node);
        int metadata = nodeData[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (referenceCount == REFERENCE_COUNT_SATURATED) {
            return;
        }
        assert referenceCount > 0;
        if (referenceCount == 1) {
            // After decrease its 0

            // We are approximating the actual dead node count here - it could be the case that
            // this node was the only one keeping its children "alive" - similarly, this node could be
            // kept alive by other nodes "above" it.
            approximateDeadNodeCount++;
            if (node == biggestReferencedNode) {
                // Update biggestReferencedNode
                for (int i = biggestReferencedNode - 1; i >= FIRST_NODE; i--) {
                    if (dataIsReferencedOrSaturated(nodeData[i])) {
                        biggestReferencedNode = i;
                        break;
                    }
                }
            }
        }
        nodeData[node] = dataDecreaseReferenceCount(metadata);
    }

    /**
     * Counts the number of referenced or saturated nodes.
     *
     * @return Number of referenced nodes.
     */
    public int referencedNodeCount() {
        int[] nodeData = this.nodeData;
        int count = 0;

        for (int i = FIRST_NODE; i <= biggestReferencedNode; i++) {
            int metadata = nodeData[i];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count++;
            }
        }
        return count;
    }

    public int saturateNode(int node) {
        assert isValidNode(node);
        if (node > biggestReferencedNode) {
            biggestReferencedNode = node;
        }
        nodeData[node] = dataSaturate(nodeData[node]);
        return node;
    }

    /**
     * Checks if the given {@code node} is saturated. This can happen if the node is explicitly marked
     * as saturated or gets referenced too often.
     *
     * @param node The node to be checked
     * @return Whether the node is saturated
     * @see #saturateNode(int)
     */
    public boolean isSaturatedNode(int node) {
        assert isValidNodeOrPlaceholder(node);
        return node == PLACEHOLDER || dataIsSaturated(nodeData[node]);
    }

    // Counting

    /**
     * Counts the number of active nodes in the structure (i.e. the ones which are not invalid),
     * <b>excluding</b> constant nodes.
     *
     * @return Number of active nodes.
     */
    public int nodeCount() {
        // Strategy: We gather all root nodes (i.e. nodes which are referenced) on the mark stack, mark
        // all of their children, count all marked nodes and un-mark them.
        assert isNoneMarked();

        int count = 0;
        for (int node = FIRST_NODE; node < size(); node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count += markAllBelowNode(node);
            }
        }

        int unmarkedCount = unMarkAll();

        assert count == unmarkedCount;

        assert isNoneMarked();
        return count;
    }

    /**
     * Counts the number of nodes below the specified {@code node}.
     *
     * @param node The node to be counted.
     * @return The number of non-leaf nodes below {@code node}.
     */
    public int nodeCountBelow(int node) {
        assert isValidNodeOrPlaceholder(node);
        assert isNoneMarked();

        int count = markAllBelowNode(node);
        if (count > 0) {
            int unmarked = unMarkAllBelowNode(node);
            assert count == unmarked : "Expected " + count + " but only unmarked " + unmarked;
        }

        assert isNoneMarked();
        return count;
    }

    // Work stack

    private void ensureWorkStackSize(int size) {
        if (size < workStack.length) {
            return;
        }
        int newSize = workStack.length * 2;
        workStack = Arrays.copyOf(workStack, newSize);
    }

    boolean isWorkStackEmpty() {
        return workStackIndex == 0;
    }

    /**
     * Removes the topmost element from the stack.
     *
     * @see #pushToWorkStack(int)
     */
    void popFromWorkStack() {
        assert !isWorkStackEmpty();
        workStackIndex--;
    }

    /**
     * Removes the {@code amount} topmost elements from the stack.
     *
     * @param amount The amount of elements to be removed.
     * @see #pushToWorkStack(int)
     */
    void popFromWorkStack(int amount) {
        assert workStackIndex >= amount;
        workStackIndex -= amount;
    }

    /**
     * Pushes the given pointer onto the stack. While a pointer is on the work stack, it will not be garbage
     * collected. Hence, elements should be popped from the stack as soon as they are not used anymore.
     *
     * @param pointer The pointer to be pushed.
     * @return The given {@code pointer}, to be used for chaining.
     * @see #popFromWorkStack(int)
     */
    int pushToWorkStack(int pointer) {
        assert isValidPointer(pointer);
        ensureWorkStackSize(workStackIndex);
        workStack[workStackIndex] = pointer;
        workStackIndex += 1;
        return pointer;
    }

    void pushToWorkStack(int pointer1, int pointer2) {
        assert isValidPointer(pointer1) && isValidPointer(pointer2);
        ensureWorkStackSize(workStackIndex + 1);
        workStack[workStackIndex] = pointer1;
        workStack[workStackIndex + 1] = pointer2;
        workStackIndex += 2;
    }

    void pushToWorkStack(int pointer1, int pointer2, int pointer3) {
        assert isValidPointer(pointer1) && isValidPointer(pointer2) && isValidPointer(pointer3);
        ensureWorkStackSize(workStackIndex + 2);
        workStack[workStackIndex] = pointer1;
        workStack[workStackIndex + 1] = pointer2;
        workStack[workStackIndex + 2] = pointer3;
        workStackIndex += 3;
    }

    // Memory management

    public int approximateDeadNodeCount() {
        return approximateDeadNodeCount;
    }

    protected abstract boolean ensureCapacity();

    public void grow(int size) {
        int currentSize = size();

        growCount += 1;
        int newSize = Math.min(MAXIMAL_NODE_COUNT, Primes.nextPrime(size));
        assert currentSize < newSize : "Got new size " + newSize + " with old size " + currentSize;

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, currentSize, newSize});

        nodeData = Arrays.copyOf(this.nodeData, newSize); // NOPMD
        hashChain = Arrays.copyOf(this.hashChain, newSize); // NOPMD
        growTo(newSize);

        // We need to re-build hashToChainStart completely
        hashToChainStart = new int[newSize];

        //noinspection ConstantValue
        assert PLACEHOLDER == 0;
        // Otherwise: Arrays.fill(hashToChainStart, NOT_A_NODE);

        // Chain start and next is used in calls to connectHashList so first enlarge and then copy to local reference
        int[] nodeData = this.nodeData;
        int[] hashToChainStart = this.hashToChainStart;
        int[] hashChain = this.hashChain;

        // Invalidate the new nodes
        Arrays.fill(nodeData, currentSize, newSize, dataMakeInvalid());

        int firstFreeNode = currentSize;
        int freeNodeCount = newSize - currentSize;

        // Update the hash references and free nodes chain of the old nodes
        // Reverse direction to build the downward chain towards first free node

        hashChain[newSize - 1] = FIRST_NODE;
        for (int hash = newSize - 2; hash >= currentSize; hash--) {
            hashChain[hash] = hash + 1;
        }
        for (int hash = currentSize - 1; hash >= FIRST_NODE; hash--) {
            int data = nodeData[hash];
            if (!dataIsValid(data)) {
                hashChain[hash] = firstFreeNode;
                firstFreeNode = hash;
            }
        }

        // Need a second pass to build the existing nodes chain
        for (int node = currentSize - 1; node >= FIRST_NODE; node--) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                connectHashList(node, modHash(positiveHash(node, data)));
            } else {
                freeNodeCount++;
            }
        }

        this.firstFreeNode = firstFreeNode;
        this.freeNodeCount = freeNodeCount;
        this.nodeData = nodeData;
        this.hashToChainStart = hashToChainStart;
        this.hashChain = hashChain;

        assert check();

        logger.log(Level.FINE, "Finished growing the table");
    }

    protected abstract void growTo(int newSize);

    public void invalidateUnmarkedNodes() {
        int[] nodeData = this.nodeData;
        for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
            int metadata = nodeData[node];
            int unmarkedData = dataClearMark(metadata);
            if (metadata == unmarkedData) {
                // Node was unmarked, invalidate
                if (node == biggestValidNode) {
                    biggestValidNode--;
                }
                nodeData[node] = dataMakeInvalid();
            } else {
                nodeData[node] = unmarkedData;
            }
        }
    }

    public int reclaimUnmarkedNodes() {
        long startTimestamp = System.currentTimeMillis();

        int biggestValidNode = this.biggestValidNode;
        int[] nodeData = this.nodeData;
        int[] hashChain = this.hashChain;

        // Clear chain starts (we need to rebuild them) and push referenced nodes on the mark stack.
        // TODO Can we omit that complete invalidation / re-use the existing chains? Should be easy enough - its just
        //   closed hashing
        Arrays.fill(hashToChainStart, PLACEHOLDER);

        int previousFreeNodes = this.freeNodeCount;
        int firstFreeNode = FIRST_NODE;

        // Connect all definitely invalid nodes in the free node chain
        for (int i = size() - 1; i > biggestValidNode; i--) {
            hashChain[i] = firstFreeNode;
            firstFreeNode = i;
        }

        int referencedNodes = 0;
        // Rebuild hash chain for valid nodes, connect invalid nodes into the free chain
        // We need to rebuild the chain for unused nodes first as a smaller, unused node might be part
        // of a chain containing bigger nodes which are in use.
        for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
            int metadata = nodeData[node];
            int unmarkedData = dataClearMark(metadata);
            if (metadata == unmarkedData) {
                // This node is unmarked and thus unused
                nodeData[node] = dataMakeInvalid();
                hashChain[node] = firstFreeNode;
                firstFreeNode = node;
                if (node == biggestValidNode) {
                    biggestValidNode--;
                }
            } else {
                // This node is used
                referencedNodes += 1;
                nodeData[node] = unmarkedData;
                connectHashList(node, modHash(positiveHash(node, unmarkedData)));
            }
        }

        this.biggestValidNode = biggestValidNode;
        this.firstFreeNode = firstFreeNode;
        this.freeNodeCount = (size() - FIRST_NODE) - referencedNodes;
        approximateDeadNodeCount = 0;

        int collectedNodes = freeNodeCount - previousFreeNodes;
        this.garbageCollectionTime += System.currentTimeMillis() - startTimestamp;
        this.garbageCollectedNodeCount += collectedNodes;
        this.garbageCollectionCount += 1;

        assert check();
        assert isNoneMarked();
        return collectedNodes;
    }

    // Marking

    public boolean isNodeMarked(int node) {
        assert isValidNode(node);
        return dataIsMarked(nodeData[node]);
    }

    public int findFirstMarkedNode() {
        for (int i = FIRST_NODE; i < nodeData.length; i++) {
            if (dataIsMarked(nodeData[i])) {
                return i;
            }
        }
        return PLACEHOLDER;
    }

    public boolean isNoneMarked() {
        return findFirstMarkedNode() == PLACEHOLDER;
    }

    public int unMarkAll() {
        int unmarkedCount = 0;
        int[] nodeData = this.nodeData;

        for (int i = FIRST_NODE; i <= biggestValidNode; i++) {
            int metadata = nodeData[i];
            if (dataIsValid(metadata)) {
                int unmarkedData = dataClearMark(metadata);
                if (metadata != unmarkedData) { // Node was marked
                    unmarkedCount++;
                    nodeData[i] = unmarkedData;
                }
            }
        }

        assert isNoneMarked();
        return unmarkedCount;
    }

    // Tree marking

    public boolean isNoneMarkedBelowNode(int node) {
        assert isValidNodeOrPlaceholder(node);
        return node == PLACEHOLDER || doIsNoneMarkedBelow(node);
    }

    protected boolean doIsNoneMarkedBelow(int node) {
        assert isValidNode(node);
        return !isNodeMarked(node) && recurseNoneMarkedBelow(node);
    }

    protected abstract boolean recurseNoneMarkedBelow(int node);

    public boolean isAllMarkedBelowNode(int node) {
        assert isValidNode(node);
        return node == PLACEHOLDER || doIsAllMarkedBelow(node);
    }

    protected boolean doIsAllMarkedBelow(int node) {
        assert isValidNode(node);
        return isNodeMarked(node) && recurseIsAllMarkedBelow(node);
    }

    protected abstract boolean recurseIsAllMarkedBelow(int node);

    /**
     * Over-approximates the number of nodes below the specified {@code node}, possibly counting
     * shared subtrees multiple times. Guaranteed to be bigger or equal to {@link #nodeCountBelow(int)}.
     *
     * @param node The node to be counted.
     * @return An approximate number of non-leaf nodes below {@code node}.
     * @see #nodeCountBelow(int)
     */
    public int approximateNodeCount(int node) {
        assert isValidNodeOrPlaceholder(node);
        return node == PLACEHOLDER ? 0 : doApproximateNodeCount(node);
    }

    protected int doApproximateNodeCount(int node) {
        assert isValidNode(node);
        return 1 + recurseApproximateNodeCount(node);
    }

    protected abstract int recurseApproximateNodeCount(int node);

    public int unMarkAllBelowNode(int node) {
        /* The algorithm does not descend into trees whose root is unmarked, hence at the start of the
         * algorithm, all children of marked nodes must be marked to ensure correctness. */
        assert isValidNodeOrPlaceholder(node) && isAllMarkedBelowNode(node);
        int unmarkedCount = node == PLACEHOLDER ? 0 : doSetMarkBelow(node, false);
        assert isNoneMarkedBelowNode(node);
        return unmarkedCount;
    }

    public int markAllBelowNode(int node) {
        /* The algorithm does not descend into trees whose root is marked, hence at the start of the
         * algorithm, every marked node must have all of its descendants marked to ensure correctness. */
        assert isValidNodeOrPlaceholder(node);
        return node == PLACEHOLDER ? 0 : doSetMarkBelow(node, true);
    }

    protected int doSetMarkBelow(int node, boolean mark) {
        assert isValidNode(node);

        int metadata = nodeData[node];
        int modifiedData = dataSetMark(metadata, mark);
        if (metadata == modifiedData) {
            return 0;
        }
        nodeData[node] = modifiedData;
        return 1 + recurseSetMarkBelow(node, mark);
    }

    protected abstract int recurseSetMarkBelow(int node, boolean mark);

    public int markAllReferencedNodes() {
        int referencedNodes = 0;

        for (int i = 0; i < workStackIndex; i++) {
            int pointer = workStack[i];
            assert isValidPointer(pointer);
            int node = nodeFor(pointer);
            if (node != PLACEHOLDER) {
                referencedNodes += markAllBelowNode(node);
            }
        }

        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            int metadata = nodeData[node];
            if (node <= biggestReferencedNode && dataIsReferencedOrSaturated(metadata)) {
                referencedNodes += markAllBelowNode(node);
            }
        }

        return referencedNodes;
    }

    // Structural properties

    public void forEachVariable(int pointer, IntConsumer action) {
        assert isValidPointer(pointer);

        int node = nodeFor(pointer);
        if (node == PLACEHOLDER) {
            return;
        }
        assert isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, null, Integer.MAX_VALUE);
        unMarkAllBelowNode(node);
        assert isNoneMarkedBelowNode(node);
    }

    public void forEachVariable(int pointer, BitSet filter, IntConsumer action) {
        assert isValidPointer(pointer);

        int depthLimit = filter.length();
        if (depthLimit == 0) {
            return;
        }

        int node = nodeFor(pointer);
        if (node == PLACEHOLDER) {
            return;
        }
        assert isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, filter, depthLimit);
        doSetMarkBelow(node, false);
        assert isNoneMarkedBelowNode(node);
    }

    protected void doForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
        assert isValidNodeOrPlaceholder(node);

        if (node == PLACEHOLDER) {
            return;
        }

        int metadata = nodeData[node];
        int variable = dataGetVariable(metadata);
        if (variable >= depthLimit) {
            return;
        }
        int markedData = dataSetMark(metadata);
        if (metadata == markedData) {
            return;
        }
        nodeData[node] = markedData;

        if (filter == null || filter.get(variable)) {
            action.accept(variable);
        }

        recurseForEachVariable(node, action, filter, depthLimit);
    }

    protected abstract void recurseForEachVariable(
            int node, IntConsumer action, @Nullable BitSet filter, int depthLimit);

    protected abstract void forEachChildPointer(int node, IntConsumer action);

    // Integrity checks and utility

    public boolean isValidNodeOrPlaceholder(int node) {
        return node == 0 || isValidNode(node);
    }

    public boolean isValidNode(int node) {
        return FIRST_NODE <= node && node <= biggestValidNode && dataIsValid(nodeData[node]);
    }

    /**
     * Performs some integrity / invariant checks.
     *
     * @return True. This way, check can easily be called by an {@code assert} statement.
     */
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    boolean check() {
        logger.log(Level.FINER, "Running integrity check");
        Preconditions.checkState(biggestReferencedNode <= biggestValidNode);

        // Check the biggestValidNode variable
        Preconditions.checkState(
                dataIsValid(nodeData[biggestValidNode]),
                "Node (%s) is not valid or leaf",
                pointerToStringSupplier(biggestValidNode));
        for (int i = biggestValidNode + 1; i < size(); i++) {
            Preconditions.checkState(!dataIsValid(nodeData[i]), "Node (%s) is valid", pointerToStringSupplier(i));
        }

        // Check biggestReferencedNode variable
        Preconditions.checkState(
                dataIsReferencedOrSaturated(nodeData[biggestReferencedNode]),
                "Node (%s) is not referenced",
                pointerToStringSupplier(biggestReferencedNode));
        for (int i = biggestReferencedNode + 1; i < size(); i++) {
            Preconditions.checkState(
                    !dataIsReferencedOrSaturated(nodeData[i]), "Node (%s) is referenced", pointerToStringSupplier(i));
        }

        // Check invalid nodes are not referenced
        for (int node = FIRST_NODE; node <= biggestReferencedNode; node++) {
            if (dataIsReferencedOrSaturated(nodeData[node])) {
                Preconditions.checkState(
                        dataIsValid(nodeData[node]),
                        "Node (%s) is referenced but invalid",
                        pointerToStringSupplier(node));
            }
        }

        // Check if the number of free nodes is correct
        int count = 0;
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            if (dataIsValid(nodeData[node])) {
                count++;
            }
        }
        Preconditions.checkState(
                count == (size() - freeNodeCount - FIRST_NODE),
                "Invalid # of free nodes: #live=%d, size=%d, free=%d, expected=%d",
                count,
                size(),
                freeNodeCount,
                size() - freeNodeCount - FIRST_NODE);

        // Check each node's children
        for (int i = FIRST_NODE; i <= biggestValidNode; i++) {
            int node = i;
            int metadata = nodeData[node];
            if (dataIsValid(metadata)) {
                forEachChildPointer(node, child -> {
                    Preconditions.checkState(
                            isValidPointer(child),
                            "Invalid entry (%s) -> (%s)",
                            pointerToStringSupplier(node),
                            pointerToStringSupplier(child));
                    if (!isConstantPointer(child)) {
                        Preconditions.checkState(
                                dataGetVariable(metadata) < dataGetVariable(nodeData[nodeFor(child)]),
                                "(%s) -> (%s) does not descend tree",
                                pointerToStringSupplier(node),
                                pointerToStringSupplier(child));
                    }
                });
            }
        }

        // Check if there are duplicate nodes
        //noinspection MagicNumber
        int maximalNodeCountCheckedPairs = 2048;
        if (size() < maximalNodeCountCheckedPairs) {
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                int dataI = nodeData[node];
                if (dataIsValid(dataI)) {
                    for (int j = node + 1; j < size(); j++) {
                        int dataJ = nodeData[j];
                        if (dataIsValid(dataJ)) {
                            Preconditions.checkState(
                                    dataGetVariable(dataI) != dataGetVariable(dataJ) || !equalChildren(node, j),
                                    "Duplicate entries (%s) and (%s)",
                                    pointerToStringSupplier(node),
                                    pointerToStringSupplier(j));
                        }
                    }
                }
            }
        }

        int maximalNodeCountCheckedSet = 2048;
        if (size() < maximalNodeCountCheckedSet) {
            logger.log(Level.FINER, "Checking duplicate nodes");

            Set<Object> nodes = new HashSet<>();
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                if (isValidNode(node)) {
                    Preconditions.checkState(
                            nodes.add(representative(node)), "Duplicate entry (%s)", pointerToStringSupplier(node));
                }
            }
        }

        // Check the integrity of the hash chain
        for (int node = FIRST_NODE; node < size(); node++) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                // Check if each element is in its own hash chain
                int chainPosition = hashToChainStart[modHash(positiveHash(node, data))];
                boolean found = false;
                StringBuilder hashChain = new StringBuilder(32);
                while (chainPosition != PLACEHOLDER) {
                    hashChain.append(' ').append(chainPosition);
                    if (chainPosition == node) {
                        found = true;
                        break;
                    }
                    chainPosition = this.hashChain[chainPosition];
                }
                Preconditions.checkState(
                        found, "(%s) is not contained in it's hash list: %s", pointerToStringSupplier(node), hashChain);
            }
        }

        // Check firstFreeNode
        for (int i = FIRST_NODE; i < firstFreeNode; i++) {
            Preconditions.checkState(
                    dataIsValid(nodeData[i]),
                    "Invalid node (%s) smaller than firstFreeNode",
                    pointerToStringSupplier(i));
        }

        // Check free nodes chain
        int currentFreeNode = firstFreeNode;
        do {
            Preconditions.checkState(
                    !dataIsValid(nodeData[currentFreeNode]),
                    "Node (%s) in free node chain is valid",
                    pointerToStringSupplier(currentFreeNode));
            int nextFreeNode = hashChain[currentFreeNode];
            // This also excludes possible loops
            Preconditions.checkState(
                    nextFreeNode == FIRST_NODE || currentFreeNode < nextFreeNode,
                    "Free node chain is not well ordered, %s <= %s",
                    nextFreeNode,
                    currentFreeNode);
            Preconditions.checkState(
                    nextFreeNode < nodeData.length,
                    "Next free node points over horizon, %s -> %s (%s)",
                    currentFreeNode,
                    nextFreeNode,
                    nodeData.length);
            currentFreeNode = nextFreeNode;
        } while (currentFreeNode != FIRST_NODE);

        return true;
    }

    protected abstract boolean equalChildren(int node1, int node2);

    protected abstract Object representative(int node);

    // Printing

    protected abstract String format(int pointer);

    private String pointerToString(int pointer) {
        int node = nodeFor(pointer);
        int metadata = nodeData[node];
        if (!dataIsValid(metadata)) {
            return String.format("%5d| == INVALID ==", pointer);
        }
        String referenceCountString;
        if (dataIsSaturated(metadata)) {
            referenceCountString = "SAT";
        } else {
            referenceCountString = String.format("%3d", dataGetReferenceCount(metadata));
        }
        String baseString =
                String.format("%5s|%3d|%s|", format(pointer), dataGetVariable(metadata), referenceCountString);
        StringBuilder string = new StringBuilder(baseString);
        forEachChildPointer(node, child -> string.append(format(child)).append(' '));
        string.deleteCharAt(string.length() - 1);
        return string.toString();
    }

    private FunctionToStringSupplier pointerToStringSupplier(int pointer) {
        return new FunctionToStringSupplier(this, pointer);
    }

    /**
     * Generates a string representation of the given {@code pointer}.
     *
     * @param pointer The pointer to be printed.
     * @return A string representing the given pointer.
     */
    public String treeToString(int pointer) {
        assert isValidPointer(pointer);
        assert isNoneMarked();
        if (isConstantPointer(pointer)) {
            return String.format("Fun %s%n", format(pointer));
        }
        //noinspection MagicNumber
        StringBuilder builder = new StringBuilder(50)
                .append("Fun ")
                .append(format(pointer))
                .append('\n')
                .append("  NODE|VAR|REF| CHILDREN \n");
        treeToStringRecursive(pointer, builder);
        unMarkAllBelowNode(nodeFor(pointer));
        return builder.toString();
    }

    private void treeToStringRecursive(int pointer, StringBuilder builder) {
        if (isConstantPointer(pointer)) {
            return;
        }
        int node = nodeFor(pointer);
        int metadata = nodeData[node];
        if (dataIsMarked(metadata)) {
            return;
        }
        nodeData[node] = dataSetMark(metadata);
        builder.append(' ').append(pointerToString(pointer)).append('\n');
        forEachChildPointer(node, child -> treeToStringRecursive(child, builder));
    }

    // Statistics

    public String getStatistics() {
        int childrenCount = 0;
        int saturatedNodes = 0;
        int referencedNodes = 0;
        int validNodes = 0;

        for (int node = 0; node < size(); node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata)) {
                validNodes += 1;
                if (dataIsReferencedOrSaturated(metadata)) {
                    referencedNodes += 1;
                    childrenCount += markAllBelowNode(node);

                    if (dataIsSaturated(metadata)) {
                        saturatedNodes += 1;
                    }
                }
            }
        }

        unMarkAll();

        int[] chainLength = new int[size()];
        Deque<Integer> path = new ArrayDeque<>();
        int distinctChains = 0;

        for (int node = FIRST_NODE; node < size(); node++) {
            int metadata = nodeData[node];
            if (chainLength[node] > 0) {
                continue;
            }

            if (dataIsValid(metadata)) {
                int chainPosition = hashToChainStart[modHash(positiveHash(node, metadata))];
                int length = 0;
                while (chainPosition != 0) {
                    path.push(chainPosition);
                    if (chainPosition == node) {
                        distinctChains += 1;
                        break;
                    }
                    chainPosition = hashChain[chainPosition];
                    if (chainLength[chainPosition] > 0) {
                        length = chainLength[chainPosition];
                        break;
                    }
                }
                while (!path.isEmpty()) {
                    int pathNode = path.pop();
                    length += 1;
                    chainLength[pathNode] = length;
                }
            }
        }

        int sum = 0;
        int max = 0;
        for (int length : chainLength) {
            if (length == 0) {
                continue;
            }
            sum += 1;
            if (max < length) {
                max = length;
            }
        }

        return String.format(
                "Node table statistics:%n"
                        + "Table Size: %1$d, (largest ref: %2$d), %3$d created nodes%n"
                        + "%4$d valid nodes, %5$d referenced (%6$d saturated), %7$d children%n"
                        + "Hash table: %8$d chains %9$.2f load, %10$.2f avg, %11$d max; "
                        + "%12$d lookups, %13$.2f avg. len%n"
                        + "%14$d GC runs (%15$.2f s), %16$d freed, %17$d grows",
                size(),
                biggestReferencedNode,
                createdNodes,
                validNodes,
                referencedNodes,
                saturatedNodes,
                childrenCount,
                distinctChains,
                sum * 1.0 / size(),
                sum * 1.0 / distinctChains,
                max,
                hashChainLookups,
                hashChainLookupLength * 1.0 / hashChainLookups,
                garbageCollectionCount,
                garbageCollectionTime / 1000.0,
                garbageCollectedNodeCount,
                growCount);
    }

    private static final class FunctionToStringSupplier {
        private final int node;
        private final NodeTable table;

        public FunctionToStringSupplier(NodeTable table, int node) {
            this.table = table;
            this.node = node;
        }

        @Override
        public String toString() {
            return table.pointerToString(node);
        }
    }

    // Static helper functions

    private static int dataGetVariable(int metadata) {
        assert dataIsValid(metadata);
        return metadata >>> VARIABLE_OFFSET;
    }

    private static boolean dataIsValid(int metadata) {
        return (metadata >>> VARIABLE_OFFSET) != INVALID_NODE_VARIABLE;
    }

    private static int dataMakeInvalid() {
        return INVALID_NODE_VARIABLE << VARIABLE_OFFSET;
    }

    private static boolean dataIsSaturated(int metadata) {
        return dataGetReferenceCountUnsafe(metadata) == REFERENCE_COUNT_SATURATED;
    }

    private static int dataSaturate(int metadata) {
        return metadata | (REFERENCE_COUNT_SATURATED << REFERENCE_COUNT_OFFSET);
    }

    private static boolean dataIsReferencedOrSaturated(int metadata) {
        return dataGetReferenceCountUnsafe(metadata) > 0;
    }

    private static int dataGetReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata);
        return (metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK;
    }

    private static int dataGetReferenceCountOrSaturated(int metadata) {
        int count = dataGetReferenceCountUnsafe(metadata);
        return count == REFERENCE_COUNT_SATURATED ? -1 : count;
    }

    private static int dataGetReferenceCountUnsafe(int metadata) {
        return (metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK;
    }

    private static int dataIncreaseReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata);
        return metadata + 2;
    }

    private static int dataDecreaseReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata) && dataGetReferenceCount(metadata) > 0;
        return metadata - 2;
    }

    private static int dataSetMark(int metadata, boolean mark) {
        return mark ? dataSetMark(metadata) : dataClearMark(metadata);
    }

    private static int dataSetMark(int metadata) {
        return metadata | 1;
    }

    private static int dataClearMark(int metadata) {
        return metadata & ~1;
    }

    private static boolean dataIsMarked(int metadata) {
        return (metadata & 1) != 0;
    }

    abstract static class Binary extends NodeTable {
        /* Low and high successors of each node */
        // There seems to be no performance difference to packing these into one array
        protected int[] low;
        protected int[] high;

        Binary(int initialSize) {
            super(initialSize);
            int size = size();
            low = new int[size];
            high = new int[size];
        }

        @Override
        protected void growTo(int newSize) {
            low = Arrays.copyOf(low, newSize);
            high = Arrays.copyOf(high, newSize);
        }

        @Override
        protected void forEachChildPointer(int node, IntConsumer action) {
            assert isValidNode(node);
            action.accept(low[node]);
            action.accept(high[node]);
        }

        @Override
        protected boolean equalChildren(int node1, int node2) {
            return low(node1) == low(node2) && high(node1) == high(node2);
        }

        @Override
        protected Object representative(int node) {
            return new BinaryNode(variable(node), low(node), high(node));
        }

        public int low(int node) {
            assert isValidNode(node);
            return low[node];
        }

        public int high(int node) {
            assert isValidNode(node);
            return high[node];
        }

        @Override
        protected int positiveHash(int node, int metadata) {
            assert FIRST_NODE <= node && dataIsValid(metadata);
            return hash(dataGetVariable(metadata), low(node), high(node));
        }

        private int hash(int variable, int low, int high) {
            // TODO This silly "hash function" seems to be significantly better than any "proper" one and I have no idea
            // why
            //   Conjecture: This leads to "locality" in the hash chain?
            int hashCode = low < 0 ? -low + high + variable : low + high + variable;
            return (hashCode < 0 ? ~hashCode : hashCode);
        }

        public int makeNode(int variable, int lowPointer, int highPointer) {
            assert 0 <= variable && variable < INVALID_NODE_VARIABLE;
            assert isConstantPointer(lowPointer) || variable < variable(nodeFor(lowPointer));
            assert isConstantPointer(highPointer) || variable < variable(nodeFor(highPointer));
            assert highPointer != lowPointer;

            int hash = hash(variable, lowPointer, highPointer);

            int modHash = modHash(hash);
            int lookup = findNode(variable, modHash, node -> low[node] == lowPointer && high[node] == highPointer);
            if (lookup != PLACEHOLDER) {
                return lookup;
            }
            if (ensureCapacity()) {
                modHash = modHash(hash);
            }
            int freeNode = allocateNode(variable, modHash);
            this.low[freeNode] = lowPointer;
            this.high[freeNode] = highPointer;
            return freeNode;
        }

        // Utility class to check for reduced-ness

        private static final class BinaryNode {
            final int var;
            final int low;
            final int high;

            BinaryNode(int var, int low, int high) {
                this.var = var;
                this.low = low;
                this.high = high;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof BinaryNode)) {
                    return false;
                }
                BinaryNode node = (BinaryNode) o;
                return var == node.var && low == node.low && high == node.high;
            }

            @Override
            public int hashCode() {
                return HashUtil.hash(var, low, high);
            }
        }
    }

    abstract static class Multi extends NodeTable {
        protected int[][] tree;

        Multi(int initialSize) {
            super(initialSize);
            int size = size();
            tree = new int[size][];
        }

        @Override
        protected void growTo(int newSize) {
            tree = Arrays.copyOf(tree, newSize);
        }

        @Override
        protected void forEachChildPointer(int node, IntConsumer action) {
            assert isValidNode(node);
            for (int child : tree[node]) {
                action.accept(child);
            }
        }

        @Override
        protected boolean equalChildren(int node1, int node2) {
            return Arrays.equals(tree[node1], tree[node2]);
        }

        @Override
        protected Object representative(int node) {
            return new MultiNode(variable(node), tree[node]);
        }

        public int follow(int node, int value) {
            assert isValidNode(node);
            return tree[node][value];
        }

        @Override
        protected int positiveHash(int node, int metadata) {
            assert FIRST_NODE <= node && dataIsValid(metadata);
            return hash(dataGetVariable(metadata), tree[node]);
        }

        private int hash(int variable, int[] children) {
            int hashCode = variable;
            for (int child : children) {
                hashCode += child < 0 ? -child : child;
            }
            return (hashCode < 0 ? ~hashCode : hashCode);
        }

        public int makeNode(int variable, int[] children) {
            assert 0 <= variable;
            assert Arrays.stream(children)
                    .allMatch(child -> isConstantPointer(child) || variable < variable(nodeFor(child)));
            assert Arrays.stream(children).distinct().count() > 1;

            int hash = hash(variable, children);

            int modHash = modHash(hash);
            int lookup = findNode(variable, modHash, node -> Arrays.equals(children, tree[node]));
            if (lookup != PLACEHOLDER) {
                return lookup;
            }
            if (ensureCapacity()) {
                modHash = modHash(hash);
            }
            int freeNode = allocateNode(variable, modHash);
            this.tree[freeNode] = children;
            return freeNode;
        }

        public int[] children(int node) {
            assert isValidNode(node);
            return tree[node];
        }

        private static final class MultiNode {
            final int var;
            final int[] children;

            MultiNode(int var, int[] children) {
                this.var = var;
                this.children = children;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof MultiNode)) {
                    return false;
                }
                MultiNode node = (MultiNode) o;
                return var == node.var && Arrays.equals(children, node.children);
            }

            @Override
            public int hashCode() {
                return var + Arrays.hashCode(children);
            }
        }
    }
}
