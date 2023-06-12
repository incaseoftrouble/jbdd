/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/* Implementation notes:
 * - Many of the methods are practically copy-paste of each other except for a few variables and
 *   corner cases, as the structure of BDD algorithms is the same for most of the operations.
 * - Due to the implementation of all operations, variable numbers increase while descending the
 *   tree of a particular node.
 */
// TODO Complement edges on high nodes
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "PMD.AssignmentInOperand",
    "PMD.TooManyFields",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "ValueOfIncrementOrDecrementUsed",
    "NestedAssignment"
})
final class BddImpl implements Bdd {
    // Use 0 as "not a node" to make re-allocations slightly more efficient
    private static final int NOT_A_NODE = 0;
    private static final int FIRST_NODE = 1;

    private static final Logger logger = Logger.getLogger(BddImpl.class.getName());
    private static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final int TRUE_NODE = -1;
    private static final int FALSE_NODE = -2;

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
        assert VARIABLE_BIT_SIZE + REFERENCE_COUNT_BIT_SIZE + 1 == Integer.SIZE;
    }

    private final BddCache cache;
    private int numberOfVariables;
    private int[] variableNodes;
    private final BddConfiguration configuration;
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

    /* The work stack is used to store intermediate nodes created by some BDD operations. While
     * constructing a new BDD, e.g. v1 and v2, we may need to create multiple intermediate BDDs. As
     * during each creation the node table may run out of space, GC might be called and could
     * delete the intermediately created nodes. As increasing and decreasing the reference counter
     * every time is more expensive than just putting the values on the stack, we use this data
     * structure. */
    private int[] workStack;
    /* Current top of the work stack. */
    private int workStackIndex = 0;

    /* Low and high successors of each node */
    private int[] tree;

    /* Stores the meta-data for BDD nodes, namely the variable number, reference count and a mask used
     * by various internal algorithms. These values are manipulated through static helper functions.
     *
     * Layout: <---VAR---><---REF---><MASK> */
    private int[] nodeData;

    /* Hash map for existing nodes and a linked list for free nodes. The semantics of the "next
     * chain entry" change, depending on whether the node is valid or not.
     *
     * When a node with a certain hash is created, we add a pointer to the corresponding hash bucket
     * obtainable by hashToChainStart. Whenever we add another node with the same value, this
     * node gets added to the chain and one can traverse the chain by repeatedly accessing
     * hashChain on the chain start. If however a node is invalid, the "next chain
     * entry" points to the next free node. This saves some time when creating nodes, as we don't have
     * to scan through our BDD to find the next node which we can update a value.
     */
    private int[] hashToChainStart;
    private int[] hashChain;

    // Iterative stack
    private final boolean iterative;
    private int[] cacheStackHash = EMPTY_INT_ARRAY;
    private int[] cacheStackFirstArg = EMPTY_INT_ARRAY;
    private int[] cacheStackSecondArg = EMPTY_INT_ARRAY;
    private int[] cacheStackThirdArg = EMPTY_INT_ARRAY;
    private int[] branchStackParentVar = EMPTY_INT_ARRAY;
    private int[] branchStackFirstArg = EMPTY_INT_ARRAY;
    private int[] branchStackSecondArg = EMPTY_INT_ARRAY;
    private int[] branchStackThirdArg = EMPTY_INT_ARRAY;
    private int[] markStack = EMPTY_INT_ARRAY;

    // Statistics
    private long createdNodes = 0;
    private long hashChainLookups = 0;
    private long hashChainLookupLength = 0;
    private long growCount = 0;
    private long garbageCollectionCount = 0;
    private long garbageCollectedNodeCount = 0;
    private long garbageCollectionTime = 0;

    BddImpl(boolean iterative, BddConfiguration configuration) {
        this.configuration = configuration;
        this.iterative = iterative;

        int initialSize = Math.max(Primes.nextPrime(configuration.initialSize()), MINIMUM_NODE_TABLE_SIZE);
        tree = new int[2 * initialSize];
        nodeData = new int[initialSize];
        hashToChainStart = new int[initialSize];
        hashChain = new int[initialSize];

        firstFreeNode = FIRST_NODE;
        freeNodeCount = initialSize - FIRST_NODE;
        biggestReferencedNode = NOT_A_NODE;
        biggestValidNode = NOT_A_NODE;

        Arrays.fill(nodeData, dataMakeInvalid());
        // Arrays.fill(hashToChainStart, NOT_A_NODE);

        // Just to ensure a fail-fast
        Arrays.fill(hashChain, 0, FIRST_NODE, Integer.MIN_VALUE);
        for (int i = FIRST_NODE; i < initialSize - 1; i++) {
            hashChain[i] = i + 1;
        }
        hashChain[initialSize - 1] = FIRST_NODE;

        workStack = new int[32];
        cache = new BddCache(this);
        variableNodes = new int[32];
        numberOfVariables = 0;
    }

    // Reference counting

    @Override
    public int referenceCount(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return -1;
        }
        int metadata = nodeData[node];
        if (dataIsSaturated(metadata)) {
            return -1;
        }
        return dataGetReferenceCount(metadata);
    }

    @Override
    public int reference(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return node;
        }
        int metadata = nodeData[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (referenceCount == REFERENCE_COUNT_SATURATED) {
            return node;
        }
        assert 0 <= dataGetReferenceCount(metadata);

        nodeData[node] = dataIncreaseReferenceCount(metadata);
        // Can't decrease approximateDeadNodeCount here - we may reference a node for the first time.
        if (node > biggestReferencedNode) {
            biggestReferencedNode = node;
        }
        return node;
    }

    @Override
    public int dereference(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return node;
        }
        int metadata = nodeData[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (referenceCount == REFERENCE_COUNT_SATURATED) {
            return node;
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
        return node;
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

    int saturateNode(int node) {
        assert isNodeValidOrLeaf(node);
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
    public boolean isNodeSaturated(int node) {
        assert isNodeValidOrLeaf(node);
        return isLeaf(node) || dataIsSaturated(nodeData[node]);
    }

    // Memory management

    int approximateDeadNodeCount() {
        return approximateDeadNodeCount;
    }

    /**
     * Perform garbage collection by freeing up dead nodes.
     *
     * @return Number of freed nodes.
     */
    public int forceGc() {
        int freedNodes = doGarbageCollection(0);
        cache.invalidate();
        return freedNodes;
    }

    /**
     * Tries to free space by garbage collection and, if that does not yield enough free nodes,
     * re-sizes the table, recreating hashes.
     *
     * @return Whether the table size has changed.
     */
    boolean ensureCapacity() {
        assert check();

        if (configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
            logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes", new Object[] {
                this, tableSize(), approximateDeadNodeCount
            });

            @SuppressWarnings("NumericCastThatLosesPrecision")
            int minimumFreeNodeCount = (int) (tableSize() * configuration.minimumFreeNodePercentageAfterGc());
            int clearedNodes = doGarbageCollection(minimumFreeNodeCount);
            if (clearedNodes == -1) {
                logger.log(Level.FINE, "Not enough free nodes");
            } else {
                logger.log(Level.FINE, "Collected {0} nodes", clearedNodes);

                // Force all caches to be wiped out
                // TODO Could do partial invalidation here
                cache.invalidate();
                return false;
            }
        }

        growCount += 1;
        int oldSize = tableSize();
        @SuppressWarnings("NumericCastThatLosesPrecision")
        int newSize =
                Math.min(MAXIMAL_NODE_COUNT, Primes.nextPrime((int) Math.ceil(oldSize * configuration.growthFactor())));
        assert oldSize < newSize : "Got new size " + newSize + " with old size " + oldSize;

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, oldSize, newSize});

        tree = Arrays.copyOf(tree, 2 * newSize);
        nodeData = Arrays.copyOf(this.nodeData, newSize); // NOPMD
        hashChain = Arrays.copyOf(this.hashChain, newSize); // NOPMD
        // We need to re-build hashToChainStart completely
        hashToChainStart = new int[newSize];
        if (placeholder() != 0) { // Leave this as a reminder
            Arrays.fill(hashToChainStart, NOT_A_NODE);
        }

        // Chain start and next is used in calls to connectHashList so first enlarge and then copy to local reference
        int[] nodeData = this.nodeData;
        int[] hashToChainStart = this.hashToChainStart;
        int[] hashChain = this.hashChain;

        // Invalidate the new nodes
        Arrays.fill(nodeData, oldSize, newSize, dataMakeInvalid());

        int firstFreeNode = oldSize;
        int freeNodeCount = newSize - oldSize;

        // Update the hash references and free nodes chain of the old nodes
        // Reverse direction to build the downward chain towards first free node

        hashChain[newSize - 1] = FIRST_NODE;
        for (int hash = newSize - 2; hash >= oldSize; hash--) {
            hashChain[hash] = hash + 1;
        }
        for (int hash = oldSize - 1; hash >= FIRST_NODE; hash--) {
            int data = nodeData[hash];
            if (!dataIsValid(data)) {
                hashChain[hash] = firstFreeNode;
                firstFreeNode = hash;
            }
        }

        // Need a second pass to build the existing nodes chain
        for (int node = oldSize - 1; node >= FIRST_NODE; node--) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                connectHashList(node, hashNode(node, data));
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

        cache.invalidate();
        logger.log(Level.FINE, "Finished growing the table");
        return true;
    }

    private int doGarbageCollection(int minimumFreeNodeCount) {
        assert check();
        long startTimestamp = System.currentTimeMillis();

        int referencedNodes = 0;
        for (int i = 0; i < workStackIndex; i++) {
            int node = workStack[i];
            if (!isLeaf(node) && isNodeValid(node)) {
                referencedNodes += markAllBelow(node);
            }
        }

        int biggestValidNode = this.biggestValidNode;
        int biggestReferencedNode = this.biggestReferencedNode;
        int[] nodeData = this.nodeData;
        int[] hashChain = this.hashChain;

        for (int i = FIRST_NODE; i <= biggestValidNode; i++) {
            int metadata = nodeData[i];
            if (i <= biggestReferencedNode && dataIsReferencedOrSaturated(metadata)) {
                referencedNodes += markAllBelow(i);
            }
        }

        int freeNodeCount = (tableSize() - FIRST_NODE) - referencedNodes;
        if (freeNodeCount < minimumFreeNodeCount) {
            unMarkAll();
            return -1;
        }

        // Clear chain starts (we need to rebuild them) and push referenced nodes on the mark stack.
        // TODO Can we omit that complete invalidation / re-use the existing chains? Should be easy enough - its just
        // open hashing
        Arrays.fill(hashToChainStart, NOT_A_NODE);

        int previousFreeNodes = this.freeNodeCount;
        int firstFreeNode = FIRST_NODE;

        // Connect all definitely invalid nodes in the free node chain
        for (int i = tableSize() - 1; i > biggestValidNode; i--) {
            hashChain[i] = firstFreeNode;
            firstFreeNode = i;
        }

        // Rebuild hash chain for valid nodes, connect invalid nodes into the free chain
        // We need to rebuild the chain for unused nodes first as a smaller, unused node might be part
        // of a chain containing bigger nodes which are in use.
        for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
            int metadata = nodeData[node];
            int unmarkedData = dataClearMark(metadata);
            if (metadata == unmarkedData) {
                // This node is unmark and thus unused
                nodeData[node] = dataMakeInvalid();
                hashChain[node] = firstFreeNode;
                firstFreeNode = node;
                if (node == biggestValidNode) {
                    biggestValidNode--;
                }
            } else {
                // This node is used
                nodeData[node] = unmarkedData;
                connectHashList(node, hashNode(node, unmarkedData));
            }
        }

        this.biggestValidNode = biggestValidNode;
        this.firstFreeNode = firstFreeNode;
        this.freeNodeCount = freeNodeCount;
        approximateDeadNodeCount = 0;

        assert check();

        int collectedNodes = freeNodeCount - previousFreeNodes;
        this.garbageCollectedNodeCount += collectedNodes;
        this.garbageCollectionCount += 1;
        this.garbageCollectionTime += System.currentTimeMillis() - startTimestamp;
        return collectedNodes;
    }

    private int hashNode(int node, int metadata) {
        assert dataIsValid(metadata);
        return hash(dataGetVariable(metadata), low(node), high(node));
    }

    private int hash(int variable, int low, int high) {
        int tableSize = tableSize();
        int hash = HashUtil.hash(low, high, variable) % tableSize;
        if (hash < 0) {
            return hash + tableSize;
        }
        return hash;
    }

    private void connectHashList(int node, int hash) {
        assert isNodeValid(node) && 0 <= hash && hash == hashNode(node, nodeData[node]);
        int hashChainStart = hashToChainStart[hash];
        int[] hashChain = this.hashChain;

        // Search the hash list if this node is already in there in order to avoid loops
        int chainLength = 1;
        int currentChain = hashChainStart;
        while (currentChain != NOT_A_NODE) {
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

    // Marking

    private boolean isNodeMarked(int node) {
        assert isNodeValid(node);
        return dataIsMarked(nodeData[node]);
    }

    private boolean isNoneMarked() {
        return findFirstMarked() == NOT_A_NODE;
    }

    private boolean isNoneMarkedBelowRecursive(int node) {
        return isLeaf(node)
                || !isNodeMarked(node)
                        && isNoneMarkedBelowRecursive(low(node))
                        && isNoneMarkedBelowRecursive(high(node));
    }

    private boolean isAllMarkedBelowRecursive(int node) {
        return isLeaf(node)
                || isNodeMarked(node) && isAllMarkedBelowRecursive(low(node)) && isAllMarkedBelowRecursive(high(node));
    }

    private int findFirstMarked() {
        for (int i = FIRST_NODE; i < nodeData.length; i++) {
            if (dataIsMarked(nodeData[i])) {
                return i;
            }
        }
        return NOT_A_NODE;
    }

    private int markAllBelow(int node) {
        /* The algorithm does not descend into trees whose root is marked, hence at the start of the
         * algorithm, every marked node must have all of its descendants marked to ensure correctness. */
        assert isNodeValidOrLeaf(node);
        return iterative ? markAllBelowIterative(node) : markAllBelowRecursive(node);
    }

    private int markAllBelowIterative(int node) {
        int[] tree = this.tree;
        int[] nodeData = this.nodeData;
        int[] stack = this.markStack;

        int stackIndex = 0;
        int unmarkedCount = 0;
        int current = node;
        while (true) {
            while (!isLeaf(current)) {
                int metadata = nodeData[current];
                int markedData = dataSetMark(metadata);

                if (metadata == markedData) {
                    // Node was marked
                    break;
                }

                nodeData[current] = markedData;
                unmarkedCount++;

                stack[stackIndex] = tree[2 * current + 1];
                stackIndex += 1;

                current = tree[2 * current];
            }

            if (stackIndex == 0) {
                break;
            }
            stackIndex -= 1;
            current = stack[stackIndex];
        }
        return unmarkedCount;
    }

    private int markAllBelowRecursive(int node) {
        assert isNodeValidOrLeaf(node);

        if (isLeaf(node)) {
            return 0;
        }

        int metadata = nodeData[node];
        int markedData = dataSetMark(metadata);

        if (metadata == markedData) {
            return 0;
        }
        nodeData[node] = markedData;
        return 1 + markAllBelowRecursive(low(node)) + markAllBelowRecursive(high(node));
    }

    private int unMarkAll() {
        /* The algorithm does not descend into trees whose root is unmarked, hence at the start of the
         * algorithm, all children of marked nodes must be marked to ensure correctness. */
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

    private int unMarkAllBelow(int node) {
        assert isNodeValidOrLeaf(node) && isAllMarkedBelowRecursive(node);
        int unmarkedCount = iterative ? unMarkAllBelowIterative(node) : unmarkAllBelowRecursive(node);
        assert isNoneMarkedBelowRecursive(node);
        return unmarkedCount;
    }

    private int unMarkAllBelowIterative(int node) {
        int[] nodeData = this.nodeData;
        int[] tree = this.tree;
        int[] stack = this.markStack;

        int stackIndex = 0;
        int unmarkedCount = 0;
        int current = node;
        while (true) {
            while (!isLeaf(current)) {
                int metadata = nodeData[current];
                int unmarkedData = dataClearMark(metadata);

                if (metadata == unmarkedData) {
                    // Node was not marked
                    break;
                }

                nodeData[current] = unmarkedData;
                unmarkedCount++;

                stack[stackIndex] = tree[2 * current + 1];
                stackIndex += 1;
                current = tree[2 * current];
            }

            if (stackIndex == 0) {
                break;
            }
            stackIndex -= 1;
            current = stack[stackIndex];
        }
        return unmarkedCount;
    }

    private int unmarkAllBelowRecursive(int node) {
        assert isNodeValidOrLeaf(node);

        if (isLeaf(node)) {
            return 0;
        }

        int metadata = nodeData[node];
        int unmarkedData = dataClearMark(metadata);

        if (metadata == unmarkedData) {
            return 0;
        }
        nodeData[node] = unmarkedData;
        return 1 + unmarkAllBelowRecursive(low(node)) + unmarkAllBelowRecursive(high(node));
    }

    public int tableSize() {
        return nodeData.length;
    }

    // Work stack

    private void ensureWorkStackSize(int size) {
        if (size < workStack.length) {
            return;
        }
        int newSize = workStack.length * 2;
        workStack = Arrays.copyOf(workStack, newSize);
    }

    // Visible for testing
    boolean isWorkStackEmpty() {
        return workStackIndex == 0;
    }

    /**
     * Removes the topmost element from the stack.
     *
     * @see #pushToWorkStack(int)
     */
    void popWorkStack() {
        assert !isWorkStackEmpty();
        workStackIndex--;
    }

    /**
     * Removes the {@code amount} topmost elements from the stack.
     *
     * @param amount The amount of elements to be removed.
     * @see #pushToWorkStack(int)
     */
    private void popWorkStack(int amount) {
        assert workStackIndex >= amount;
        workStackIndex -= amount;
    }

    private int peekWorkStack() {
        assert !isWorkStackEmpty();
        return workStack[workStackIndex - 1];
    }

    private int peekAndPopWorkStack() {
        assert !isWorkStackEmpty();
        workStackIndex -= 1;
        return workStack[workStackIndex];
    }

    /**
     * Pushes the given node onto the stack. While a node is on the work stack, it will not be garbage
     * collected. Hence, elements should be popped from the stack as soon as they are not used
     * anymore.
     *
     * @param node The node to be pushed.
     * @return The given {@code node}, to be used for chaining.
     * @see #popWorkStack(int)
     */
    int pushToWorkStack(int node) {
        assert isNodeValidOrLeaf(node);
        ensureWorkStackSize(workStackIndex);
        workStack[workStackIndex] = node;
        workStackIndex += 1;
        return node;
    }

    // Nodes

    private int makeNode(int variable, int low, int high) {
        assert 0 <= variable && variable < INVALID_NODE_VARIABLE;
        assert (isLeaf(low) || variable < variable(low));
        assert (isLeaf(high) || variable < variable(high));

        if (low == high) {
            return low;
        }

        createdNodes += 1;

        int[] tree = this.tree;
        int[] nodeData = this.nodeData;
        int[] hashChain = this.hashChain;

        int hash = hash(variable, low, high);
        int currentLookupNode = hashToChainStart[hash];
        assert currentLookupNode < tableSize() : "Invalid previous entry for " + hash;

        // Search for the node in the hash chain
        int chainLookups = 1;
        while (currentLookupNode != NOT_A_NODE) {
            if ((nodeData[currentLookupNode] >>> VARIABLE_OFFSET) == variable
                    && tree[currentLookupNode * 2] == low
                    && tree[currentLookupNode * 2 + 1] == high) {
                return currentLookupNode;
            }
            int next = hashChain[currentLookupNode];
            assert next != currentLookupNode;
            currentLookupNode = next;
            chainLookups += 1;
        }
        this.hashChainLookupLength += chainLookups;
        this.hashChainLookups += 1;

        // Check we have enough space to add the node
        assert freeNodeCount > 0;
        if (freeNodeCount == 1) {
            // We need a starting point for the free chain node, hence grow if only one node is remaining
            // instead of occupying that node
            // TODO Instead, we should try to clear / grow if freeNodes < load * total size, so that the
            //  hash table is less pressured
            if (ensureCapacity()) { // NOPMD
                // Table size has changed, hence re-hash
                hash = hash(variable, low, high);
            }
        }

        // Here we need to use this.tree etc. since we may have GC'd in between

        // Take next free node
        int freeNode = firstFreeNode;
        firstFreeNode = this.hashChain[firstFreeNode];
        freeNodeCount--;
        assert !isNodeValidOrLeaf(freeNode) : "Overwriting existing node " + freeNode;
        assert FIRST_NODE <= firstFreeNode && firstFreeNode < tableSize() : "Invalid free node " + firstFreeNode;

        // Adjust and write node
        this.tree[2 * freeNode] = low;
        this.tree[2 * freeNode + 1] = high;
        this.nodeData[freeNode] = variable << VARIABLE_OFFSET;
        if (biggestValidNode < freeNode) {
            biggestValidNode = freeNode;
        }
        connectHashList(freeNode, hash);
        return freeNode;
    }

    @Override
    public int low(int node) {
        assert isNodeValid(node);
        return tree[2 * node];
    }

    @Override
    public int high(int node) {
        assert isNodeValid(node);
        return tree[2 * node + 1];
    }

    @Override
    public int variable(int node) {
        assert isNodeValid(node);
        return dataGetVariable(nodeData[node]);
    }

    @Override
    public boolean isLeaf(int node) {
        assert -2 <= node && node < tableSize();
        return node < 0;
    }

    public boolean isNodeValid(int node) {
        assert -2 <= node && node < tableSize();
        return FIRST_NODE <= node && node <= biggestValidNode && dataIsValid(nodeData[node]);
    }

    /**
     * Determines if the given {@code node} is either a root node or valid. For most operations it is
     * required that this is the case.
     *
     * @param node The node to be checked.
     * @return If {@code} is valid or root node.
     * @see #isLeaf(int)
     */
    public boolean isNodeValidOrLeaf(int node) {
        assert -2 <= node && node < tableSize();
        return isLeaf(node) || isNodeValid(node);
    }

    // Variables and base nodes

    @Override
    public int trueNode() {
        return TRUE_NODE;
    }

    @Override
    public int falseNode() {
        return FALSE_NODE;
    }

    @Override
    public int placeholder() {
        return NOT_A_NODE;
    }

    @Override
    public int numberOfVariables() {
        return numberOfVariables;
    }

    @Override
    public int variableNode(int variableNumber) {
        assert 0 <= variableNumber && variableNumber < numberOfVariables;
        return variableNodes[variableNumber];
    }

    @Override
    public int createVariable() {
        int variableNode = saturateNode(makeNode(numberOfVariables, FALSE_NODE, TRUE_NODE));
        saturateNode(makeNode(numberOfVariables, TRUE_NODE, FALSE_NODE));

        if (numberOfVariables == variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, variableNodes.length * 2);
        }
        variableNodes[numberOfVariables] = variableNode;
        numberOfVariables++;

        cache.variablesChanged();
        if (iterative) {
            growStacks();
        }
        ensureWorkStackSize(numberOfVariables * 2);

        return variableNode;
    }

    @Override
    public int[] createVariables(int count) {
        if (count == 0) {
            return EMPTY_INT_ARRAY;
        }
        if (count == 1) {
            return new int[] {createVariable()};
        }

        int newSize = numberOfVariables + count;
        if (newSize >= variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, Math.max(variableNodes.length * 2, newSize));
        }

        int[] newVariableNodes = new int[count];

        for (int i = 0; i < count; i++) {
            int variable = numberOfVariables + i;

            int variableNode = saturateNode(makeNode(variable, FALSE_NODE, TRUE_NODE));
            saturateNode(makeNode(variable, TRUE_NODE, FALSE_NODE));
            newVariableNodes[i] = variableNode;
            this.variableNodes[variable] = variableNode;
        }
        numberOfVariables += count;

        cache.variablesChanged();
        if (iterative) {
            growStacks();
        }
        ensureWorkStackSize(numberOfVariables * 2);

        return newVariableNodes;
    }

    @Override
    public boolean isVariable(int node) {
        if (isLeaf(node)) {
            return false;
        }
        return low(node) == FALSE_NODE && high(node) == TRUE_NODE;
    }

    @Override
    public boolean isVariableNegated(int node) {
        if (isLeaf(node)) {
            return false;
        }
        return low(node) == TRUE_NODE && high(node) == FALSE_NODE;
    }

    @Override
    public boolean isVariableOrNegated(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return false;
        }
        int low = low(node);
        int high = high(node);
        return (low == FALSE_NODE && high == TRUE_NODE) || (low == TRUE_NODE && high == FALSE_NODE);
    }

    // Reading

    BddConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Counts the number of active nodes in the BDD (i.e. the ones which are not invalid),
     * <b>excluding</b> the leaf nodes.
     *
     * @return Number of active nodes.
     */
    public int nodeCount() {
        // Strategy: We gather all root nodes (i.e. nodes which are referenced) on the mark stack, mark
        // all of their children, count all marked nodes and un-mark them.
        assert isNoneMarked();

        int count = 0;
        for (int node = FIRST_NODE; node < tableSize(); node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count += markAllBelow(node);
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
    public int nodeCount(int node) {
        assert isNodeValidOrLeaf(node);
        assert isNoneMarked();

        int count = markAllBelow(node);
        if (count > 0) {
            int unmarked = unMarkAllBelow(node);
            assert count == unmarked : "Expected " + count + " but only unmarked " + unmarked;
        }

        assert isNoneMarked();
        return count;
    }

    /**
     * Over-approximates the number of nodes below the specified {@code node}, possibly counting
     * shared subtrees multiple times. Guaranteed to be bigger or equal to {@link #nodeCount(int)}.
     *
     * @param node The node to be counted.
     * @return An approximate number of non-leaf nodes below {@code node}.
     * @see #nodeCount(int)
     */
    public int approximateNodeCount(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return 0;
        }
        return 1 + approximateNodeCount(low(node)) + approximateNodeCount(high(node));
    }

    @Override
    public boolean evaluate(int node, boolean[] assignment) {
        int current = node;
        while (current >= FIRST_NODE) {
            assert isNodeValid(current);
            current = assignment[variable(current)] ? high(current) : low(current);
        }
        assert isLeaf(current);
        return current == TRUE_NODE;
    }

    @Override
    public boolean evaluate(int node, BitSet assignment) {
        int current = node;
        while (current >= FIRST_NODE) {
            assert isNodeValid(current);
            current = assignment.get(variable(current)) ? high(current) : low(current);
        }
        assert isLeaf(current);
        return current == TRUE_NODE;
    }

    @Override
    public BitSet getSatisfyingAssignment(int node) {
        assert isNodeValidOrLeaf(node);

        if (node == FALSE_NODE) {
            throw new NoSuchElementException("False has no solution");
        }

        BitSet path = new BitSet(numberOfVariables);
        int currentNode = node;
        while (currentNode != TRUE_NODE) {
            int lowNode = low(currentNode);
            if (lowNode == FALSE_NODE) {
                int highNode = high(currentNode);
                int variable = variable(currentNode);

                path.set(variable);
                currentNode = highNode;
            } else {
                currentNode = lowNode;
            }
        }
        return path;
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node) {
        assert isNodeValidOrLeaf(node);
        if (node == FALSE_NODE) {
            return Collections.emptyIterator();
        }
        if (node == TRUE_NODE) {
            return new PowerIterator(numberOfVariables);
        }

        BitSet support = new BitSet(numberOfVariables);
        support.set(0, numberOfVariables);
        return new NodeSolutionIterator(this, node, support);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node, BitSet support) {
        assert isNodeValidOrLeaf(node);
        if (support.isEmpty() || node == FALSE_NODE) {
            return Collections.emptyIterator();
        }
        if (node == TRUE_NODE) {
            return new PowerIterator(support);
        }

        return new NodeSolutionIterator(this, node, support);
    }

    @Override
    public void forEachPath(int node, BiConsumer<BitSet, BitSet> action) {
        assert isNodeValidOrLeaf(node);
        if (node == FALSE_NODE) {
            return;
        }
        if (node == TRUE_NODE) {
            action.accept(new BitSet(0), new BitSet(0));
            return;
        }

        int numberOfVariables = numberOfVariables();
        BitSet path = new BitSet(numberOfVariables);
        BitSet pathSupport = new BitSet(numberOfVariables);

        if (iterative) {
            forEachPathIterative(node, path, pathSupport, action, 0);
        } else {
            forEachPathRecursive(node, path, pathSupport, action);
        }
    }

    public void forEachPathIterative(
            int node, BitSet path, BitSet pathSupport, BiConsumer<BitSet, BitSet> action, int baseStackIndex) {
        int[] branchStackParentVariable = this.branchStackParentVar;
        int[] branchStackNode = this.branchStackFirstArg;

        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            while (current != TRUE_NODE) {
                int variable = variable(current);
                int lowNode = low(current);
                int highNode = high(current);

                if (lowNode == FALSE_NODE) {
                    assert highNode != FALSE_NODE;

                    branchStackNode[stackIndex] = FALSE_NODE;

                    path.set(variable);
                    current = highNode;
                } else {
                    branchStackNode[stackIndex] = highNode;

                    current = lowNode;
                }
                pathSupport.set(variable);
                branchStackParentVariable[stackIndex] = variable;

                stackIndex += 1;
            }
            action.accept(path, pathSupport);

            do {
                if (stackIndex == baseStackIndex) {
                    return;
                }
                current = branchStackNode[--stackIndex];
            } while (current == FALSE_NODE);

            int variable = branchStackParentVariable[stackIndex];
            path.set(variable);

            path.clear(variable + 1, numberOfVariables);
            pathSupport.clear(variable + 1, numberOfVariables);
        }
    }

    private void forEachPathRecursive(int node, BitSet path, BitSet pathSupport, BiConsumer<BitSet, BitSet> action) {
        assert isNodeValid(node) || node == TRUE_NODE;

        if (node == TRUE_NODE) {
            action.accept(path, pathSupport);
            return;
        }

        int variable = variable(node);
        int lowNode = low(node);
        int highNode = high(node);
        pathSupport.set(variable);

        if (lowNode != FALSE_NODE) {
            forEachPathRecursive(lowNode, path, pathSupport, action);
        }
        if (highNode != FALSE_NODE) {
            path.set(variable);
            forEachPathRecursive(highNode, path, pathSupport, action);
            path.clear(variable);
        }

        assert pathSupport.get(variable);
        pathSupport.clear(variable);
    }

    @Override
    public BitSet supportFilteredTo(int node, BitSet bitSet, BitSet filter) {
        assert isNodeValidOrLeaf(node);

        int depthLimit = filter.length();
        if (depthLimit == 0) {
            return bitSet;
        }

        if (iterative) {
            supportIterative(node, bitSet, filter, depthLimit, 0);
            unMarkAllBelowIterative(node);
        } else {
            supportRecursive(node, bitSet, filter, depthLimit);
            unmarkAllBelowRecursive(node);
        }
        return bitSet;
    }

    private void supportIterative(int node, BitSet bitSet, BitSet filter, int depthLimit, int baseStackIndex) {
        int[] nodeData = this.nodeData;
        int[] branchStackNode = this.branchStackFirstArg;

        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            while (!isLeaf(current)) {
                int metadata = nodeData[current];
                assert dataIsValid(metadata);
                int variable = dataGetVariable(metadata);
                if (variable >= depthLimit) {
                    break;
                }
                int markedData = dataSetMark(metadata);
                if (metadata == markedData) {
                    break;
                }
                nodeData[current] = markedData;

                if (filter.get(variable)) {
                    bitSet.set(variable);
                }
                branchStackNode[stackIndex] = high(current);
                current = low(current);
                stackIndex += 1;
            }

            if (stackIndex == baseStackIndex) {
                return;
            }

            stackIndex -= 1;
            current = branchStackNode[stackIndex];
        }
    }

    private void supportRecursive(int node, BitSet bitSet, BitSet filter, int depthLimit) {
        if (isLeaf(node)) {
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

        int lowNode = low(node);
        int highNode = high(node);

        if (filter.get(variable)) {
            bitSet.set(variable);
        }
        supportRecursive(lowNode, bitSet, filter, depthLimit);
        supportRecursive(highNode, bitSet, filter, depthLimit);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node) {
        if (node == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (node == TRUE_NODE) {
            return TWO.pow(numberOfVariables);
        }
        int variable = variable(node);
        return TWO.pow(variable)
                .multiply(
                        iterative
                                ? countSatisfyingAssignmentsIterative(node, 0)
                                : countSatisfyingAssignmentsRecursive(node));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node, BitSet support) {
        assert BitSets.isSubset(support(node), support);

        return countSatisfyingAssignments(node).divide(TWO.pow(numberOfVariables - support.cardinality()));
    }

    private BigInteger countSatisfyingAssignmentsIterative(int node, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackArg = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchTaskStack = this.branchStackFirstArg;

        BigInteger[] resultStack = new BigInteger[numberOfVariables];
        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            BigInteger result;
            int nodeVar;
            do {
                if (current == FALSE_NODE) {
                    nodeVar = numberOfVariables;
                    result = BigInteger.ZERO;
                } else if (current == TRUE_NODE) {
                    nodeVar = numberOfVariables;
                    result = BigInteger.ONE;
                } else {
                    nodeVar = variable(current);

                    result = cache.lookupSatisfaction(current);
                    if (result == null) {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackArg[stackIndex] = current;
                        branchStackParentVar[stackIndex] = nodeVar;
                        branchTaskStack[stackIndex] = high(current);
                        stackIndex += 1;

                        current = low(current);
                    }
                }
            } while (result == null);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;

                if (result.signum() > 0) {
                    result = result.multiply(TWO.pow(nodeVar - variable - 1));
                }
                nodeVar = variable;
                result = result.add(resultStack[stackIndex]);

                cache.putSatisfaction(cacheStackHash[stackIndex], cacheStackArg[stackIndex], result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            resultStack[stackIndex] = result.multiply(TWO.pow(nodeVar - parentVar - 1));

            current = branchTaskStack[stackIndex];
            stackIndex += 1;
        }
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node) {
        assert isNodeValid(node);

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return cacheLookup;
        }
        int hash = cache.lookupHash();

        int nodeVar = variable(node);
        BigInteger lowCount = doCountSatisfyingAssignments(low(node), nodeVar);
        BigInteger highCount = doCountSatisfyingAssignments(high(node), nodeVar);

        BigInteger result = lowCount.add(highCount);
        cache.putSatisfaction(hash, node, result);
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int subNode, int currentVar) {
        if (subNode == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (subNode == TRUE_NODE) {
            return TWO.pow(numberOfVariables - currentVar - 1);
        }
        BigInteger multiplier = TWO.pow(variable(subNode) - currentVar - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(subNode));
    }

    // Bdd operations

    @Override
    public int conjunction(int... variables) {
        int node = TRUE_NODE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            pushToWorkStack(node);
            node = iterative
                    ? andIterative(node, variableNodes[variable], 0)
                    : andRecursive(node, variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int conjunction(BitSet variables) {
        int node = TRUE_NODE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            pushToWorkStack(node);
            node = iterative
                    ? andIterative(node, variableNodes[variable], 0)
                    : andRecursive(node, variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(int... variables) {
        int node = FALSE_NODE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            pushToWorkStack(node);
            node = iterative
                    ? orIterative(node, variableNodes[variable], 0)
                    : orRecursive(node, variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(BitSet variables) {
        int node = FALSE_NODE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            pushToWorkStack(node);
            node = iterative
                    ? orIterative(node, variableNodes[variable], 0)
                    : orRecursive(node, variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int and(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = iterative ? andIterative(node1, node2, 0) : andRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int andIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == current2 || current2 == TRUE_NODE) {
                    result = current1;
                } else if (current1 == FALSE_NODE || current2 == FALSE_NODE) {
                    result = FALSE_NODE;
                } else if (current1 == TRUE_NODE) {
                    result = current2;
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    if (node2var < node1var || (node2var == node1var && current2 < current1)) {
                        int nodeSwap = current1;
                        current1 = current2;
                        current2 = nodeSwap;

                        int varSwap = node1var;
                        node1var = node2var;
                        node2var = varSwap;
                    }

                    if (cache.lookupAnd(current1, current2)) {
                        result = cache.lookupResult();
                    } else {
                        assert isNodeValid(current1) && isNodeValid(current2);

                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackLeft[stackIndex] = current1;
                        cacheStackRight[stackIndex] = current2;
                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        if (node1var == node2var) {
                            branchStackRight[stackIndex] = high(current2);
                            current2 = low(current2);
                        } else {
                            branchStackRight[stackIndex] = current2;
                        }
                        current1 = low(current1);

                        stackIndex += 1;
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));

                int left = cacheStackLeft[stackIndex];
                int right = cacheStackRight[stackIndex];
                cache.putAnd(cacheStackHash[stackIndex], left, right, result);

                popWorkStack(2);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int andRecursive(int node1, int node2) {
        if (node1 == node2 || node2 == TRUE_NODE) {
            return node1;
        }
        if (node1 == FALSE_NODE || node2 == FALSE_NODE) {
            return FALSE_NODE;
        }
        if (node1 == TRUE_NODE) {
            return node2;
        }

        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node2var < node1var || (node2var == node1var && node2 < node1)) {
            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupAnd(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int lowNode;
        int highNode;
        if (node1var == node2var) {
            lowNode = pushToWorkStack(andRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(andRecursive(high(node1), high(node2)));
        } else { // v < getVariable(node2)
            lowNode = pushToWorkStack(andRecursive(low(node1), node2));
            highNode = pushToWorkStack(andRecursive(high(node1), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putAnd(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int compose(int node, int[] variableMapping) {
        assert isWorkStackEmpty();
        assert variableMapping.length <= numberOfVariables;

        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }

        // Guard the elements and replace placeholder by actual variable reference
        pushToWorkStack(node);
        int workStackCount = 1;
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] == NOT_A_NODE) {
                variableMapping[i] = this.variableNodes[i];
            } else {
                assert isNodeValidOrLeaf(variableMapping[i]);
                if (!isNodeSaturated(variableMapping[i])) {
                    pushToWorkStack(variableMapping[i]);
                    workStackCount++;
                }
            }
        }

        int highestReplacedVariable = variableMapping.length - 1;
        // Optimise the replacement array
        for (int i = variableMapping.length - 1; i >= 0; i--) {
            if (variableMapping[i] != this.variableNodes[i]) {
                highestReplacedVariable = i;
                break;
            }
        }
        if (highestReplacedVariable == -1) {
            popWorkStack(workStackCount);
            assert isWorkStackEmpty();
            return node;
        }

        cache.initCompose(variableMapping, highestReplacedVariable);
        int result = iterative
                ? composeIterative(node, variableMapping, highestReplacedVariable)
                : composeRecursive(node, variableMapping, highestReplacedVariable);
        popWorkStack(workStackCount);
        assert isWorkStackEmpty();
        return result;
    }

    private int composeIterative(int node, int[] variableNodes, int highestReplacedVariable) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheArgStack = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchTaskStack = this.branchStackFirstArg;

        int initialSize = workStackIndex;
        int stackIndex = 0;
        int current = node;
        while (true) {
            assert stackIndex >= 0;
            assert workStackIndex >= initialSize;

            int result = NOT_A_NODE;
            do {
                if (current == TRUE_NODE || current == FALSE_NODE) {
                    result = current;
                } else {
                    int nodeVariable = variable(current);

                    if (nodeVariable > highestReplacedVariable) {
                        result = current;
                    } else {
                        int replacementNode = variableNodes[nodeVariable];

                        if (replacementNode == TRUE_NODE) {
                            current = high(current);
                        } else if (replacementNode == FALSE_NODE) {
                            current = low(current);
                        } else if (cache.lookupCompose(current)) {
                            result = cache.lookupResult();
                        } else {
                            cacheStackHash[stackIndex] = cache.lookupHash();
                            cacheArgStack[stackIndex] = current;
                            branchStackParentVar[stackIndex] = nodeVariable;
                            branchTaskStack[stackIndex] = high(current);
                            stackIndex += 1;

                            current = low(current);
                        }
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == 0) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                int replacementNode = variableNodes[variable];
                int currentHash = cacheStackHash[stackIndex];
                int currentNode = cacheArgStack[stackIndex];

                // TODO Shortcut if replacement is a variable?
                int lowResult = peekWorkStack();
                pushToWorkStack(result);
                result = ifThenElseIterative(replacementNode, result, lowResult, stackIndex);
                popWorkStack(2);

                cache.putCompose(currentHash, currentNode, result);

                if (stackIndex == 0) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current = branchTaskStack[stackIndex];
            stackIndex += 1;
        }
    }

    private int composeRecursive(int node, int[] variableNodes, int highestReplacedVariable) {
        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }

        int nodeVariable = variable(node);
        if (nodeVariable > highestReplacedVariable) {
            return node;
        }

        if (cache.lookupCompose(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int variableReplacementNode = variableNodes[nodeVariable];
        int resultNode;
        // Short-circuit constant replacements.
        if (variableReplacementNode == TRUE_NODE) {
            resultNode = composeRecursive(high(node), variableNodes, highestReplacedVariable);
        } else if (variableReplacementNode == FALSE_NODE) {
            resultNode = composeRecursive(low(node), variableNodes, highestReplacedVariable);
        } else {
            int lowCompose = pushToWorkStack(composeRecursive(low(node), variableNodes, highestReplacedVariable));
            int highCompose = pushToWorkStack(composeRecursive(high(node), variableNodes, highestReplacedVariable));
            resultNode = ifThenElseRecursive(variableReplacementNode, highCompose, lowCompose);
            popWorkStack(2);
        }
        cache.putCompose(hash, node, resultNode);
        return resultNode;
    }

    @Override
    public int equivalence(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = iterative ? equivalenceIterative(node1, node2, 0) : equivalenceRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int equivalenceIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;
        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == current2) {
                    result = TRUE_NODE;
                } else if (current1 == FALSE_NODE) {
                    result = notIterative(current2, stackIndex);
                } else if (current1 == TRUE_NODE) {
                    result = current2;
                } else if (current2 == FALSE_NODE) {
                    result = notIterative(current1, stackIndex);
                } else if (current2 == TRUE_NODE) {
                    result = current1;
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    if (node2var < node1var || (node2var == node1var && current2 < current1)) {
                        int nodeSwap = current1;
                        current1 = current2;
                        current2 = nodeSwap;

                        int varSwap = node1var;
                        node1var = node2var;
                        node2var = varSwap;
                    }

                    if (cache.lookupEquivalence(current1, current2)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackLeft[stackIndex] = current1;
                        cacheStackRight[stackIndex] = current2;

                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        if (node1var == node2var) {
                            branchStackRight[stackIndex] = high(current2);
                            current2 = low(current2);
                        } else {
                            branchStackRight[stackIndex] = current2;
                        }
                        current1 = low(current1);

                        stackIndex += 1;
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);

                int left = cacheStackLeft[stackIndex];
                int right = cacheStackRight[stackIndex];
                cache.putEquivalence(cacheStackHash[stackIndex], left, right, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int equivalenceRecursive(int node1, int node2) {
        if (node1 == node2) {
            return TRUE_NODE;
        }
        if (node1 == FALSE_NODE) {
            return notRecursive(node2);
        }
        if (node1 == TRUE_NODE) {
            return node2;
        }
        if (node2 == FALSE_NODE) {
            return notRecursive(node1);
        }
        if (node2 == TRUE_NODE) {
            return node1;
        }

        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node2var < node1var || (node2var == node1var && node2 < node1)) {
            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupEquivalence(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int lowNode;
        int highNode;
        if (node1var == node2var) {
            lowNode = pushToWorkStack(equivalenceRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(equivalenceRecursive(high(node1), high(node2)));
        } else { // v < getVariable(node2)
            lowNode = pushToWorkStack(equivalenceRecursive(low(node1), node2));
            highNode = pushToWorkStack(equivalenceRecursive(high(node1), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putEquivalence(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int exists(int node, BitSet quantifiedVariables) {
        assert isWorkStackEmpty();
        assert quantifiedVariables.previousSetBit(quantifiedVariables.length()) <= numberOfVariables;
        if (quantifiedVariables.cardinality() == numberOfVariables) {
            return TRUE_NODE;
        }

        // Shannon exists
        pushToWorkStack(node);
        int quantifiedVariablesConjunction = conjunction(quantifiedVariables);
        pushToWorkStack(quantifiedVariablesConjunction);
        int result = iterative
                ? existsIterative(node, quantifiedVariablesConjunction, 0)
                : existsRecursive(node, quantifiedVariablesConjunction);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int existsIterative(int node, int quantifiedVariableCube, int baseStackIndex) {
        // N.B.: The "root" of the cube is guarded in the main invocation - no need to guard it

        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackArg = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackArg = this.branchStackFirstArg;
        int[] branchStackCubeNode = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current = node;
        int currentCubeNode = quantifiedVariableCube;
        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            //noinspection LabeledStatement
            loop:
            do {
                if (current == TRUE_NODE || current == FALSE_NODE) {
                    result = current;
                } else if (quantifiedVariableCube == TRUE_NODE) {
                    result = current;
                } else {
                    int nodeVariable = variable(current);
                    int currentCubeNodeVariable = variable(currentCubeNode);
                    while (currentCubeNodeVariable < nodeVariable) {
                        currentCubeNode = high(currentCubeNode);
                        if (currentCubeNode == TRUE_NODE) {
                            // No more variables to project
                            result = current;
                            //noinspection BreakStatementWithLabel
                            break loop;
                        }
                        currentCubeNodeVariable = variable(currentCubeNode);
                    }

                    if (isVariableOrNegated(current)) {
                        if (nodeVariable == currentCubeNodeVariable) {
                            result = TRUE_NODE;
                        } else {
                            result = current;
                        }
                    } else if (cache.lookupExists(current, currentCubeNode)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackArg[stackIndex] = current;

                        branchStackParentVar[stackIndex] = nodeVariable;
                        branchStackArg[stackIndex] = high(current);
                        branchStackCubeNode[stackIndex] = currentCubeNode;

                        current = low(current);
                        stackIndex += 1;
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                int currentNode = cacheStackArg[stackIndex];
                int currentHash = cacheStackHash[stackIndex];

                currentCubeNode = branchStackCubeNode[stackIndex];
                if (variable(currentCubeNode) > variable) {
                    // The variable of this node is smaller than the variable looked for - only propagate the
                    // quantification downward
                    result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                    popWorkStack(2);
                } else {
                    // nodeVariable == nextVariable, i.e. "quantify out" the current node.
                    result = orIterative(peekAndPopWorkStack(), result, stackIndex);
                }
                cache.putExists(currentHash, currentNode, currentCubeNode, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            currentCubeNode = branchStackCubeNode[stackIndex];
            current = branchStackArg[stackIndex];
            stackIndex += 1;
        }
    }

    private int existsRecursive(int node, int quantifiedVariableCube) {
        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }
        if (quantifiedVariableCube == TRUE_NODE) {
            return node;
        }

        int nodeVariable = variable(node);

        int currentCubeNode = quantifiedVariableCube;
        int currentCubeNodeVariable = variable(currentCubeNode);
        while (currentCubeNodeVariable < nodeVariable) {
            currentCubeNode = high(currentCubeNode);
            if (currentCubeNode == TRUE_NODE) {
                // No more variables to project
                return node;
            }
            currentCubeNodeVariable = variable(currentCubeNode);
        }

        if (isVariableOrNegated(node)) {
            if (nodeVariable == currentCubeNodeVariable) {
                return TRUE_NODE;
            }
            return node;
        }

        if (cache.lookupExists(node, currentCubeNode)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        // The "root" of the cube is guarded in the main invocation - no need to guard its descendants
        int lowExists = pushToWorkStack(existsRecursive(low(node), currentCubeNode));
        int highExists = pushToWorkStack(existsRecursive(high(node), currentCubeNode));
        int resultNode;
        if (currentCubeNodeVariable > nodeVariable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            resultNode = makeNode(nodeVariable, lowExists, highExists);
        } else {
            // nodeVariable == nextVariable, i.e. "quantify out" the current node.
            resultNode = orRecursive(lowExists, highExists);
        }
        popWorkStack(2);
        cache.putExists(hash, node, currentCubeNode, resultNode);
        return resultNode;
    }

    @Override
    public int ifThenElse(int ifNode, int thenNode, int elseNode) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(ifNode) && isNodeValidOrLeaf(thenNode) && isNodeValidOrLeaf(elseNode);
        pushToWorkStack(ifNode);
        pushToWorkStack(thenNode);
        pushToWorkStack(elseNode);
        int result = iterative
                ? ifThenElseIterative(ifNode, thenNode, elseNode, 0)
                : ifThenElseRecursive(ifNode, thenNode, elseNode);
        popWorkStack(3);
        assert isWorkStackEmpty();
        return result;
    }

    private int ifThenElseIterative(int ifNode, int thenNode, int elseNode, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheIfArgStack = this.cacheStackFirstArg;
        int[] cacheThenArgStack = this.cacheStackSecondArg;
        int[] cacheElseArgStack = this.cacheStackThirdArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchTaskIfStack = this.branchStackFirstArg;
        int[] branchTaskThenStack = this.branchStackSecondArg;
        int[] branchTaskElseStack = this.branchStackThirdArg;

        int stackIndex = baseStackIndex;
        int currentIf = ifNode;
        int currentThen = thenNode;
        int currentElse = elseNode;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (currentIf == TRUE_NODE) {
                    result = currentThen;
                } else if (currentIf == FALSE_NODE) {
                    result = currentElse;
                } else if (currentThen == currentElse) {
                    result = currentThen;
                } else if (currentThen == TRUE_NODE) {
                    result = currentElse == FALSE_NODE ? currentIf : orIterative(currentIf, currentElse, stackIndex);
                } else if (currentThen == FALSE_NODE) {
                    if (currentElse == TRUE_NODE) {
                        result = notIterative(currentIf, stackIndex);
                    } else {
                        int not = notIterative(currentIf, stackIndex);
                        result = andIterative(pushToWorkStack(not), currentElse, stackIndex);
                        popWorkStack();
                    }
                } else if (currentElse == TRUE_NODE) {
                    int not = notIterative(currentThen, stackIndex);
                    result = notAndIterative(currentIf, pushToWorkStack(not), stackIndex);
                    popWorkStack();
                } else if (currentElse == FALSE_NODE) {
                    result = andIterative(currentIf, currentThen, stackIndex);
                } else if (currentIf == currentThen) {
                    result = orIterative(currentIf, currentElse, stackIndex);
                } else if (currentIf == currentElse) {
                    result = andIterative(currentIf, currentThen, stackIndex);
                } else if (cache.lookupIfThenElse(currentIf, currentThen, currentElse)) {
                    result = cache.lookupResult();
                } else {
                    int ifVar = variable(currentIf);
                    int thenVar = variable(currentThen);
                    int elseVar = variable(currentElse);

                    int minVar = min(ifVar, thenVar, elseVar);
                    int ifLowNode;
                    int ifHighNode;

                    if (ifVar == minVar) {
                        ifLowNode = low(currentIf);
                        ifHighNode = high(currentIf);
                    } else {
                        ifLowNode = currentIf;
                        ifHighNode = currentIf;
                    }

                    int thenHighNode;
                    int thenLowNode;
                    if (thenVar == minVar) {
                        thenLowNode = low(currentThen);
                        thenHighNode = high(currentThen);
                    } else {
                        thenLowNode = currentThen;
                        thenHighNode = currentThen;
                    }

                    int elseHighNode;
                    int elseLowNode;
                    if (elseVar == minVar) {
                        elseLowNode = low(currentElse);
                        elseHighNode = high(currentElse);
                    } else {
                        elseLowNode = currentElse;
                        elseHighNode = currentElse;
                    }

                    cacheStackHash[stackIndex] = cache.lookupHash();
                    cacheIfArgStack[stackIndex] = currentIf;
                    cacheThenArgStack[stackIndex] = currentThen;
                    cacheElseArgStack[stackIndex] = currentElse;

                    branchStackParentVar[stackIndex] = minVar;
                    branchTaskIfStack[stackIndex] = ifHighNode;
                    branchTaskThenStack[stackIndex] = thenHighNode;
                    branchTaskElseStack[stackIndex] = elseHighNode;

                    currentIf = ifLowNode;
                    currentThen = thenLowNode;
                    currentElse = elseLowNode;
                    stackIndex += 1;
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);

                int cacheIf = cacheIfArgStack[stackIndex];
                int cacheThen = cacheThenArgStack[stackIndex];
                int cacheElse = cacheElseArgStack[stackIndex];
                cache.putIfThenElse(cacheStackHash[stackIndex], cacheIf, cacheThen, cacheElse, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            assert stackIndex >= baseStackIndex;
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            currentIf = branchTaskIfStack[stackIndex];
            currentThen = branchTaskThenStack[stackIndex];
            currentElse = branchTaskElseStack[stackIndex];
            stackIndex += 1;
        }
    }

    private int ifThenElseRecursive(int ifNode, int thenNode, int elseNode) {
        if (ifNode == TRUE_NODE) {
            return thenNode;
        }
        if (ifNode == FALSE_NODE) {
            return elseNode;
        }
        if (thenNode == elseNode) {
            return thenNode;
        }
        if (thenNode == TRUE_NODE) {
            if (elseNode == FALSE_NODE) {
                return ifNode;
            }
            return orRecursive(ifNode, elseNode);
        }
        if (thenNode == FALSE_NODE) {
            if (elseNode == TRUE_NODE) {
                return notRecursive(ifNode);
            }
            int result = andRecursive(pushToWorkStack(notRecursive(ifNode)), elseNode);
            popWorkStack();
            return result;
        }

        if (elseNode == TRUE_NODE) {
            int result = notAndRecursive(ifNode, pushToWorkStack(notRecursive(thenNode)));
            popWorkStack();
            return result;
        }
        if (elseNode == FALSE_NODE) {
            return andRecursive(ifNode, thenNode);
        }
        if (ifNode == thenNode) {
            return orRecursive(ifNode, elseNode);
        }
        if (ifNode == elseNode) {
            return andRecursive(ifNode, thenNode);
        }

        if (cache.lookupIfThenElse(ifNode, thenNode, elseNode)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int ifVar = variable(ifNode);
        int thenVar = variable(thenNode);
        int elseVar = variable(elseNode);

        int minVar = Math.min(ifVar, Math.min(thenVar, elseVar));
        int ifLowNode;
        int ifHighNode;

        if (ifVar == minVar) {
            ifLowNode = low(ifNode);
            ifHighNode = high(ifNode);
        } else {
            ifLowNode = ifNode;
            ifHighNode = ifNode;
        }

        int thenHighNode;
        int thenLowNode;
        if (thenVar == minVar) {
            thenLowNode = low(thenNode);
            thenHighNode = high(thenNode);
        } else {
            thenLowNode = thenNode;
            thenHighNode = thenNode;
        }

        int elseHighNode;
        int elseLowNode;
        if (elseVar == minVar) {
            elseLowNode = low(elseNode);
            elseHighNode = high(elseNode);
        } else {
            elseLowNode = elseNode;
            elseHighNode = elseNode;
        }

        int lowNode = pushToWorkStack(ifThenElseRecursive(ifLowNode, thenLowNode, elseLowNode));
        int highNode = pushToWorkStack(ifThenElseRecursive(ifHighNode, thenHighNode, elseHighNode));
        int result = makeNode(minVar, lowNode, highNode);
        popWorkStack(2);
        cache.putIfThenElse(hash, ifNode, thenNode, elseNode, result);
        return result;
    }

    @Override
    public int implication(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = iterative ? implicationIterative(node1, node2, 0) : implicationRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int implicationIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == FALSE_NODE || current2 == TRUE_NODE || current1 == current2) {
                    result = TRUE_NODE;
                } else if (current1 == TRUE_NODE) {
                    result = current2;
                } else if (current2 == FALSE_NODE) {
                    result = notIterative(current1, stackIndex);
                } else if (cache.lookupImplication(current1, current2)) {
                    result = cache.lookupResult();
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    cacheStackHash[stackIndex] = cache.lookupHash();
                    cacheStackLeft[stackIndex] = current1;
                    cacheStackRight[stackIndex] = current2;

                    if (node1var > node2var) {
                        branchStackParentVar[stackIndex] = node2var;
                        branchStackLeft[stackIndex] = current1;
                        branchStackRight[stackIndex] = high(current2);

                        current2 = low(current2);
                    } else if (node1var == node2var) {
                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        branchStackRight[stackIndex] = high(current2);

                        current1 = low(current1);
                        current2 = low(current2);
                    } else {
                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        branchStackRight[stackIndex] = current2;

                        current1 = low(current1);
                    }
                    stackIndex += 1;
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);

                int left = cacheStackLeft[stackIndex];
                int right = cacheStackRight[stackIndex];
                cache.putImplication(cacheStackHash[stackIndex], left, right, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int implicationRecursive(int node1, int node2) {
        if (node1 == FALSE_NODE || node2 == TRUE_NODE || node1 == node2) {
            return TRUE_NODE;
        }
        if (node1 == TRUE_NODE) {
            return node2;
        }
        if (node2 == FALSE_NODE) {
            return notRecursive(node1);
        }

        if (cache.lookupImplication(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int node1var = variable(node1);
        int node2var = variable(node2);

        int lowNode;
        int highNode;
        int decisionVar;
        if (node1var > node2var) {
            lowNode = pushToWorkStack(implicationRecursive(node1, low(node2)));
            highNode = pushToWorkStack(implicationRecursive(node1, high(node2)));
            decisionVar = node2var;
        } else if (node1var == node2var) {
            lowNode = pushToWorkStack(implicationRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(implicationRecursive(high(node1), high(node2)));
            decisionVar = node1var;
        } else {
            lowNode = pushToWorkStack(implicationRecursive(low(node1), node2));
            highNode = pushToWorkStack(implicationRecursive(high(node1), node2));
            decisionVar = node1var;
        }
        int resultNode = makeNode(decisionVar, lowNode, highNode);
        popWorkStack(2);
        cache.putImplication(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public boolean implies(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        boolean result = iterative ? impliesIterative(node1, node2, 0) : impliesRecursive(node1, node2);
        assert isWorkStackEmpty();
        return result;
    }

    private boolean impliesIterative(int node1, int node2, int baseStackIndex) {
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            //noinspection LoopWithImplicitTerminationCondition
            while (true) {
                if (current1 == FALSE_NODE) {
                    // False implies anything
                    break;
                }
                if (current2 == FALSE_NODE) {
                    // node1 != FALSE_NODE
                    return false;
                }
                if (current2 == TRUE_NODE) {
                    // node1 != FALSE_NODE
                    break;
                }
                if (current1 == TRUE_NODE) {
                    // node2 != TRUE_NODE
                    return false;
                }
                if (current1 == current2) {
                    // Trivial implication
                    break;
                }
                if (cache.lookupImplication(current1, current2)) {
                    if (cache.lookupResult() == TRUE_NODE) {
                        break;
                    }
                    return false;
                }

                int node1var = variable(current1);
                int node2var = variable(current2);

                int node1low = low(current1);
                int node1high = high(current1);
                int node2low = low(current2);
                int node2high = high(current2);

                if (node1var > node2var) {
                    branchStackLeft[stackIndex] = current1;
                    branchStackRight[stackIndex] = node2high;

                    current2 = node2low;
                } else if (node1var == node2var) {
                    branchStackLeft[stackIndex] = node1high;
                    branchStackRight[stackIndex] = node2high;

                    current1 = node1low;
                    current2 = node2low;
                } else {
                    branchStackLeft[stackIndex] = node1high;
                    branchStackRight[stackIndex] = current2;

                    current1 = node1low;
                }
                stackIndex += 1;
            }

            if (stackIndex == baseStackIndex) {
                return true;
            }

            stackIndex--;
            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
        }
    }

    private boolean impliesRecursive(int node1, int node2) {
        if (node1 == FALSE_NODE) {
            // False implies anything
            return true;
        }
        if (node2 == FALSE_NODE) {
            // node1 != FALSE_NODE
            return false;
        }
        if (node2 == TRUE_NODE) {
            // node1 != FALSE_NODE
            return true;
        }
        if (node1 == TRUE_NODE) {
            // node2 != TRUE_NODE
            return false;
        }
        if (node1 == node2) {
            // Trivial implication
            return true;
        }

        if (cache.lookupImplication(node1, node2)) {
            return cache.lookupResult() == TRUE_NODE;
        }
        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node1var == node2var) {
            return impliesRecursive(low(node1), low(node2)) && impliesRecursive(high(node1), high(node2));
        } else if (node1var < node2var) {
            return impliesRecursive(low(node1), node2) && impliesRecursive(high(node1), node2);
        } else {
            return impliesRecursive(node1, low(node2)) && impliesRecursive(node1, high(node2));
        }
    }

    @Override
    public int not(int node) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node);
        pushToWorkStack(node);
        int result = iterative ? notIterative(node, 0) : notRecursive(node);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
    }

    private int notIterative(int node, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheArgStack = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchTaskStack = this.branchStackFirstArg;

        int stackIndex = baseStackIndex;
        int current = node;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current == FALSE_NODE) {
                    result = TRUE_NODE;
                } else if (current == TRUE_NODE) {
                    result = FALSE_NODE;
                } else if (cache.lookupNot(current)) {
                    result = cache.lookupResult();
                } else {
                    cacheStackHash[stackIndex] = cache.lookupHash();
                    cacheArgStack[stackIndex] = current;
                    branchStackParentVar[stackIndex] = variable(current);
                    branchTaskStack[stackIndex] = high(current);
                    stackIndex += 1;
                    current = low(current);
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                assert stackIndex >= baseStackIndex;
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);
                cache.putNot(cacheStackHash[stackIndex], cacheArgStack[stackIndex], result);
                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            assert stackIndex >= baseStackIndex;
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current = branchTaskStack[stackIndex];
            stackIndex += 1;
        }
    }

    private int notRecursive(int node) {
        if (node == FALSE_NODE) {
            return TRUE_NODE;
        }
        if (node == TRUE_NODE) {
            return FALSE_NODE;
        }

        if (cache.lookupNot(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int lowNode = pushToWorkStack(notRecursive(low(node)));
        int highNode = pushToWorkStack(notRecursive(high(node)));
        int resultNode = makeNode(variable(node), lowNode, highNode);
        popWorkStack(2);
        cache.putNot(hash, node, resultNode);
        return resultNode;
    }

    @Override
    public int notAnd(int node1, int node2) {
        assert isWorkStackEmpty();
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = iterative ? notAndIterative(node1, node2, 0) : notAndRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int notAndIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == FALSE_NODE || current2 == FALSE_NODE) {
                    result = TRUE_NODE;
                } else if (current1 == TRUE_NODE || current1 == current2) {
                    result = notIterative(current2, stackIndex);
                } else if (current2 == TRUE_NODE) {
                    result = notIterative(current1, stackIndex);
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    if (node2var < node1var || (node2var == node1var && current2 < current1)) {
                        int nodeSwap = current1;
                        current1 = current2;
                        current2 = nodeSwap;

                        int varSwap = node1var;
                        node1var = node2var;
                        node2var = varSwap;
                    }

                    if (cache.lookupNAnd(current1, current2)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackLeft[stackIndex] = current1;
                        cacheStackRight[stackIndex] = current2;
                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        if (node1var == node2var) {
                            branchStackRight[stackIndex] = high(current2);
                            current2 = low(current2);
                        } else {
                            branchStackRight[stackIndex] = current2;
                        }
                        stackIndex += 1;

                        current1 = low(current1);
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);
                cache.putNAnd(
                        cacheStackHash[stackIndex], cacheStackLeft[stackIndex], cacheStackRight[stackIndex], result);
                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int notAndRecursive(int node1, int node2) {
        if (node1 == FALSE_NODE || node2 == FALSE_NODE) {
            return TRUE_NODE;
        }
        if (node1 == TRUE_NODE || node1 == node2) {
            return notRecursive(node2);
        }
        if (node2 == TRUE_NODE) {
            return notRecursive(node1);
        }

        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node2var < node1var || (node2var == node1var && node2 < node1)) {
            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupNAnd(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int lowNode;
        int highNode;
        if (node1var == node2var) {
            lowNode = pushToWorkStack(notAndRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(notAndRecursive(high(node1), high(node2)));
        } else { // v < getVariable(node2)
            lowNode = pushToWorkStack(notAndRecursive(low(node1), node2));
            highNode = pushToWorkStack(notAndRecursive(high(node1), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putNAnd(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int or(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = iterative ? orIterative(node1, node2, 0) : orRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int orIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == TRUE_NODE || current2 == TRUE_NODE) {
                    result = TRUE_NODE;
                } else if (current1 == FALSE_NODE || current1 == current2) {
                    result = current2;
                } else if (current2 == FALSE_NODE) {
                    result = current1;
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    if (node2var < node1var || (node2var == node1var && current2 < current1)) {
                        int nodeSwap = current1;
                        current1 = current2;
                        current2 = nodeSwap;

                        int varSwap = node1var;
                        node1var = node2var;
                        node2var = varSwap;
                    }

                    if (cache.lookupOr(current1, current2)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackLeft[stackIndex] = current1;
                        cacheStackRight[stackIndex] = current2;

                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        if (node1var == node2var) {
                            branchStackRight[stackIndex] = high(current2);
                            current2 = low(current2);
                        } else {
                            branchStackRight[stackIndex] = current2;
                        }
                        stackIndex += 1;

                        current1 = low(current1);
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);
                cache.putOr(
                        cacheStackHash[stackIndex], cacheStackLeft[stackIndex], cacheStackRight[stackIndex], result);
                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int orRecursive(int node1, int node2) {
        if (node1 == TRUE_NODE || node2 == TRUE_NODE) {
            return TRUE_NODE;
        }
        if (node1 == FALSE_NODE || node1 == node2) {
            return node2;
        }
        if (node2 == FALSE_NODE) {
            return node1;
        }

        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node2var < node1var || (node2var == node1var && node2 < node1)) {
            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupOr(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int lowNode;
        int highNode;
        if (node1var == node2var) {
            lowNode = pushToWorkStack(orRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(orRecursive(high(node1), high(node2)));
        } else { // v < getVariable(node2)
            lowNode = pushToWorkStack(orRecursive(low(node1), node2));
            highNode = pushToWorkStack(orRecursive(high(node1), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putOr(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node);

        if (restrictedVariables.isEmpty()) {
            return node;
        }
        if (isLeaf(node)) {
            return node;
        }

        pushToWorkStack(node);
        int highestReplacement = restrictedVariables.length() - 1;
        int[] composeArray = new int[highestReplacement + 1];
        for (int variable = 0; variable <= highestReplacement; variable++) {
            if (restrictedVariables.get(variable)) {
                composeArray[variable] = restrictedVariableValues.get(variable) ? TRUE_NODE : FALSE_NODE;
            } else {
                composeArray[variable] = variableNodes[variable];
            }
        }

        cache.initCompose(composeArray, highestReplacement);
        int result = iterative
                ? composeIterative(node, composeArray, highestReplacement)
                : composeRecursive(node, composeArray, highestReplacement);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
    }

    @Override
    public int xor(int node1, int node2) {
        assert isWorkStackEmpty();
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int ret = iterative ? xorIterative(node1, node2, 0) : xorRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return ret;
    }

    private int xorIterative(int node1, int node2, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackLeft = this.cacheStackFirstArg;
        int[] cacheStackRight = this.cacheStackSecondArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackLeft = this.branchStackFirstArg;
        int[] branchStackRight = this.branchStackSecondArg;

        int stackIndex = baseStackIndex;
        int current1 = node1;
        int current2 = node2;

        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            do {
                if (current1 == current2) {
                    result = FALSE_NODE;
                } else if (current1 == FALSE_NODE) {
                    result = current2;
                } else if (current2 == FALSE_NODE) {
                    result = current1;
                } else if (current1 == TRUE_NODE) {
                    result = notIterative(current2, stackIndex);
                } else if (current2 == TRUE_NODE) {
                    result = notIterative(current1, stackIndex);
                } else {
                    int node1var = variable(current1);
                    int node2var = variable(current2);

                    if (node2var < node1var || (node2var == node1var && current2 < current1)) {
                        int nodeSwap = current1;
                        current1 = current2;
                        current2 = nodeSwap;

                        int varSwap = node1var;
                        node1var = node2var;
                        node2var = varSwap;
                    }

                    if (cache.lookupXor(current1, current2)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackLeft[stackIndex] = current1;
                        cacheStackRight[stackIndex] = current2;

                        branchStackParentVar[stackIndex] = node1var;
                        branchStackLeft[stackIndex] = high(current1);
                        if (node1var == node2var) {
                            branchStackRight[stackIndex] = high(current2);
                            current2 = low(current2);
                        } else {
                            branchStackRight[stackIndex] = current2;
                        }
                        current1 = low(current1);

                        stackIndex += 1;
                    }
                }
            } while (result == NOT_A_NODE);

            if (stackIndex == baseStackIndex) {
                return result;
            }

            int parentVar;
            while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
                int variable = -parentVar - 1;
                result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                popWorkStack(2);
                cache.putXor(
                        cacheStackHash[stackIndex], cacheStackLeft[stackIndex], cacheStackRight[stackIndex], result);
                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            current1 = branchStackLeft[stackIndex];
            current2 = branchStackRight[stackIndex];
            stackIndex += 1;
        }
    }

    private int xorRecursive(int node1, int node2) {
        if (node1 == node2) {
            return FALSE_NODE;
        }
        if (node1 == FALSE_NODE) {
            return node2;
        }
        if (node2 == FALSE_NODE) {
            return node1;
        }
        if (node1 == TRUE_NODE) {
            return notRecursive(node2);
        }
        if (node2 == TRUE_NODE) {
            return notRecursive(node1);
        }

        int node1var = variable(node1);
        int node2var = variable(node2);

        if (node2var < node1var || (node2var == node1var && node2 < node1)) {
            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupXor(node1, node2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
        int lowNode;
        int highNode;
        if (node1var == node2var) {
            lowNode = pushToWorkStack(xorRecursive(low(node1), low(node2)));
            highNode = pushToWorkStack(xorRecursive(high(node1), high(node2)));
        } else { // v < getVariable(node2)
            lowNode = pushToWorkStack(xorRecursive(low(node1), node2));
            highNode = pushToWorkStack(xorRecursive(high(node1), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putXor(hash, node1, node2, resultNode);
        return resultNode;
    }

    // Iterative management

    private void growStacks() {
        int minimumSize = numberOfVariables + 5;
        if (cacheStackHash.length > minimumSize) {
            return;
        }

        cacheStackHash = new int[minimumSize];
        cacheStackFirstArg = new int[minimumSize];
        cacheStackSecondArg = new int[minimumSize];
        cacheStackThirdArg = new int[minimumSize];

        branchStackParentVar = new int[minimumSize];
        branchStackFirstArg = new int[minimumSize];
        branchStackSecondArg = new int[minimumSize];
        branchStackThirdArg = new int[minimumSize];

        markStack = new int[minimumSize];
    }

    // Integrity checks and utility

    /**
     * Performs some integrity / invariant checks.
     *
     * @return True. This way, check can easily be called by an {@code assert} statement.
     */
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    boolean check() {
        logger.log(Level.FINER, "Running integrity check");
        checkState(biggestReferencedNode <= biggestValidNode);

        // Check the biggestValidNode variable
        checkState(
                dataIsValid(nodeData[biggestValidNode]),
                "Node (%s) is not valid or leaf",
                nodeToStringSupplier(biggestValidNode));
        for (int i = biggestValidNode + 1; i < tableSize(); i++) {
            checkState(!dataIsValid(nodeData[i]), "Node (%s) is valid", nodeToStringSupplier(i));
        }

        // Check biggestReferencedNode variable
        checkState(
                dataIsReferencedOrSaturated(nodeData[biggestReferencedNode]),
                "Node (%s) is not referenced",
                nodeToStringSupplier(biggestReferencedNode));
        for (int i = biggestReferencedNode + 1; i < tableSize(); i++) {
            checkState(!dataIsReferencedOrSaturated(nodeData[i]), "Node (%s) is referenced", nodeToStringSupplier(i));
        }

        // Check invalid nodes are not referenced
        for (int node = FIRST_NODE; node <= biggestReferencedNode; node++) {
            if (dataIsReferencedOrSaturated(nodeData[node])) {
                checkState(
                        dataIsValid(nodeData[node]), "Node (%s) is referenced but invalid", nodeToStringSupplier(node));
            }
        }

        // Check if the number of free nodes is correct
        int count = 0;
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            if (dataIsValid(nodeData[node])) {
                count++;
            }
        }
        checkState(
                count == (tableSize() - freeNodeCount - FIRST_NODE),
                "Invalid # of free nodes: #live=%d, size=%d, free=%d, expected=%d",
                count,
                tableSize(),
                freeNodeCount,
                tableSize() - freeNodeCount - FIRST_NODE);

        // Check each node's children
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata)) {
                int low = low(node);
                int high = high(node);
                checkState(
                        isNodeValidOrLeaf(low),
                        "Invalid low entry (%s) -> (%s)",
                        nodeToStringSupplier(node),
                        nodeToStringSupplier(low));
                checkState(
                        isNodeValidOrLeaf(high),
                        "Invalid high entry (%s) -> (%s)",
                        nodeToStringSupplier(node),
                        nodeToStringSupplier(high));
                if (!isLeaf(low)) {
                    checkState(
                            dataGetVariable(metadata) < dataGetVariable(nodeData[low]),
                            "(%s) -> (%s) does not descend tree",
                            nodeToStringSupplier(node),
                            nodeToStringSupplier(low));
                }
                if (!isLeaf(high)) {
                    checkState(
                            dataGetVariable(metadata) < dataGetVariable(nodeData[high]),
                            "(%s) -> (%s) does not descend tree",
                            nodeToStringSupplier(node),
                            nodeToStringSupplier(high));
                }
            }
        }

        // Check if there are duplicate nodes
        //noinspection MagicNumber
        int maximalNodeCountCheckedPairs = 1000;
        if (tableSize() < maximalNodeCountCheckedPairs) {
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                int dataI = nodeData[node];
                if (dataIsValid(dataI)) {
                    for (int j = node + 1; j < tableSize(); j++) {
                        int dataJ = nodeData[j];
                        if (dataIsValid(dataJ)) {
                            checkState(
                                    low(node) != low(j)
                                            || high(node) != high(j)
                                            || dataGetVariable(dataI) != dataGetVariable(dataJ),
                                    "Duplicate entries (%s) and (%s)",
                                    nodeToStringSupplier(node),
                                    nodeToStringSupplier(j));
                        }
                    }
                }
            }
        }

        int maximalNodeCountCheckedSet = 2048;
        if (tableSize() < maximalNodeCountCheckedSet) {
            logger.log(Level.FINER, "Checking duplicate nodes");

            Set<Node> nodes = new HashSet<>();
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                if (isNodeValid(node)) {
                    checkState(
                            nodes.add(new Node(variable(node), low(node), high(node))),
                            "Duplicate entry (%s)",
                            nodeToStringSupplier(node));
                }
            }
        }

        // Check the integrity of the hash chain
        for (int node = FIRST_NODE; node < tableSize(); node++) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                // Check if each element is in its own hash chain
                int chainPosition = hashToChainStart[hashNode(node, data)];
                boolean found = false;
                StringBuilder hashChain = new StringBuilder(32);
                while (chainPosition != NOT_A_NODE) {
                    hashChain.append(' ').append(chainPosition);
                    if (chainPosition == node) {
                        found = true;
                        break;
                    }
                    chainPosition = this.hashChain[chainPosition];
                }
                checkState(found, "(%s) is not contained in it's hash list: %s", nodeToStringSupplier(node), hashChain);
            }
        }

        // Check firstFreeNode
        for (int i = FIRST_NODE; i < firstFreeNode; i++) {
            checkState(
                    dataIsValid(nodeData[i]), "Invalid node (%s) smaller than firstFreeNode", nodeToStringSupplier(i));
        }

        // Check free nodes chain
        int currentFreeNode = firstFreeNode;
        do {
            checkState(
                    !dataIsValid(nodeData[currentFreeNode]),
                    "Node (%s) in free node chain is valid",
                    nodeToStringSupplier(currentFreeNode));
            int nextFreeNode = hashChain[currentFreeNode];
            // This also excludes possible loops
            checkState(
                    nextFreeNode == FIRST_NODE || currentFreeNode < nextFreeNode,
                    "Free node chain is not well ordered, %s <= %s",
                    nextFreeNode,
                    currentFreeNode);
            checkState(
                    nextFreeNode < nodeData.length,
                    "Next free node points over horizon, %s -> %s (%s)",
                    currentFreeNode,
                    nextFreeNode,
                    nodeData.length);
            currentFreeNode = nextFreeNode;
        } while (currentFreeNode != FIRST_NODE);

        return true;
    }

    void invalidateCache() {
        cache.invalidate();
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("BDD%s@%d(%d)", iterative ? "iter" : "rec", tableSize(), System.identityHashCode(this));
    }

    @Override
    public String statistics() {
        return getStatistics() + '\n' + cache.getStatistics();
    }

    String nodeToString(int node) {
        int metadata = nodeData[node];
        if (!dataIsValid(metadata)) {
            return String.format("%5d| == INVALID ==", node);
        }
        String referenceCountString;
        if (dataIsSaturated(metadata)) {
            referenceCountString = "SAT";
        } else {
            referenceCountString = String.format("%3d", dataGetReferenceCount(metadata));
        }
        return String.format(
                "%5d|%3d|%5d|%5d|%s", node, dataGetVariable(metadata), low(node), high(node), referenceCountString);
    }

    NodeToStringSupplier nodeToStringSupplier(int node) {
        return new NodeToStringSupplier(this, node);
    }

    /**
     * Generates a string representation of the given {@code node}.
     *
     * @param node The node to be printed.
     * @return A string representing the given node.
     */
    public String treeToString(int node) {
        assert isNodeValidOrLeaf(node);
        assert isNoneMarked();
        if (isLeaf(node)) {
            return String.format("Node %d%n", node);
        }
        //noinspection MagicNumber
        StringBuilder builder =
                new StringBuilder(50).append("Node ").append(node).append('\n').append("  NODE|VAR| LOW | HIGH|REF\n");
        treeToStringRecursive(node, builder);
        unMarkAllBelow(node);
        return builder.toString();
    }

    private void treeToStringRecursive(int node, StringBuilder builder) {
        if (isLeaf(node)) {
            return;
        }
        int metadata = nodeData[node];
        if (dataIsMarked(metadata)) {
            return;
        }
        nodeData[node] = dataSetMark(metadata);
        builder.append(' ').append(nodeToString(node)).append('\n');
        treeToStringRecursive(low(node), builder);
        treeToStringRecursive(high(node), builder);
    }

    public String getStatistics() {
        int childrenCount = 0;
        int saturatedNodes = 0;
        int referencedNodes = 0;
        int validNodes = 0;

        for (int node = 0; node < tableSize(); node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata)) {
                validNodes += 1;
                if (dataIsReferencedOrSaturated(metadata)) {
                    referencedNodes += 1;
                    childrenCount += markAllBelow(node);

                    if (dataIsSaturated(metadata)) {
                        saturatedNodes += 1;
                    }
                }
            }
        }

        unMarkAll();

        int[] chainLength = new int[tableSize()];
        Deque<Integer> path = new ArrayDeque<>();
        int distinctChains = 0;

        for (int node = FIRST_NODE; node < tableSize(); node++) {
            int metadata = nodeData[node];
            if (chainLength[node] > 0) {
                continue;
            }

            if (dataIsValid(metadata)) {
                int chainPosition = hashToChainStart[hashNode(node, metadata)];
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
                tableSize(),
                biggestReferencedNode,
                createdNodes,
                validNodes,
                referencedNodes,
                saturatedNodes,
                childrenCount,
                distinctChains,
                sum * 1.0 / tableSize(),
                sum * 1.0 / distinctChains,
                max,
                hashChainLookups,
                hashChainLookupLength * 1.0 / hashChainLookups,
                garbageCollectionCount,
                garbageCollectionTime / 1000.0,
                garbageCollectedNodeCount,
                growCount);
    }

    // Static utility methods

    private static int min(int a, int b, int c) {
        return a < b ? Math.min(a, c) : Math.min(b, c);
    }

    private static void checkState(boolean state) {
        if (!state) {
            throw new IllegalStateException("");
        }
    }

    private static void checkState(boolean state, String formatString, Object... format) {
        if (!state) {
            throw new IllegalStateException(String.format(formatString, format));
        }
    }

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
        return ((metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK) == REFERENCE_COUNT_SATURATED;
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

    private static int dataSetMark(int metadata) {
        return metadata | 1;
    }

    private static int dataClearMark(int metadata) {
        return metadata & ~1;
    }

    private static boolean dataIsMarked(int metadata) {
        return (metadata & 1) != 0;
    }

    // Utility classes

    static final class NodeSolutionIterator implements Iterator<BitSet> {
        private static final int NON_PATH_NODE = NOT_A_NODE;

        private final BddImpl bdd;
        private final BitSet assignment;
        private final BitSet support;
        private final int variableCount;
        private final int[] path;
        private boolean firstRun = true;
        private int highestLowVariableWithNonFalseHighBranch = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private boolean hasNextAssignment;
        private final int rootVariable;

        NodeSolutionIterator(BddImpl bdd, int node, BitSet support) {
            // Require at least one possible solution to exist.
            assert bdd.isNodeValid(node) || node == TRUE_NODE;
            variableCount = bdd.numberOfVariables();

            // Assignments don't make much sense otherwise
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(bdd.support(node), support);

            this.bdd = bdd;
            this.support = support;
            this.path = new int[variableCount];
            this.assignment = new BitSet(variableCount);
            rootVariable = bdd.variable(node);
            assert support.get(rootVariable);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = node;

            leafNodeVariable = 0;
            hasNextPath = true;
            hasNextAssignment = true;
        }

        @Override
        public boolean hasNext() {
            assert !hasNextPath || hasNextAssignment;
            return hasNextAssignment;
        }

        @Override
        public BitSet next() {
            assert IntStream.range(0, variableCount).allMatch(i -> support.get(i) || path[i] == NON_PATH_NODE);

            int currentNode;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
            } else {
                // Check if we can flip any non-path variable in the support
                boolean clearedAny = false;
                for (int index = support.nextSetBit(0); index >= 0; index = support.nextSetBit(index + 1)) {
                    // Strategy: Perform binary addition on the NON_PATH_NODEs over the support
                    // The tricky bit is to determine whether there is a "next element": Either there is
                    // another real path in the BDD or there is some variable which we still can flip to 1

                    if (path[index] == NON_PATH_NODE) {
                        if (assignment.get(index)) {
                            assignment.clear(index);
                            clearedAny = true;
                        } else {
                            assignment.set(index);
                            if (hasNextPath || clearedAny) {
                                hasNextAssignment = true;
                            } else {
                                hasNextAssignment = false;

                                // TODO This should be constant time to determine?
                                // TODO This only needs to run if we set the first non-path variable to 1
                                for (int i = support.nextSetBit(index + 1); i >= 0; i = support.nextSetBit(i + 1)) {
                                    if (path[i] == NON_PATH_NODE && !assignment.get(i)) {
                                        hasNextAssignment = true;
                                        break;
                                    }
                                }
                            }
                            assert bdd.evaluate(path[rootVariable], assignment);
                            return assignment;
                        }
                    }
                }

                // Situation: All non-path variables are set to zero, and we need to find a new path
                assert IntStream.range(0, variableCount)
                        .noneMatch(index -> path[index] == NON_PATH_NODE && assignment.get(index));
                assert hasNextPath
                        : "Expected another path after " + assignment + ", node:\n"
                                + bdd.treeToString(path[rootVariable]);

                // Backtrack on the current path until we find a node set to low and non-false high branch
                // to find a new path in the BDD
                // TODO Use highestLowVariableWithNonFalseHighBranch?
                currentNode = path[leafNodeVariable];
                int branchIndex = leafNodeVariable;
                while (assignment.get(branchIndex) || bdd.high(currentNode) == FALSE_NODE) {
                    // This node does not give us another branch, backtrack over the path until we get to
                    // the next element of the path
                    // TODO Could track the previous path element in int[]
                    do {
                        branchIndex = support.previousSetBit(branchIndex - 1);
                        if (branchIndex == -1) {
                            throw new NoSuchElementException("No next element");
                        }
                    } while (path[branchIndex] == NON_PATH_NODE);
                    currentNode = path[branchIndex];
                }
                assert !assignment.get(branchIndex) && bdd.high(currentNode) != FALSE_NODE;
                assert leafNodeVariable >= highestLowVariableWithNonFalseHighBranch;
                assert bdd.variable(currentNode) == branchIndex;

                // currentNode is the lowest node we can switch high; set the value and descend the tree
                assignment.clear(branchIndex + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchIndex + 1, leafNodeVariable + 1, NON_PATH_NODE);

                assignment.set(branchIndex);
                assert path[branchIndex] == currentNode;
                currentNode = bdd.high(currentNode);
                assert currentNode != FALSE_NODE;
                leafNodeVariable = branchIndex;

                // We flipped the candidate for low->high transition, clear this information
                if (highestLowVariableWithNonFalseHighBranch == leafNodeVariable) {
                    highestLowVariableWithNonFalseHighBranch = -1;
                }
            }

            // Situation: The currentNode valuation was just flipped to 1 or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestLowVariableWithNonFalseHighBranch > -1
                    && highestLowVariableWithNonFalseHighBranch < leafNodeVariable;

            while (currentNode != TRUE_NODE) {
                assert currentNode != FALSE_NODE;
                leafNodeVariable = bdd.variable(currentNode);
                path[leafNodeVariable] = currentNode;
                assert support.get(leafNodeVariable);

                int low = bdd.low(currentNode);
                if (low == FALSE_NODE) {
                    // Descend high path
                    assignment.set(leafNodeVariable);
                    currentNode = bdd.high(currentNode);
                } else {
                    // If there is a non-false high node, we will be able to swap this node later on so we
                    // definitely have a next assignment. On the other hand, if there is no such node, the
                    // last possible assignment has been reached, as there are no more possible switches
                    // higher up in the tree.
                    if (!hasNextPath && bdd.high(currentNode) != FALSE_NODE) {
                        hasNextPath = true;
                        highestLowVariableWithNonFalseHighBranch = leafNodeVariable;
                    }
                    currentNode = low;
                }
            }
            assert bdd.evaluate(path[rootVariable], assignment);

            // If this is a unique path, there won't be any trivial assignments
            // TODO We can make this faster!
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                if (path[i] == NON_PATH_NODE) {
                    // We switched path so every non-path variable is low
                    assert !assignment.get(i);
                    hasNextAssignment = true;
                    return assignment;
                }
            }
            hasNextAssignment = hasNextPath;
            return assignment;
        }
    }

    private static final class NodeToStringSupplier {
        private final int node;
        private final BddImpl table;

        public NodeToStringSupplier(BddImpl table, int node) {
            this.table = table;
            this.node = node;
        }

        @Override
        public String toString() {
            return table.nodeToString(node);
        }
    }

    // Utility class to check for reduced-ness
    private static final class Node {
        final int var;
        final int low;
        final int high;

        Node(int var, int low, int high) {
            this.var = var;
            this.low = low;
            this.high = high;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Node)) {
                return false;
            }
            Node node = (Node) o;
            return var == node.var && low == node.low && high == node.high;
        }

        @Override
        public int hashCode() {
            return HashUtil.hash(var, low, high);
        }
    }
}
