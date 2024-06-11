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
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/* Implementation notes:
 * - Many of the methods are practically copy-paste of each other except for a few variables and
 *   corner cases, as the structure of BDD algorithms is the same for most of the operations.
 * - Due to the implementation of all operations, variable numbers increase while descending the
 *   tree of a particular node.
 */
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "PMD.AssignmentInOperand",
    "PMD.TooManyFields",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "ValueOfIncrementOrDecrementUsed",
    "NestedAssignment",
    "SameParameterValue"
})
final class BddImpl implements Bdd {
    private static final Logger logger = Logger.getLogger(BddImpl.class.getName());
    private static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    // Use 0 as "not a node" to make re-allocations slightly more efficient
    private static final int NOT_A_NODE = 0;
    private static final int FIRST_NODE = 1;
    // Prepare for complement edges
    private static final int TRUE_NODE = Integer.MAX_VALUE;
    private static final int FALSE_NODE = complement(TRUE_NODE);

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

    // Internal structure
    // There seems to be no performance difference to packing these into one array

    /* Low and high successors of each node */
    private int[] low;
    private int[] high;
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

    // Statistics
    private long createdNodes = 0;
    private long hashChainLookups = 0;
    private long hashChainLookupLength = 0;
    private int growCount = 0;
    private int partialCacheInvalidation = 0;
    private int garbageCollectionCount = 0;
    private long garbageCollectedNodeCount = 0;
    private long garbageCollectionTime = 0;

    BddImpl(BddConfiguration configuration) {
        this.configuration = configuration;

        int initialSize = Math.max(Primes.nextPrime(configuration.initialSize()), MINIMUM_NODE_TABLE_SIZE);
        low = new int[initialSize];
        high = new int[initialSize];
        nodeData = new int[initialSize];
        hashToChainStart = new int[initialSize];
        hashChain = new int[initialSize];

        firstFreeNode = FIRST_NODE;
        freeNodeCount = initialSize - FIRST_NODE;
        biggestReferencedNode = NOT_A_NODE;
        biggestValidNode = NOT_A_NODE;

        Arrays.fill(nodeData, dataMakeInvalid());
        // Arrays.fill(hashToChainStart, NOT_A_NODE);
        assert Arrays.stream(hashToChainStart).allMatch(i -> i == NOT_A_NODE);

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

    private static int complement(int node) {
        return -node;
    }

    private static int complementIf(int node, boolean condition) {
        return condition ? complement(node) : node;
    }

    private static boolean isComplemented(int node) {
        return node < 0;
    }

    private static boolean isRegular(int node) {
        return node > 0;
    }

    private static int regular(int node) {
        return node < 0 ? -node : node;
    }

    private static boolean isTrue(int node, boolean lookingFor) {
        return lookingFor ? (node == TRUE_NODE) : (node == FALSE_NODE);
    }

    private static boolean isFalse(int node, boolean lookingFor) {
        return lookingFor ? (node == FALSE_NODE) : (node == TRUE_NODE);
    }

    // Reference counting

    @Override
    public int referenceCount(int node) {
        return referenceCountNode(regular(node));
    }

    private int referenceCountNode(int node) {
        assert isNodeValidOrTerminal(node) && isRegular(node);
        return node == TRUE_NODE ? -1 : dataGetReferenceCountOrSaturated(nodeData[node]);
    }

    @Override
    public int reference(int node) {
        referenceNode(regular(node));
        return node;
    }

    private void referenceNode(int node) {
        assert isNodeValidOrTerminal(node) && isRegular(node);
        if (node == TRUE_NODE) {
            return;
        }
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

    @Override
    public int dereference(int node) {
        dereferenceNode(regular(node));
        return node;
    }

    private void dereferenceNode(int node) {
        assert isNodeValidOrTerminal(node) && isRegular(node);
        if (node == TRUE_NODE) {
            return;
        }
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

    int saturateNode(int node) {
        assert isRegular(node);
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
    public boolean isSaturated(int node) {
        return isSaturatedNode(regular(node));
    }

    private boolean isSaturatedNode(int node) {
        assert isRegular(node) && isNodeValidOrTerminal(node);
        return node == TRUE_NODE || dataIsSaturated(nodeData[regular(node)]);
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
        assert freedNodes >= 0 && isNoneMarked();
        return freedNodes;
    }

    /**
     * Tries to free space by garbage collection and, if that does not yield enough free nodes,
     * re-sizes the table, recreating hashes.
     *
     * @return Whether the table size has changed (and thus hashes have to be re-computed).
     */
    boolean ensureCapacity() {
        assert check();

        int currentSize = tableSize();
        if (configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
            logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes", new Object[] {
                this, currentSize, approximateDeadNodeCount
            });

            @SuppressWarnings("NumericCastThatLosesPrecision")
            // If we only can free few nodes, it is not worth the effort
            int minimumFreeNodeCount = (int) (currentSize * 0.3);

            // Leaves all referenced nodes marked
            int clearedNodes = doGarbageCollection(minimumFreeNodeCount);
            if (clearedNodes == -1) {
                logger.log(Level.FINE, "Not enough free nodes");

                // Clear marks and free all unreferenced nodes
                for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
                    int metadata = nodeData[node];
                    int cleared = dataClearMark(metadata);
                    if (metadata != cleared) {
                        nodeData[node] = cleared;
                    } else {
                        if (node == biggestValidNode) {
                            biggestValidNode--;
                        }
                        nodeData[node] = dataMakeInvalid();
                    }
                }
            } else {
                logger.log(Level.FINE, "Collected {0} nodes", clearedNodes);
                unMarkAll();
                return false;
            }
        }

        growCount += 1;
        @SuppressWarnings("NumericCastThatLosesPrecision")
        int newSize = Math.min(
                MAXIMAL_NODE_COUNT, Primes.nextPrime((int) Math.ceil(currentSize * configuration.growthFactor())));
        assert currentSize < newSize : "Got new size " + newSize + " with old size " + currentSize;

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, currentSize, newSize});

        low = Arrays.copyOf(low, newSize);
        high = Arrays.copyOf(high, newSize);
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

        cache.tableSizeChanged();
        logger.log(Level.FINE, "Finished growing the table");
        return true;
    }

    private int doGarbageCollection(int minimumFreeNodeCount) {
        assert check();
        long startTimestamp = System.currentTimeMillis();

        int referencedNodes = 0;
        for (int i = 0; i < workStackIndex; i++) {
            int node = workStack[i];
            if (!isTerminal(node) && isNodeValid(node)) {
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
            return -1;
        }

        // Clear chain starts (we need to rebuild them) and push referenced nodes on the mark stack.
        // TODO Can we omit that complete invalidation / re-use the existing chains? Should be easy enough - its just
        //   closed hashing
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

        int collectedNodes = freeNodeCount - previousFreeNodes;

        // Delete cache entries which are no longer valid
        // If we reclaimed a lot of nodes, we won't be able to save much
        if (configuration.useCachePartialInvalidate() && collectedNodes < tableSize() / 2) {
            this.partialCacheInvalidation += 1;
            cache.partialInvalidate();
        } else {
            cache.tableSizeChanged();
        }

        this.garbageCollectionTime += System.currentTimeMillis() - startTimestamp;
        this.garbageCollectedNodeCount += collectedNodes;
        this.garbageCollectionCount += 1;

        assert check();

        return collectedNodes;
    }

    private int hashNode(int node, int metadata) {
        assert isRegular(node) && dataIsValid(metadata);
        return hash(dataGetVariable(metadata), lowOfNode(node), highOfNode(node));
    }

    private int hash(int variable, int low, int high) {
        // TODO This silly "hash function" seems to be significantly better than any "proper" one and I have no idea why
        //   Conjecture: This leads to "locality" in the hash chain?
        int hash;
        if (low < 0) {
            hash = -low + high + variable;
        } else {
            hash = low + high + variable;
        }
        return (hash < 0 ? ~hash : hash) % tableSize();
    }

    private void connectHashList(int node, int hash) {
        assert isRegular(node);
        assert isNodeValid(node) && 0 <= hash && hash == hashNode(node, nodeData[node]);
        int hashChainStart = hashToChainStart[hash];
        int[] hashChain = this.hashChain;

        // Search the hash list if this node is already in there in order to avoid loops
        int chainLength = 1;
        int currentChain = hashChainStart;
        while (currentChain != NOT_A_NODE) {
            assert isRegular(currentChain);
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
        assert isRegular(node) && isNodeValid(node);
        return dataIsMarked(nodeData[node]);
    }

    private boolean isNoneMarked() {
        return findFirstMarked() == NOT_A_NODE;
    }

    private boolean isNoneMarkedBelow(int node) {
        assert isRegular(node);
        return node == TRUE_NODE
                || !isNodeMarked(node) && isNoneMarkedBelow(regular(low(node))) && isNoneMarkedBelow(high(node));
    }

    private boolean isAllMarkedBelow(int node) {
        assert isRegular(node);
        return node == TRUE_NODE
                || isNodeMarked(node) && isAllMarkedBelow(regular(low(node))) && isAllMarkedBelow(high(node));
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
        assert isNodeValidOrTerminal(node);
        return setMarkAllBelowRecursive(regular(node), true);
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
        assert isNodeValidOrTerminal(node) && isAllMarkedBelow(regular(node));
        int unmarkedCount = setMarkAllBelowRecursive(regular(node), false);
        assert isNoneMarkedBelow(regular(node));
        return unmarkedCount;
    }

    private int setMarkAllBelowRecursive(int node, boolean mark) {
        assert isRegular(node) && isNodeValidOrTerminal(node);

        if (node == TRUE_NODE) {
            return 0;
        }

        int metadata = nodeData[node];
        int modifiedData = dataSetMark(metadata, mark);

        if (metadata == modifiedData) {
            return 0;
        }
        nodeData[node] = modifiedData;
        return 1 + setMarkAllBelowRecursive(regular(low(node)), mark) + setMarkAllBelowRecursive(high(node), mark);
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
        assert isNodeValidOrTerminal(node);
        ensureWorkStackSize(workStackIndex);
        workStack[workStackIndex] = node;
        workStackIndex += 1;
        return node;
    }

    // Nodes

    private int makeNode(int variable, int low, int high) {
        assert 0 <= variable && variable < INVALID_NODE_VARIABLE;
        assert isTerminal(low) || variable < variable(low);
        assert isTerminal(high) || variable < variable(high);

        if (high == low) {
            // If both are complemented; we complement both and the complement the result
            return low;
        }

        int highNode = regular(high);
        boolean isHighComplement = high < highNode;
        int lowNode = complementIf(low, isHighComplement);

        int[] lowEdge = this.low;
        int[] highEdge = this.high;
        int[] nodeData = this.nodeData;
        int[] hashChain = this.hashChain;

        int hash = hash(variable, lowNode, highNode);
        int currentLookupNode = hashToChainStart[hash];
        assert currentLookupNode < tableSize() : "Invalid previous entry for " + hash;

        int chainLookups = 1;
        this.hashChainLookups += 1;
        // Search for the node in the hash chain
        while (currentLookupNode != NOT_A_NODE) {
            if ((nodeData[currentLookupNode] >>> VARIABLE_OFFSET) == variable
                    && lowEdge[currentLookupNode] == lowNode
                    && highEdge[currentLookupNode] == highNode) {
                this.hashChainLookupLength += chainLookups;
                return complementIf(currentLookupNode, isHighComplement);
            }
            assert currentLookupNode != hashChain[currentLookupNode];
            currentLookupNode = hashChain[currentLookupNode];
            chainLookups += 1;
        }
        this.hashChainLookupLength += chainLookups;

        /*
        else if (approximateDeadNodeCount > 0.1 * tableSize() && freeNodeCount < 0.1 * tableSize()) {
            tryGc();
        }
         */

        if (freeNodeCount <= tableSize() / 4) {
            // The hash table is very pressured, re-allocate
            if (ensureCapacity()) { // NOPMD
                // Table size has changed, hence re-hash
                hash = hash(variable, lowNode, highNode);
            }
        }

        // Here we need to use this.low etc. since we may have GC'd in between

        // Take next free node
        assert freeNodeCount > 0;
        createdNodes += 1;
        int freeNode = firstFreeNode;
        firstFreeNode = this.hashChain[freeNode];
        freeNodeCount--;
        assert !isNodeValidOrTerminal(freeNode) : "Overwriting existing node " + freeNode;
        assert FIRST_NODE <= firstFreeNode && firstFreeNode < tableSize() : "Invalid free node " + firstFreeNode;

        // Adjust and write node
        this.low[freeNode] = lowNode;
        this.high[freeNode] = highNode;
        this.nodeData[freeNode] = variable << VARIABLE_OFFSET;
        if (biggestValidNode < freeNode) {
            biggestValidNode = freeNode;
        }
        connectHashList(freeNode, hash);
        return complementIf(freeNode, isHighComplement);
    }

    @Override
    public int low(int node) {
        return lowOfNode(regular(node));
    }

    private int lowOfNode(int node) {
        assert isRegular(node) && isNodeValid(node);
        return low[node];
    }

    private int lowC(int node, boolean complement) {
        return complementIf(low(node), complement);
    }

    @Override
    public int high(int node) {
        return highOfNode(regular(node));
    }

    private int highOfNode(int node) {
        assert isRegular(node) && isNodeValid(node);
        return high[node];
    }

    private int highC(int node, boolean complement) {
        return complementIf(high(node), complement);
    }

    @Override
    public int variable(int node) {
        return variableOfNode(regular(node));
    }

    private int variableOfNode(int node) {
        assert isRegular(node) && isNodeValid(node);
        return dataGetVariable(nodeData[regular(node)]);
    }

    @Override
    public boolean isTerminal(int node) {
        return node == TRUE_NODE || node == FALSE_NODE;
    }

    public boolean isNodeValid(int node) {
        int nodeRegular = regular(node);
        return FIRST_NODE <= nodeRegular && nodeRegular <= biggestValidNode && dataIsValid(nodeData[nodeRegular]);
    }

    /**
     * Determines if the given {@code node} is either a root node or valid. For most operations it is
     * required that this is the case.
     *
     * @param node The node to be checked.
     * @return If {@code} is valid or root node.
     * @see #isTerminal(int)
     */
    public boolean isNodeValidOrTerminal(int node) {
        return isTerminal(node) || isNodeValid(node);
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

        if (numberOfVariables == variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, variableNodes.length * 2);
        }
        variableNodes[numberOfVariables] = variableNode;
        numberOfVariables++;

        cache.variablesChanged();
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
            newVariableNodes[i] = variableNode;
            this.variableNodes[variable] = variableNode;
        }
        numberOfVariables += count;

        cache.variablesChanged();
        ensureWorkStackSize(numberOfVariables * 2);

        return newVariableNodes;
    }

    @Override
    public boolean isVariable(int node) {
        return !isTerminal(node) && isRegular(node) && low(node) == FALSE_NODE && high(node) == TRUE_NODE;
    }

    @Override
    public boolean isVariableNegated(int node) {
        return isVariable(complement(node));
    }

    @Override
    public boolean isVariableOrNegated(int node) {
        assert isNodeValidOrTerminal(node);
        return isVariable(regular(node));
    }

    // Reading

    public BddConfiguration configuration() {
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
        assert isNodeValidOrTerminal(node);
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
        return approximateNodeCountRecursive(regular(node));
    }

    private int approximateNodeCountRecursive(int node) {
        assert isRegular(node) && isNodeValidOrTerminal(node);
        return isTerminal(node)
                ? 0
                : 1 + approximateNodeCountRecursive(regular(low(node))) + approximateNodeCountRecursive(high(node));
    }

    @Override
    public boolean evaluate(int node, boolean[] assignment) {
        int current = regular(node);
        boolean lookingFor = current == node;
        while (current != TRUE_NODE) {
            assert isNodeValid(current) && isRegular(current);
            if (assignment[variableOfNode(current)]) {
                current = high(current);
            } else {
                int low = low(current);
                current = regular(low);
                if (current != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public boolean evaluate(int node, BitSet assignment) {
        int current = regular(node);
        boolean lookingFor = current == node;
        while (current != TRUE_NODE) {
            assert isNodeValid(current);
            if (assignment.get(variableOfNode(current))) {
                current = high(current);
            } else {
                int lowNode = low(current);
                current = regular(lowNode);
                if (current != lowNode) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public BitSet getSatisfyingAssignment(int node) {
        assert isNodeValidOrTerminal(node);

        if (node == FALSE_NODE) {
            throw new NoSuchElementException("False has no solution");
        }

        BitSet path = new BitSet(numberOfVariables);
        int current = regular(node);
        boolean lookingFor = current == node;

        while (current != TRUE_NODE) {
            int lowNode = lowOfNode(current);
            if (isTrue(lowNode, !lookingFor)) {
                int highNode = highOfNode(current);
                int variable = variableOfNode(current);

                path.set(variable);
                current = highNode;
            } else {
                current = regular(lowNode);
                if (current != lowNode) {
                    lookingFor = !lookingFor;
                }
            }
        }
        assert lookingFor;
        return path;
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node) {
        assert isNodeValidOrTerminal(node);
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
        assert isNodeValidOrTerminal(node);
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
        assert isNodeValidOrTerminal(node);
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

        forEachPathRecursive(regular(node), null, numberOfVariables, path, pathSupport, action, isRegular(node));
    }

    @Override
    public void forEachPath(int node, BitSet relevantSet, BiConsumer<BitSet, BitSet> action) {
        assert isNodeValidOrTerminal(node);
        if (node == FALSE_NODE) {
            return;
        }
        if (node == TRUE_NODE || relevantSet.isEmpty()) {
            action.accept(new BitSet(0), new BitSet(0));
            return;
        }

        int highestVariable = relevantSet.length() - 1;
        BitSet path = new BitSet(highestVariable + 1);
        BitSet pathSupport = new BitSet(highestVariable + 1);

        forEachPathRecursive(regular(node), relevantSet, highestVariable, path, pathSupport, action, isRegular(node));
    }

    private void forEachPathRecursive(
            int node,
            @Nullable BitSet support,
            int depthLimit,
            BitSet path,
            BitSet pathSupport,
            BiConsumer<BitSet, BitSet> action,
            boolean lookingFor) {
        assert isRegular(node);
        assert isNodeValid(node) || node == TRUE_NODE;

        if (node == TRUE_NODE) {
            assert lookingFor;
            action.accept(path, pathSupport);
            return;
        }
        assert !isTerminal(node);

        int variable = variableOfNode(node);
        if (variable > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path, pathSupport);
            return;
        }

        int lowNode = lowOfNode(node);
        int highNode = highOfNode(node);
        boolean relevant = support == null || support.get(variable);

        if (relevant) {
            pathSupport.set(variable);
        }

        if (!isFalse(lowNode, lookingFor)) {
            forEachPathRecursive(
                    regular(lowNode), support, depthLimit, path, pathSupport, action, isRegular(lowNode) == lookingFor);
        }
        if (!isFalse(highNode, lookingFor)) {
            if (relevant) {
                path.set(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, pathSupport, action, lookingFor);
                assert path.get(variable);
                path.clear(variable);
            } else {
                assert !path.get(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, pathSupport, action, lookingFor);
            }
        }

        assert relevant == pathSupport.get(variable);
        if (relevant) {
            pathSupport.clear(variable);
        }
    }

    @Override
    public void forEachSupport(int node, IntConsumer action) {
        assert isNodeValidOrTerminal(node);

        int nodeRegular = regular(node);
        assert isNoneMarkedBelow(nodeRegular);
        supportRecursive(nodeRegular, action, null, numberOfVariables);
        setMarkAllBelowRecursive(nodeRegular, false);
        assert isNoneMarkedBelow(nodeRegular);
    }

    @Override
    public void forEachSupportFiltered(int node, BitSet filter, IntConsumer action) {
        assert isNodeValidOrTerminal(node);

        int depthLimit = filter.length();
        if (depthLimit == 0) {
            return;
        }

        int nodeRegular = regular(node);
        assert isNoneMarkedBelow(nodeRegular);
        supportRecursive(nodeRegular, action, filter, depthLimit);
        setMarkAllBelowRecursive(nodeRegular, false);
        assert isNoneMarkedBelow(nodeRegular);
    }

    private void supportRecursive(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
        assert isRegular(node);
        if (isTerminal(node)) {
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

        supportRecursive(regular(low(node)), action, filter, depthLimit);
        supportRecursive(high(node), action, filter, depthLimit);
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
        BigInteger satisfyingBelow = countSatisfyingAssignmentsRecursive(regular(node), isRegular(node));
        return TWO.pow(variable).multiply(satisfyingBelow);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node, BitSet support) {
        assert BitSets.isSubset(support(node), support);
        return countSatisfyingAssignments(node).divide(TWO.pow(numberOfVariables - support.cardinality()));
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node, boolean lookingFor) {
        assert isRegular(node) && isNodeValid(node);

        int nodeVar = variableOfNode(node);

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return lookingFor
                    ? cacheLookup
                    : TWO.pow(numberOfVariables - nodeVar).subtract(cacheLookup);
        }
        int hash = cache.lookupHash();

        int lowNode = lowOfNode(node);
        BigInteger lowCount = doCountSatisfyingAssignments(regular(lowNode), nodeVar, isRegular(lowNode) == lookingFor);
        BigInteger highCount = doCountSatisfyingAssignments(highOfNode(node), nodeVar, lookingFor);

        BigInteger result = lowCount.add(highCount);
        cache.putSatisfaction(
                hash,
                node,
                lookingFor ? result : TWO.pow(numberOfVariables - nodeVar).subtract(result));
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int subNode, int currentVar, boolean lookingFor) {
        if (isFalse(subNode, lookingFor)) {
            return BigInteger.ZERO;
        }
        if (isTrue(subNode, lookingFor)) {
            return TWO.pow(numberOfVariables - currentVar - 1);
        }
        BigInteger multiplier = TWO.pow(variable(subNode) - currentVar - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(subNode, lookingFor));
    }

    // Bdd operations

    @Override
    public int conjunction(int... variables) {
        int node = TRUE_NODE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            node = andRecursive(pushToWorkStack(node), variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int conjunction(BitSet variables) {
        int node = TRUE_NODE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            node = andRecursive(pushToWorkStack(node), variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(int... variables) {
        int node = FALSE_NODE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            node = orRecursive(pushToWorkStack(node), variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(BitSet variables) {
        int node = FALSE_NODE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            node = orRecursive(pushToWorkStack(node), variableNodes[variable]);
            popWorkStack();
        }
        return node;
    }

    @Override
    public int and(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrTerminal(node1) && isNodeValidOrTerminal(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = andRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
    }

    private int andRecursive(int node1, int node2) {
        if (node1 == TRUE_NODE) {
            return node2;
        }
        if (node2 == TRUE_NODE) {
            return node1;
        }
        if (node1 == FALSE_NODE || node2 == FALSE_NODE) {
            return FALSE_NODE;
        }
        if (node1 == node2) {
            return node1;
        }
        if (node1 == complement(node2)) {
            return FALSE_NODE;
        }

        assert !isTerminal(node1) && !isTerminal(node2);

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

        boolean node1c = isComplemented(node1);
        int node1low = lowC(node1, node1c);
        int node1high = highC(node1, node1c);

        int lowNode;
        int highNode;
        if (node1var == node2var) {
            boolean node2c = isComplemented(node2);
            lowNode = pushToWorkStack(andRecursive(node1low, lowC(node2, node2c)));
            highNode = pushToWorkStack(andRecursive(node1high, highC(node2, node2c)));
        } else { // node1var < node2var
            lowNode = pushToWorkStack(andRecursive(node1low, node2));
            highNode = pushToWorkStack(andRecursive(node1high, node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putAnd(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int andNot(int node1, int node2) {
        assert isNodeValidOrTerminal(node1) && isNodeValidOrTerminal(node2);
        return and(node1, complement(node2));
    }

    @Override
    public int compose(int node, int[] variableMapping) {
        assert isWorkStackEmpty();
        assert variableMapping.length <= numberOfVariables;

        if (isTerminal(node)) {
            return node;
        }

        // Guard the elements and replace placeholder by actual variable reference
        pushToWorkStack(node);
        int workStackCount = 1;
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] == NOT_A_NODE) {
                variableMapping[i] = this.variableNodes[i];
            } else {
                assert isNodeValidOrTerminal(variableMapping[i]);
                if (!isSaturated(variableMapping[i])) {
                    pushToWorkStack(variableMapping[i]);
                    workStackCount++;
                }
            }
        }

        int highestReplacedVariable = variableMapping.length - 1;
        // Optimise the replacement array
        // Note: Could also detect the case that everything is identity or true / false; which would be "restrict"
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
        int result = composeRecursive(node, variableMapping, highestReplacedVariable);
        popWorkStack(workStackCount);
        assert isWorkStackEmpty();
        return result;
    }

    private int composeRecursive(int node, int[] variableNodes, int highestReplacedVariable) {
        if (isTerminal(node)) {
            return node;
        }

        int nodeVariable = variable(node);
        if (nodeVariable > highestReplacedVariable) {
            return node;
        }

        int nodeRegular = regular(node);
        boolean regular = nodeRegular == node;

        if (cache.lookupCompose(nodeRegular)) {
            return complementIf(cache.lookupResult(), !regular);
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
        cache.putCompose(hash, nodeRegular, resultNode);
        return complementIf(resultNode, !regular);
    }

    @Override
    public int equivalence(int node1, int node2) {
        assert isNodeValidOrTerminal(node1) && isNodeValidOrTerminal(node2);
        return complement(xor(node1, node2));
    }

    @Override
    public int exists(int node, BitSet quantifiedVariables) {
        assert isWorkStackEmpty();
        assert quantifiedVariables.previousSetBit(quantifiedVariables.length()) <= numberOfVariables;
        if (node == FALSE_NODE) {
            return FALSE_NODE;
        }
        if (quantifiedVariables.cardinality() == numberOfVariables) {
            return TRUE_NODE;
        }

        cache.initQuantification(quantifiedVariables);

        boolean regular = isRegular(node);
        pushToWorkStack(node);
        int result = complementIf(quantifyRecursive(regular(node), quantifiedVariables, regular), !regular);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
    }

    private int quantifyRecursive(int node, BitSet quantifiedVariables, boolean exists) {
        if (isTerminal(node)) {
            return node;
        }

        int nodeVariable = variable(node);
        int currentCubeNodeVariable = quantifiedVariables.nextSetBit(nodeVariable);
        if (currentCubeNodeVariable == -1) {
            return node;
        }
        if (isVariableOrNegated(node)) {
            if (nodeVariable == currentCubeNodeVariable) {
                return exists ? TRUE_NODE : FALSE_NODE;
            }
            return node;
        }

        if (cache.lookupQuantification(node, exists)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        boolean isRegular = isRegular(node);
        int lowExists = pushToWorkStack(
                complementIf(quantifyRecursive(low(node), quantifiedVariables, isRegular == exists), !isRegular));
        int highExists = pushToWorkStack(
                complementIf(quantifyRecursive(high(node), quantifiedVariables, isRegular == exists), !isRegular));
        int resultNode;

        if (currentCubeNodeVariable > nodeVariable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            resultNode = makeNode(nodeVariable, lowExists, highExists);
        } else {
            // nodeVariable == nextVariable, i.e. "quantify out" the current node.
            resultNode = exists ? orRecursive(lowExists, highExists) : andRecursive(lowExists, highExists);
        }

        popWorkStack(2);
        cache.putQuantification(hash, node, exists, resultNode);
        return resultNode;
    }

    @Override
    public int ifThenElse(int ifNode, int thenNode, int elseNode) {
        assert isWorkStackEmpty();
        assert isNodeValidOrTerminal(ifNode) && isNodeValidOrTerminal(thenNode) && isNodeValidOrTerminal(elseNode);
        pushToWorkStack(ifNode);
        pushToWorkStack(thenNode);
        pushToWorkStack(elseNode);
        int result = ifThenElseRecursive(ifNode, thenNode, elseNode);
        popWorkStack(3);
        assert isWorkStackEmpty();
        return result;
    }

    private int ifThenElseRecursive(int ifNode, int thenNode, int elseNode) {
        if (ifNode == TRUE_NODE) {
            return thenNode;
        }
        if (ifNode == FALSE_NODE) {
            return elseNode;
        }

        if (thenNode == TRUE_NODE || thenNode == ifNode) {
            return orRecursive(ifNode, elseNode);
        }
        if (thenNode == FALSE_NODE || thenNode == complement(ifNode)) {
            return andRecursive(complement(ifNode), elseNode);
        }

        if (elseNode == TRUE_NODE || elseNode == complement(ifNode)) {
            return complement(andRecursive(ifNode, complement(thenNode)));
        }
        if (elseNode == FALSE_NODE || ifNode == elseNode) {
            return andRecursive(ifNode, thenNode);
        }

        if (thenNode == elseNode) {
            return thenNode;
        }
        if (thenNode == complement(elseNode)) {
            return xorRecursive(ifNode, elseNode);
        }

        // Normalize so that at most else is complemented
        int ifNormalized = regular(ifNode);
        int thenSwap;
        int elseSwap;
        if (ifNormalized != ifNode) {
            thenSwap = elseNode;
            elseSwap = thenNode;
        } else {
            thenSwap = thenNode;
            elseSwap = elseNode;
        }

        boolean complement = false;
        int thenNormalized = regular(thenSwap);
        int elseNormalized;
        if (thenNormalized != thenSwap) {
            elseNormalized = complement(elseSwap);
            complement = true;
        } else {
            elseNormalized = elseSwap;
        }
        assert isRegular(ifNormalized) && isRegular(thenNormalized);

        if (cache.lookupIfThenElse(ifNormalized, thenNormalized, elseNormalized)) {
            return complementIf(cache.lookupResult(), complement);
        }
        int hash = cache.lookupHash();
        int ifVar = variable(ifNormalized);
        int thenVar = variable(thenNormalized);
        int elseVar = variable(elseNormalized);

        int minVar = Math.min(ifVar, Math.min(thenVar, elseVar));
        int ifLowNode;
        int ifHighNode;

        if (ifVar == minVar) {
            ifLowNode = low(ifNormalized);
            ifHighNode = high(ifNormalized);
        } else {
            ifLowNode = ifNormalized;
            ifHighNode = ifNormalized;
        }

        int thenHighNode;
        int thenLowNode;
        if (thenVar == minVar) {
            thenLowNode = low(thenNormalized);
            thenHighNode = high(thenNormalized);
        } else {
            thenLowNode = thenNormalized;
            thenHighNode = thenNormalized;
        }

        int elseHighNode;
        int elseLowNode;
        if (elseVar == minVar) {
            boolean else1c = isComplemented(elseNormalized);
            elseLowNode = lowC(elseNormalized, else1c);
            elseHighNode = highC(elseNormalized, else1c);
        } else {
            elseLowNode = elseNormalized;
            elseHighNode = elseNormalized;
        }

        int lowNode = pushToWorkStack(ifThenElseRecursive(ifLowNode, thenLowNode, elseLowNode));
        int highNode = pushToWorkStack(ifThenElseRecursive(ifHighNode, thenHighNode, elseHighNode));
        int result = makeNode(minVar, lowNode, highNode);
        popWorkStack(2);
        cache.putIfThenElse(hash, ifNormalized, thenNormalized, elseNormalized, result);
        return complementIf(result, complement);
    }

    @Override
    public int implication(int node1, int node2) {
        assert isWorkStackEmpty();
        return complement(and(node1, complement(node2)));
    }

    @Override
    public boolean implies(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrTerminal(node1) && isNodeValidOrTerminal(node2);
        boolean result = impliesRecursive(node1, node2);
        assert isWorkStackEmpty();
        return result;
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
        if (node1 == complement(node2)) {
            return false;
        }

        if (cache.lookupImplies(node1, node2)) {
            return cache.lookupResult() == TRUE_NODE;
        }
        int hash = cache.lookupHash();

        int node1var = variable(node1);
        int node2var = variable(node2);

        boolean result;
        if (node1var == node2var) {
            boolean node1c = isComplemented(node1);
            boolean node2c = isComplemented(node2);
            result = impliesRecursive(lowC(node1, node1c), lowC(node2, node2c))
                    && impliesRecursive(highC(node1, node1c), highC(node2, node2c));
        } else if (node1var < node2var) {
            boolean node1c = isComplemented(node1);
            result = impliesRecursive(lowC(node1, node1c), node2) && impliesRecursive(highC(node1, node1c), node2);
        } else {
            boolean node2c = isComplemented(node2);
            result = impliesRecursive(node1, lowC(node2, node2c)) && impliesRecursive(node1, highC(node2, node2c));
        }
        cache.putImplies(hash, node1, node2, result);
        return result;
    }

    @Override
    public int not(int node) {
        assert isNodeValidOrTerminal(node);
        return complement(node);
    }

    @Override
    public int notAnd(int node1, int node2) {
        return complement(and(node1, node2));
    }

    @Override
    public int or(int node1, int node2) {
        return complement(and(complement(node1), complement(node2)));
    }

    private int orRecursive(int node1, int node2) {
        return complement(andRecursive(complement(node1), complement(node2)));
    }

    @Override
    public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isWorkStackEmpty();
        assert isNodeValidOrTerminal(node);

        if (restrictedVariables.isEmpty()) {
            return node;
        }
        if (isTerminal(node)) {
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
        int result = composeRecursive(node, composeArray, highestReplacement);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
    }

    @Override
    public int xor(int node1, int node2) {
        assert isWorkStackEmpty();
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int ret = xorRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return ret;
    }

    private int xorRecursive(int node1, int node2) {
        if (node1 == node2) {
            return FALSE_NODE;
        }
        if (node1 == TRUE_NODE) {
            return complement(node2);
        }
        if (node1 == FALSE_NODE) {
            return node2;
        }
        if (node2 == TRUE_NODE) {
            return complement(node1);
        }
        if (node2 == FALSE_NODE) {
            return node1;
        }
        if (node1 == complement(node2)) {
            return TRUE_NODE;
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
        boolean node1c = isComplemented(node1);
        if (node1var == node2var) {
            boolean node2c = isComplemented(node2);
            lowNode = pushToWorkStack(xorRecursive(complementIf(low(node1), node1c), complementIf(low(node2), node2c)));
            highNode =
                    pushToWorkStack(xorRecursive(complementIf(high(node1), node1c), complementIf(high(node2), node2c)));
        } else { // node1var < node2var
            lowNode = pushToWorkStack(xorRecursive(complementIf(low(node1), node1c), node2));
            highNode = pushToWorkStack(xorRecursive(complementIf(high(node1), node1c), node2));
        }
        int resultNode = makeNode(node1var, lowNode, highNode);
        popWorkStack(2);
        cache.putXor(hash, node1, node2, resultNode);
        return resultNode;
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
                        isNodeValidOrTerminal(low),
                        "Invalid low entry (%s) -> (%s)",
                        nodeToStringSupplier(node),
                        nodeToStringSupplier(low));
                checkState(
                        isNodeValidOrTerminal(high),
                        "Invalid high entry (%s) -> (%s)",
                        nodeToStringSupplier(node),
                        nodeToStringSupplier(high));
                if (!isTerminal(low)) {
                    checkState(
                            dataGetVariable(metadata) < dataGetVariable(nodeData[regular(low)]),
                            "(%s) -> (%s) does not descend tree",
                            nodeToStringSupplier(node),
                            nodeToStringSupplier(low));
                }
                if (!isTerminal(high)) {
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
        int maximalNodeCountCheckedPairs = 2048;
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
        cache.tableSizeChanged();
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("BDD@%d(%d)", tableSize(), System.identityHashCode(this));
    }

    @Override
    public String statistics() {
        return getStatistics() + '\n' + cache.getStatistics();
    }

    private String formatNode(int node) {
        if (node == TRUE_NODE) {
            return "TRUE";
        }
        if (node == FALSE_NODE) {
            return "FALSE";
        }
        return String.format("%s%d", isRegular(node) ? "" : "!", regular(node));
    }

    String nodeToString(int node) {
        int nodeRegular = regular(node);
        int metadata = nodeData[nodeRegular];
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
                "%5s|%3d|%5s|%5s|%s",
                formatNode(nodeRegular),
                dataGetVariable(metadata),
                formatNode(low(node)),
                formatNode(high(node)),
                referenceCountString);
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
        assert isNodeValidOrTerminal(node);
        assert isNoneMarked();
        if (isTerminal(node)) {
            return String.format("Node %s%n", formatNode(node));
        }
        //noinspection MagicNumber
        StringBuilder builder = new StringBuilder(50)
                .append("Node ")
                .append(formatNode(node))
                .append('\n')
                .append("  NODE|VAR| LOW | HIGH|REF\n");
        treeToStringRecursive(node, builder);
        unMarkAllBelow(node);
        return builder.toString();
    }

    private void treeToStringRecursive(int node, StringBuilder builder) {
        if (isTerminal(node)) {
            return;
        }
        int nodeRegular = regular(node);
        int metadata = nodeData[nodeRegular];
        if (dataIsMarked(metadata)) {
            return;
        }
        nodeData[nodeRegular] = dataSetMark(metadata);
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
                        + "%14$d GC runs (%15$.2f s), %16$d freed, %17$d partial invalidation, %18$d grows",
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
                partialCacheInvalidation,
                growCount);
    }

    // Static utility methods

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

    // Utility classes

    static final class NodeSolutionIterator implements Iterator<BitSet> {
        private static final int NON_PATH_NODE = NOT_A_NODE;

        private final BddImpl bdd;
        private final BitSet assignment;
        private final BitSet support;
        private final int variableCount;
        private final int[] path;
        private final boolean[] pathLookingFor;
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
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new BitSet(variableCount);
            rootVariable = bdd.variable(node);
            assert support.get(rootVariable);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = regular(node);
            pathLookingFor[rootVariable] = isRegular(node);

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
            boolean currentLookingFor;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
                currentLookingFor = pathLookingFor[rootVariable];
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
                            assert bdd.evaluate(
                                    complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);
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
                currentLookingFor = pathLookingFor[leafNodeVariable];
                int branchIndex = leafNodeVariable;
                while (assignment.get(branchIndex) || isFalse(bdd.high(currentNode), currentLookingFor)) {
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
                    currentLookingFor = pathLookingFor[branchIndex];
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
                assert isRegular(currentNode);
                assert !isFalse(currentNode, currentLookingFor);
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

            while (!isTrue(currentNode, currentLookingFor)) {
                assert isRegular(currentNode) && !bdd.isTerminal(currentNode);

                leafNodeVariable = bdd.variable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathLookingFor[leafNodeVariable] = currentLookingFor;
                assert support.get(leafNodeVariable);

                int low = bdd.low(currentNode);
                if (isFalse(low, currentLookingFor)) {
                    // Descend high path
                    assignment.set(leafNodeVariable);
                    currentNode = bdd.high(currentNode);
                } else {
                    // If there is a non-false high node, we will be able to swap this node later on, so we
                    // definitely have a next assignment. On the other hand, if there is no such node, the
                    // last possible assignment has been reached, as there are no more possible switches
                    // higher up in the tree.
                    if (!hasNextPath && !isFalse(bdd.high(currentNode), currentLookingFor)) {
                        hasNextPath = true;
                        highestLowVariableWithNonFalseHighBranch = leafNodeVariable;
                    }
                    currentNode = regular(low);
                    if (currentNode != low) {
                        currentLookingFor = !currentLookingFor;
                    }
                }
            }
            assert bdd.evaluate(complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);

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
