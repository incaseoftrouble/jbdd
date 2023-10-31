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

import static de.tum.in.jbdd.Util.checkState;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class NodeTable implements DecisionDiagram {
    private static final Logger logger = Logger.getLogger(NodeTable.class.getName());

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

    static {
        //noinspection ConstantValue
        assert VARIABLE_BIT_SIZE + REFERENCE_COUNT_BIT_SIZE + 1 == Integer.SIZE;
    }

    static int dataMake(int variable) {
        return variable << VARIABLE_OFFSET;
    }

    static int dataGetVariable(int metadata) {
        assert dataIsValid(metadata);
        return dataGetVariableUnsafe(metadata);
    }

    static int dataGetVariableUnsafe(int metadata) {
        return metadata >>> VARIABLE_OFFSET;
    }

    static boolean dataIsValid(int metadata) {
        return (metadata >>> VARIABLE_OFFSET) != INVALID_NODE_VARIABLE;
    }

    static int dataMakeInvalid() {
        return INVALID_NODE_VARIABLE << VARIABLE_OFFSET;
    }

    static boolean dataIsSaturated(int metadata) {
        return ((metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK) == REFERENCE_COUNT_SATURATED;
    }

    static int dataSaturate(int metadata) {
        return metadata | (REFERENCE_COUNT_SATURATED << REFERENCE_COUNT_OFFSET);
    }

    static boolean dataIsReferencedOrSaturated(int metadata) {
        return dataGetReferenceCountUnsafe(metadata) > 0;
    }

    static int dataGetReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata);
        return (metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK;
    }

    static int dataGetReferenceCountUnsafe(int metadata) {
        return (metadata >>> REFERENCE_COUNT_OFFSET) & REFERENCE_COUNT_MASK;
    }

    static int dataIncreaseReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata);
        return metadata + 2;
    }

    static int dataDecreaseReferenceCount(int metadata) {
        assert !dataIsSaturated(metadata) && dataGetReferenceCount(metadata) > 0;
        return metadata - 2;
    }

    static int dataSetMark(int metadata) {
        return metadata | 1;
    }

    static int dataClearMark(int metadata) {
        return metadata & ~1;
    }

    static boolean dataIsMarked(int metadata) {
        return (metadata & 1) != 0;
    }

    static boolean countIsSaturated(int referenceCount) {
        return referenceCount == REFERENCE_COUNT_SATURATED;
    }

    // Use 0 as "not a node" to make re-allocations slightly more efficient
    protected static final int NOT_A_NODE = 0;
    protected static final int FIRST_NODE = 1;

    private static final int MINIMUM_NODE_TABLE_SIZE = Primes.nextPrime(1_000);
    private static final int MAXIMAL_NODE_COUNT = Integer.MAX_VALUE / 2 - 8;

    /* Approximation of dead node count. */
    private int approximateDeadNodeCount = 0;
    private final double minimumFreeNodeAfterGc;
    private final double growthFactor;

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

    /* Stores the meta-data for BDD nodes, namely the variable number, reference count and a mask used
     * by various internal algorithms. These values are manipulated through static helper functions.
     *
     * Layout: <---VAR---><---REF---><MASK> */
    private int[] nodes;

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
    private long hashChainLookupHit = 0;
    private long growCount = 0;
    private long garbageCollectionCount = 0;
    private long garbageCollectedNodeCount = 0;
    private long garbageCollectionTime = 0;

    public NodeTable(int initialSize, double minimumFreeNodeAfterGc, double growthFactor) {
        this.minimumFreeNodeAfterGc = minimumFreeNodeAfterGc;
        this.growthFactor = growthFactor;
        int tableSize = Math.max(Primes.nextPrime(initialSize), MINIMUM_NODE_TABLE_SIZE);

        nodes = new int[tableSize];
        hashToChainStart = new int[tableSize];
        hashChain = new int[tableSize];

        firstFreeNode = FIRST_NODE;
        freeNodeCount = tableSize - FIRST_NODE;
        biggestReferencedNode = placeholder();
        biggestValidNode = placeholder();

        Arrays.fill(nodes, dataMakeInvalid());
        // Arrays.fill(hashToChainStart, placeholder());

        // Just to ensure a fail-fast
        Arrays.fill(hashChain, 0, FIRST_NODE, Integer.MIN_VALUE);
        for (int i = FIRST_NODE; i < tableSize - 1; i++) {
            hashChain[i] = i + 1;
        }
        hashChain[tableSize - 1] = FIRST_NODE;

        workStack = new int[32];
    }

    @Override
    public int variableOf(int node) {
        assert isNodeValidOrLeaf(node);
        return isLeaf(node) ? -1 : dataGetVariable(nodes[node]);
    }

    @Override
    public final int placeholder() {
        return NOT_A_NODE;
    }

    public boolean isNodeValid(int node) {
        assert -2 <= node && node < tableSize();
        return FIRST_NODE <= node && node <= biggestValidNode && dataIsValid(nodes[node]);
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

    // Work stack

    protected final void ensureWorkStackSize(int size) {
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
    protected final void popWorkStack() {
        assert !isWorkStackEmpty();
        workStackIndex--;
    }

    /**
     * Removes the {@code amount} topmost elements from the stack.
     *
     * @param amount The amount of elements to be removed.
     * @see #pushToWorkStack(int)
     */
    protected final void popWorkStack(int amount) {
        assert workStackIndex >= amount;
        workStackIndex -= amount;
    }

    protected final int peekWorkStack() {
        assert !isWorkStackEmpty();
        return workStack[workStackIndex - 1];
    }

    protected final int peekAndPopWorkStack() {
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
    protected final int pushToWorkStack(int node) {
        assert isNodeValidOrLeaf(node);
        ensureWorkStackSize(workStackIndex);
        workStack[workStackIndex] = node;
        workStackIndex += 1;
        return node;
    }

    protected final int[] pushToWorkStack(int[] nodes) {
        assert Arrays.stream(nodes).allMatch(this::isNodeValidOrLeaf);
        ensureWorkStackSize(workStackIndex + nodes.length - 1);
        System.arraycopy(nodes, 0, workStack, workStackIndex, nodes.length);
        workStackIndex += nodes.length;
        return nodes;
    }

    // Reference counting

    @Override
    public int referenceCount(int node) {
        assert isNodeValidOrLeaf(node);
        if (isLeaf(node)) {
            return -1;
        }
        int metadata = nodes[node];
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
        int metadata = nodes[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (countIsSaturated(referenceCount)) {
            return node;
        }
        assert 0 <= dataGetReferenceCount(metadata);

        nodes[node] = dataIncreaseReferenceCount(metadata);
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
        int metadata = nodes[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (countIsSaturated(referenceCount)) {
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
                    if (dataIsReferencedOrSaturated(nodes[i])) {
                        biggestReferencedNode = i;
                        break;
                    }
                }
            }
        }
        nodes[node] = dataDecreaseReferenceCount(metadata);
        return node;
    }

    @Override
    public int referencedNodeCount() {
        int[] nodes = this.nodes;
        int count = 0;

        for (int i = FIRST_NODE; i <= biggestReferencedNode; i++) {
            int metadata = nodes[i];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int saturateNode(int node) {
        assert isNodeValidOrLeaf(node);
        if (node > biggestReferencedNode) {
            biggestReferencedNode = node;
        }
        nodes[node] = dataSaturate(nodes[node]);
        return node;
    }

    @Override
    public boolean isNodeSaturated(int node) {
        assert isNodeValidOrLeaf(node);
        return isLeaf(node) || dataIsSaturated(nodes[node]);
    }

    // Memory management

    int approximateDeadNodeCount() {
        return approximateDeadNodeCount;
    }

    protected abstract boolean checkLookupChildrenMatch(int lookup);

    protected int findOrCreateNode(int variable, int hashCode) {
        createdNodes += 1;

        int[] nodes = this.nodes;
        int[] hashChain = this.hashChain;

        int lookup = hashToTable(hashCode);
        int currentLookupNode = hashToChainStart[lookup];
        assert currentLookupNode < tableSize() : "Invalid previous entry for " + lookup;

        // Search for the node in the hash chain
        int chainLookups = 1;
        this.hashChainLookups += 1;
        while (currentLookupNode != placeholder()) {
            if ((dataGetVariableUnsafe(nodes[currentLookupNode])) == variable
                    && checkLookupChildrenMatch(currentLookupNode)) {
                this.hashChainLookupLength += chainLookups;
                this.hashChainLookupHit += 1;
                return currentLookupNode;
            }
            int next = hashChain[currentLookupNode];
            assert next != currentLookupNode;
            currentLookupNode = next;
            chainLookups += 1;
        }
        this.hashChainLookupLength += chainLookups;

        // Check we have enough space to add the node
        assert freeNodeCount > 0;
        if (freeNodeCount == 1) {
            // We need a starting point for the free chain node, hence grow if only one node is remaining
            // instead of occupying that node
            // TODO Instead, we should try to clear / grow if freeNodes < load * total size, so that the
            //  hash table is less pressured
            ensureCapacity();
        }

        // Take next free node
        int freeNode = firstFreeNode;
        firstFreeNode = this.hashChain[firstFreeNode];
        freeNodeCount--;
        assert !isNodeValidOrLeaf(freeNode) : "Overwriting existing node " + freeNode;
        assert FIRST_NODE <= firstFreeNode && firstFreeNode < tableSize() : "Invalid free node " + firstFreeNode;

        // Adjust and write node
        this.nodes[freeNode] = dataMake(variable);
        if (biggestValidNode < freeNode) {
            biggestValidNode = freeNode;
        }
        connectHashList(freeNode, hashCode);
        return freeNode;
    }

    /**
     * Perform garbage collection by freeing up dead nodes.
     *
     * @return Number of freed nodes.
     */
    public int forceGc() {
        int freedNodes = doGarbageCollection(0);
        onGarbageCollection();
        return freedNodes;
    }

    /**
     * Tries to free space by garbage collection and, if that does not yield enough free nodes,
     * re-sizes the table, recreating hashes.
     */
    private void ensureCapacity() {
        assert check();

        if (minimumFreeNodeAfterGc < 1.0 && approximateDeadNodeCount > 0) {
            logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes", new Object[] {
                this, tableSize(), approximateDeadNodeCount
            });

            @SuppressWarnings("NumericCastThatLosesPrecision")
            int minimumFreeNodeCount = (int) (tableSize() * minimumFreeNodeAfterGc);
            int clearedNodes = doGarbageCollection(minimumFreeNodeCount);
            if (clearedNodes == -1) {
                logger.log(Level.FINE, "Not enough free nodes");
            } else {
                logger.log(Level.FINE, "Collected {0} nodes", clearedNodes);

                // Force all caches to be wiped out
                // TODO Could do partial invalidation here
                onGarbageCollection();
                return;
            }
        }

        checkState(growthFactor > 0.0);

        growCount += 1;
        int oldSize = tableSize();
        @SuppressWarnings("NumericCastThatLosesPrecision")
        int newSize = Math.min(MAXIMAL_NODE_COUNT, Primes.nextPrime((int) Math.ceil(oldSize * growthFactor)));
        assert oldSize < newSize : "Got new size " + newSize + " with old size " + oldSize;

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, oldSize, newSize});

        // tree = Arrays.copyOf(tree, 2 * newSize);
        onTableResize(newSize);

        nodes = Arrays.copyOf(this.nodes, newSize); // NOPMD
        hashChain = Arrays.copyOf(this.hashChain, newSize); // NOPMD
        // We need to re-build hashToChainStart completely
        hashToChainStart = new int[newSize];
        if (placeholder() != 0) { // Leave this as a reminder
            Arrays.fill(hashToChainStart, NOT_A_NODE);
        }

        // Chain start and next is used in calls to connectHashList so first enlarge and then copy to local reference
        int[] nodes = this.nodes;
        int[] hashToChainStart = this.hashToChainStart;
        int[] hashChain = this.hashChain;

        // Invalidate the new nodes
        Arrays.fill(nodes, oldSize, newSize, dataMakeInvalid());

        int firstFreeNode = oldSize;
        int freeNodeCount = newSize - oldSize;

        // Update the hash references and free nodes chain of the old nodes
        // Reverse direction to build the downward chain towards first free node

        hashChain[newSize - 1] = FIRST_NODE;
        for (int hash = newSize - 2; hash >= oldSize; hash--) {
            hashChain[hash] = hash + 1;
        }
        for (int hash = oldSize - 1; hash >= FIRST_NODE; hash--) {
            int data = nodes[hash];
            if (!dataIsValid(data)) {
                hashChain[hash] = firstFreeNode;
                firstFreeNode = hash;
            }
        }

        // Need a second pass to build the existing nodes chain
        for (int node = oldSize - 1; node >= FIRST_NODE; node--) {
            int data = nodes[node];
            if (dataIsValid(data)) {
                connectHashList(node, hashCode(node, dataGetVariable(data)));
            } else {
                freeNodeCount++;
            }
        }

        this.firstFreeNode = firstFreeNode;
        this.freeNodeCount = freeNodeCount;
        this.hashToChainStart = hashToChainStart;
        this.hashChain = hashChain;

        assert check();

        onGarbageCollection();
        logger.log(Level.FINE, "Finished growing the table");
    }

    private int doGarbageCollection(int minimumFreeNodeCount) {
        assert check();
        assert isNoneMarked();
        long startTimestamp = System.currentTimeMillis();

        int referencedNodes = 0;
        for (int i = 0; i < workStackIndex; i++) {
            int node = workStack[i];
            if (!isLeaf(node) && isNodeValid(node)) {
                referencedNodes += markAllUnmarkedBelow(node);
            }
        }

        int biggestValidNode = this.biggestValidNode;
        int biggestReferencedNode = this.biggestReferencedNode;
        int[] nodes = this.nodes;
        int[] hashChain = this.hashChain;

        for (int i = FIRST_NODE; i <= biggestValidNode; i++) {
            int metadata = nodes[i];
            if (i <= biggestReferencedNode && dataIsReferencedOrSaturated(metadata)) {
                referencedNodes += markAllUnmarkedBelow(i);
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
            int metadata = nodes[node];
            int unmarkedData = dataClearMark(metadata);
            if (metadata == unmarkedData) {
                // This node is unmark and thus unused
                nodes[node] = dataMakeInvalid();
                hashChain[node] = firstFreeNode;
                firstFreeNode = node;
                if (node == biggestValidNode) {
                    biggestValidNode--;
                }
            } else {
                // This node is used
                nodes[node] = unmarkedData;
                connectHashList(node, hashCode(node, dataGetVariable(unmarkedData)));
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

    private void connectHashList(int node, int hashCode) {
        assert isNodeValid(node);
        int position = hashToTable(hashCode);
        int hashChainStart = hashToChainStart[position];
        int[] hashChain = this.hashChain;

        // Search the hash list if this node is already in there in order to avoid loops
        int chainLength = 1;
        int currentChain = hashChainStart;
        this.hashChainLookups += 1;
        while (currentChain != NOT_A_NODE) {
            if (currentChain == node) {
                // The node is already contained in the hash list
                this.hashChainLookupLength += chainLength;
                this.hashChainLookupHit += 1;
                return;
            }
            int next = hashChain[currentChain];
            assert next != currentChain;
            currentChain = next;
            chainLength += 1;
        }
        this.hashChainLookupLength += chainLength;

        hashChain[node] = hashChainStart;
        hashToChainStart[position] = node;
    }

    private int hashToTable(int hashCode) {
        int mod = hashCode % nodes.length;
        return hashCode < 0 ? mod + nodes.length : mod;
    }

    protected abstract int hashCode(int node, int variable);

    protected abstract void onGarbageCollection();

    protected abstract void onTableResize(int newSize);

    // Marking

    private boolean isNodeMarked(int node) {
        assert isNodeValid(node);
        return dataIsMarked(nodes[node]);
    }

    private boolean isNoneMarked() {
        return findFirstMarked() == NOT_A_NODE;
    }

    private boolean isNoneMarkedBelow(int node) {
        return isLeaf(node) || !isNodeMarked(node) && allMatchBelow(node, this::isNoneMarkedBelow);
    }

    private boolean isAllMarkedBelow(int node) {
        return isLeaf(node) || isNodeMarked(node) && allMatchBelow(node, this::isAllMarkedBelow);
    }

    private int findFirstMarked() {
        for (int i = FIRST_NODE; i < nodes.length; i++) {
            if (dataIsMarked(nodes[i])) {
                return i;
            }
        }
        return NOT_A_NODE;
    }

    private int markAllUnmarkedBelow(int node) {
        /* The algorithm does not descend into trees whose root is marked, hence at the start of the
         * algorithm, every marked node must have all of its descendants marked to ensure correctness. */
        assert isNodeValidOrLeaf(node);

        if (isLeaf(node)) {
            return 0;
        }

        int metadata = nodes[node];
        int markedData = dataSetMark(metadata);

        if (metadata == markedData) {
            return 0;
        }
        nodes[node] = markedData;
        return 1 + sumEachBelow(node, this::markAllUnmarkedBelow);
    }

    protected int unMarkAll() {
        /* The algorithm does not descend into trees whose root is unmarked, hence at the start of the
         * algorithm, all children of marked nodes must be marked to ensure correctness. */
        int unmarkedCount = 0;
        int[] nodes = this.nodes;

        for (int i = FIRST_NODE; i <= biggestValidNode; i++) {
            int metadata = nodes[i];
            if (dataIsValid(metadata)) {
                int unmarkedData = dataClearMark(metadata);
                if (metadata != unmarkedData) { // Node was marked
                    unmarkedCount++;
                    nodes[i] = unmarkedData;
                }
            }
        }

        assert isNoneMarked();
        return unmarkedCount;
    }

    protected int unMarkAllMarkedBelow(int node) {
        assert isNodeValidOrLeaf(node);

        if (isLeaf(node)) {
            return 0;
        }

        int metadata = nodes[node];
        int unmarkedData = dataClearMark(metadata);

        if (metadata == unmarkedData) {
            return 0;
        }
        nodes[node] = unmarkedData;
        return 1 + sumEachBelow(node, this::unMarkAllMarkedBelow);
    }

    // Reading

    public int tableSize() {
        return nodes.length;
    }

    /**
     * Counts the number of active nodes in the tree (i.e. the ones which are not invalid),
     * <b>excluding</b> the leaf nodes.
     *
     * @return Number of active nodes.
     */
    @Override
    public int activeNodeCount() {
        // Strategy: We gather all root nodes (i.e. nodes which are referenced) on the mark stack, mark
        // all of their children, count all marked nodes and un-mark them.
        assert isNoneMarked();

        int count = 0;
        for (int node = FIRST_NODE; node < tableSize(); node++) {
            int metadata = nodes[node];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count += markAllUnmarkedBelow(node);
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

        int count = markAllUnmarkedBelow(node);
        if (count > 0) {
            int unmarked = unMarkAllMarkedBelow(node);
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
        return 1 + sumEachBelow(node, this::approximateNodeCount);
    }

    // Traversal

    protected void forEachNodeBelowOnce(int node, NodeVisitor action) {
        assert isNoneMarkedBelow(node);
        doForEachNodeBelowOnce(node, action);
        unMarkAllMarkedBelow(node);
        assert isNoneMarkedBelow(node);
    }

    protected void doForEachNodeBelowOnce(int node, NodeVisitor action) {
        if (isLeaf(node)) {
            return;
        }
        int metadata = nodes[node];
        int markedData = dataSetMark(metadata);
        if (metadata == markedData) {
            return;
        }
        nodes[node] = markedData;
        action.visit(node, dataGetVariable(metadata));
        forEachChild(node, child -> doForEachNodeBelowOnce(child, action));
    }

    @Override
    public BitSet supportFilteredTo(int node, BitSet bitSet, BitSet filter) {
        assert isNodeValidOrLeaf(node);

        int depthLimit = filter.length();
        if (depthLimit == 0) {
            return bitSet;
        }

        forEachNodeBelowOnce(node, (n, var) -> {
            if (filter.get(var)) {
                bitSet.set(var);
            }
        });

        return bitSet;
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
        checkState(dataIsValid(nodes[biggestValidNode]), "Node (%s) is not valid or leaf", string(biggestValidNode));
        for (int i = biggestValidNode + 1; i < tableSize(); i++) {
            checkState(!dataIsValid(nodes[i]), "Node (%s) is valid", string(i));
        }

        // Check biggestReferencedNode variable
        checkState(
                dataIsReferencedOrSaturated(nodes[biggestReferencedNode]),
                "Node (%s) is not referenced",
                node(biggestReferencedNode));
        for (int i = biggestReferencedNode + 1; i < tableSize(); i++) {
            checkState(!dataIsReferencedOrSaturated(nodes[i]), "Node (%s) is referenced", string(i));
        }

        // Check invalid nodes are not referenced
        for (int node = FIRST_NODE; node <= biggestReferencedNode; node++) {
            if (dataIsReferencedOrSaturated(nodes[node])) {
                checkState(dataIsValid(nodes[node]), "Node (%s) is referenced but invalid", string(node));
            }
        }

        // Check if the number of free nodes is correct
        int count = 0;
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            if (dataIsValid(nodes[node])) {
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
            int metadata = nodes[node];
            if (dataIsValid(metadata)) {
                int current = node;
                forEachChild(node, child -> {
                    checkState(
                            isNodeValidOrLeaf(child),
                            "Invalid child entry (%s) -> (%s)",
                            string(current),
                            string(child));
                    if (!isLeaf(child)) {
                        checkState(
                                dataGetVariable(metadata) < dataGetVariable(nodes[child]),
                                "(%s) -> (%s) does not descend tree",
                                string(current),
                                string(child));
                    }
                });
            }
        }

        // Check if there are duplicate nodes
        //noinspection MagicNumber
        int maximalNodeCountCheckedPairs = 1000;
        if (tableSize() < maximalNodeCountCheckedPairs) {
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                if (dataIsValid(nodes[node])) {
                    Node nodeI = node(node);
                    for (int j = node + 1; j < tableSize(); j++) {
                        if (dataIsValid(nodes[j])) {
                            Node nodeJ = node(j);
                            checkState(!Objects.equals(nodeI, nodeJ), "Duplicate entries (%s) and (%s)", nodeI, nodeJ);
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
                    Node nodeObject = node(node);
                    checkState(nodes.add(nodeObject), "Duplicate entry (%s)", nodeObject);
                }
            }
        }

        // Check the integrity of the hash chain
        for (int node = FIRST_NODE; node < tableSize(); node++) {
            int data = nodes[node];
            if (dataIsValid(data)) {
                // Check if each element is in its own hash chain
                int chainPosition = hashToChainStart[hashToTable(hashCode(node, dataGetVariable(data)))];
                boolean found = false;
                StringBuilder hashChain = new StringBuilder(32);
                while (chainPosition != placeholder()) {
                    hashChain.append(' ').append(chainPosition);
                    if (chainPosition == node) {
                        found = true;
                        break;
                    }
                    chainPosition = this.hashChain[chainPosition];
                }
                checkState(found, "(%s) is not contained in it's hash list: %s", string(node), hashChain);
            }
        }

        // Check firstFreeNode
        for (int i = FIRST_NODE; i < firstFreeNode; i++) {
            checkState(dataIsValid(nodes[i]), "Invalid node (%s) smaller than firstFreeNode", string(i));
        }

        // Check free nodes chain
        int currentFreeNode = firstFreeNode;
        do {
            checkState(
                    !dataIsValid(nodes[currentFreeNode]),
                    "Node (%s) in free node chain is valid",
                    string(currentFreeNode));
            int nextFreeNode = hashChain[currentFreeNode];
            // This also excludes possible loops
            checkState(
                    nextFreeNode == FIRST_NODE || currentFreeNode < nextFreeNode,
                    "Free node chain is not well ordered, %s <= %s",
                    nextFreeNode,
                    currentFreeNode);
            checkState(
                    nextFreeNode < nodes.length,
                    "Next free node points over horizon, %s -> %s (%s)",
                    currentFreeNode,
                    nextFreeNode,
                    nodes.length);
            currentFreeNode = nextFreeNode;
        } while (currentFreeNode != FIRST_NODE);

        return true;
    }

    public String getStatistics() {
        int childrenCount = 0;
        int saturatedNodes = 0;
        int referencedNodes = 0;
        int validNodes = 0;

        assert isNoneMarked();

        for (int node = 0; node < tableSize(); node++) {
            int metadata = nodes[node];
            if (dataIsValid(metadata)) {
                validNodes += 1;
                if (dataIsReferencedOrSaturated(metadata)) {
                    referencedNodes += 1;
                    childrenCount += markAllUnmarkedBelow(node);

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
            int metadata = nodes[node];
            if (chainLength[node] > 0) {
                continue;
            }

            if (dataIsValid(metadata)) {
                int chainPosition = hashToChainStart[hashToTable(hashCode(node, dataGetVariable(metadata)))];
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

    public String nodeToString(int node) {
        int metadata = nodes[node];
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
                "%5d|%3d|%s|%s",
                node,
                dataGetVariable(metadata),
                referenceCountString,
                node(node).childrenString());
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
        StringBuilder builder =
                new StringBuilder(50).append("Node ").append(node).append('\n').append("  NODE|VAR|REF|DATA\n");
        forEachNodeBelowOnce(
                node,
                (child, var) -> builder.append(' ').append(nodeToString(child)).append('\n'));
        return builder.toString();
    }

    private Object string(int node) {
        return new Object() {
            @Override
            public String toString() {
                return node(node).toString();
            }
        };
    }

    protected abstract Node node(int node);

    public interface Node {
        String childrenString();
    }

    @FunctionalInterface
    protected interface NodeVisitor {
        void visit(int node, int variable);
    }

    // Tree structure abstraction

    protected abstract void forEachChild(int node, IntConsumer action);

    protected abstract int sumEachBelow(int node, IntUnaryOperator operator);

    protected abstract boolean allMatchBelow(int node, IntPredicate predicate);
}
