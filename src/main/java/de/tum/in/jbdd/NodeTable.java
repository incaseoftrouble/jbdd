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

import static de.tum.in.jbdd.Preconditions.checkState;
import static java.util.Map.entry;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("PMD.TooManyFields")
public abstract class NodeTable {
    private static final Logger logger = Logger.getLogger(NodeTable.class.getName());

    // Use 0 as "not a node" to make re-allocations slightly more efficient (arrays are always filled with zeroes)
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
    /* Margin applied to the estimated memory requirement of a larger table, see availableMemory(). */
    private static final double MEMORY_SAFETY_FACTOR = 1.25;
    /* Live node ratio accepted before growing when the table cannot be grown within the available memory -
     * a densely packed table which is collected often is still better than not being able to allocate. */
    private static final double MEMORY_PRESSURE_LIVE_NODE_THRESHOLD = 0.9;

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

    /* Stores the metadata for BDD nodes, namely the variable number, reference count and a mask used
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

    /* Statistics. Note that the counters describing the cost of memory management (marked / swept /
     * rehashed nodes) are the interesting ones to watch: The collected node count alone says nothing about
     * efficiency - a table which is collected far too often collects *more* nodes in total, not fewer. */
    private long createdNodes = 0;
    private long hashChainLookups = 0;
    private long hashChainLookupLength = 0;
    private int growCount = 0;
    private int garbageCollectionCount = 0;
    private long garbageCollectedNodeCount = 0;
    private long garbageCollectionTime = 0;
    /* Nodes visited by mark phases, i.e. what collecting costs. */
    private long markedNodeCount = 0;
    /* Node slots visited by sweeps, i.e. what reclaiming / invalidating costs. */
    private long sweptNodeCount = 0;
    /* Nodes re-inserted into the hash chains while growing, i.e. what growing costs. */
    private long rehashedNodeCount = 0;
    /* Largest number of live nodes observed during a mark phase. */
    private int peakLiveNodeCount = 0;
    /* Collections whose mark phase was discarded because the table had to be grown anyway. */
    private int futileGarbageCollectionCount = 0;
    /* Growths which were limited (possibly to nothing) by the available memory. */
    private int memoryLimitedGrowthCount = 0;

    /* The work stack is used to store intermediate nodes created by operations. While constructing
     * a new node, e.g. "v1 AND v2", we may need to create multiple intermediate nodes. As
     * during each creation, the node table may run out of space, GC might be called and could
     * delete the intermediately created nodes. Increasing and decreasing the reference counter
     * every time is more expensive than just putting the values on the stack, thus we use this data
     *  */
    private int[] workStack;
    /* Current top of the work stack. */
    private int workStackIndex = 0;
    /* Secondary work stack, for protecting intermediates that must outlive a nested call's own work-stack
     * usage (e.g. a recursion accumulating results across several levels) without forcing every ancestor
     * frame to drop and re-push them just to keep popFromWorkStack's LIFO counting correct. */
    private int[] secondaryWorkStack;
    private int secondaryWorkStackIndex = 0;

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
        secondaryWorkStack = new int[32];
    }

    // Structure

    public final int variable(int node) {
        assert isValidDecisionNode(node);
        return dataGetVariable(nodeData[node]);
    }

    public final int size() {
        return nodeData.length;
    }

    public int freeNodeCount() {
        return freeNodeCount;
    }

    abstract int treeNodeFor(int pointer);

    protected abstract boolean isLeafNode(int node);

    protected abstract boolean isValidLeafNode(int node);

    public boolean isValidConstant(int pointer) {
        return isValidLeafNode(treeNodeFor(pointer));
    }

    public boolean isValidPointer(int pointer) {
        return isValidNode(treeNodeFor(pointer));
    }

    // Creating nodes

    protected abstract int positiveHash(int node, int metaData);

    protected int modHash(int hashCode) {
        return hashCode % size();
    }

    /**
     * Links {@code node} into the hash chain of the bucket {@code hash}, which must not contain it already.
     *
     * <p>Each node has exactly one chain slot ({@code hashChain[node]}), which is shared with the free node
     * list - so a node is either free or in exactly one hash chain, and all three callers (fresh allocation
     * plus the two chain rebuilds, which start from cleared buckets) insert every node exactly once.
     * Scanning the chain for duplicates would therefore only cost an additional random-access walk per
     * inserted node in the rebuild paths, hence it is done in an assertion instead.
     */
    protected void linkHashList(int node, int hash) {
        assert isValidDecisionNode(node);
        assert !isInHashList(node, hash) : "Node " + node + " already in chain of " + hash;

        hashChain[node] = hashToChainStart[hash];
        hashToChainStart[hash] = node;
    }

    private boolean isInHashList(int node, int hash) {
        int currentChain = hashToChainStart[hash];
        while (currentChain != PLACEHOLDER) {
            assert isValidDecisionNode(currentChain);
            if (currentChain == node) {
                return true;
            }
            int next = hashChain[currentChain];
            assert next != currentChain;
            currentChain = next;
        }
        return false;
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
        // Take the next free node
        assert freeNodeCount > 0;
        createdNodes += 1;
        int freeNode = firstFreeNode;
        firstFreeNode = this.hashChain[freeNode];
        freeNodeCount--;
        assert !isValidDecisionNode(freeNode) : "Overwriting existing node " + freeNode;
        assert FIRST_NODE <= firstFreeNode && firstFreeNode < size() : "Invalid free node " + firstFreeNode;

        // Adjust and write node
        this.nodeData[freeNode] = variable << VARIABLE_OFFSET;
        if (biggestValidNode < freeNode) {
            biggestValidNode = freeNode;
        }
        linkHashList(freeNode, modHash);
        return freeNode;
    }

    // Reference counting

    public int nodeReferenceCount(int node) {
        assert isValidDecisionNode(node);
        return dataGetReferenceCountOrSaturated(nodeData[node]);
    }

    public void referenceNode(int node) {
        assert isValidDecisionNode(node);
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
        assert isValidDecisionNode(node);
        int metadata = nodeData[node];
        int referenceCount = dataGetReferenceCountUnsafe(metadata);
        if (referenceCount == REFERENCE_COUNT_SATURATED) {
            return;
        }
        assert referenceCount > 0;
        if (referenceCount == 1) {
            // After decrease, it is 0

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
        assert isValidDecisionNode(node);
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
        assert isValidNode(node);
        return dataIsSaturated(nodeData[node]);
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
                count += markAllBelowNode(node, true);
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
        assert isValidNode(node);
        assert isNoneMarked();

        // Only decision nodes are tallied, so leafs never need to be marked here at all - unlike a full
        // GC-style mark (markAllBelowNode(node), the default), which also marks managed leafs.
        int count = markAllBelowNode(node, false);
        if (count > 0) {
            int unmarked = unMarkAllBelowNode(node, false);
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

    boolean workStacksEmpty() {
        return workStackIndex == 0 && secondaryWorkStackIndex == 0;
    }

    /**
     * Removes the topmost element from the stack.
     *
     * @see #pushToWorkStack(int)
     */
    void popFromWorkStack() {
        assert !workStacksEmpty();
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

    void pushToWorkStack(int pointer1, int pointer2, int pointer3, int pointer4) {
        assert isValidPointer(pointer1)
                && isValidPointer(pointer2)
                && isValidPointer(pointer3)
                && isValidPointer(pointer4);
        ensureWorkStackSize(workStackIndex + 3);
        workStack[workStackIndex] = pointer1;
        workStack[workStackIndex + 1] = pointer2;
        workStack[workStackIndex + 2] = pointer3;
        workStack[workStackIndex + 3] = pointer4;
        workStackIndex += 4;
    }

    // Secondary work stack

    private void ensureSecondaryWorkStackSize(int size) {
        if (size < secondaryWorkStack.length) {
            return;
        }
        int newSize = secondaryWorkStack.length * 2;
        secondaryWorkStack = Arrays.copyOf(secondaryWorkStack, newSize);
    }

    /**
     * Removes the {@code amount} topmost elements from the secondary stack.
     *
     * @see #pushToSecondaryWorkStack(int)
     */
    void popFromSecondaryWorkStack(int amount) {
        assert secondaryWorkStackIndex >= amount;
        secondaryWorkStackIndex -= amount;
    }

    /**
     * Pushes the given pointer onto the secondary stack. Behaves exactly like {@link #pushToWorkStack(int)},
     * but on an independent stack - so pushing here does not disturb what {@link #popFromWorkStack(int)}
     * considers the top of the primary stack.
     *
     * @param pointer The pointer to be pushed.
     * @return The given {@code pointer}, to be used for chaining.
     * @see #popFromSecondaryWorkStack(int)
     */
    int pushToSecondaryWorkStack(int pointer) {
        assert isValidPointer(pointer);
        ensureSecondaryWorkStackSize(secondaryWorkStackIndex);
        secondaryWorkStack[secondaryWorkStackIndex] = pointer;
        secondaryWorkStackIndex += 1;
        return pointer;
    }

    // Memory management

    public int approximateDeadNodeCount() {
        return approximateDeadNodeCount;
    }

    /** Configuration of the diagram owning this table. */
    protected abstract NodeTableConfiguration configuration();

    /** Notifies the owning diagram right before a mark phase, see {@code NodeLifecycleObserver#beforeGc}. */
    protected abstract void notifyBeforeGc();

    /** Notifies the owning diagram after nodes have been reclaimed. */
    protected abstract void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues);

    /** Notifies the owning diagram after the table has grown. */
    protected abstract void notifyAfterTableGrowth(int invalidatedNodes, BitSet reclaimedValues);

    /**
     * Sweeps managed leaves (i.e. MTBDD terminal values) which are neither marked nor referenced, returning
     * the freed values. Runs as part of the mark phase, i.e. <b>before</b> {@link #reclaimUnmarkedNodes()},
     * whose closing {@code assert isNoneMarked()} also covers leaf marks.
     */
    protected BitSet sweepManagedLeaves() {
        return BitSets.of();
    }

    /** Runs the owning diagram's integrity check, see {@link #check()}. Only called from assertions. */
    protected boolean checkOwner() {
        return true;
    }

    /**
     * Ensures that a node can be allocated, by running a garbage collection and / or growing the table.
     *
     * @return Whether the table has grown (and hence hash values have to be recomputed).
     */
    final boolean ensureCapacity() {
        if (freeNodeCount() > size() / 4) {
            return false;
        }

        NodeTableConfiguration configuration = configuration();
        int currentSize = size();
        int invalidatedNodes = 0;
        BitSet invalidatedLeaves = BitSets.of();

        if (configuration.useGarbageCollection()) {
            // Perform any pre-gc cleanup, e.g. releasing phantom references
            notifyBeforeGc();

            logger.log(Level.FINE, "Running GC on {0} of size {1}", new Object[] {this, currentSize});

            // Leaves all live nodes marked
            int liveNodes = markAllReferencedNodes();
            invalidatedLeaves = sweepManagedLeaves();

            @SuppressWarnings("NumericCastThatLosesPrecision")
            int maximumLiveNodes = (int) (currentSize * liveNodeThreshold(configuration));
            if (liveNodes <= maximumLiveNodes) {
                int reclaimedNodes = reclaimUnmarkedNodes();
                logger.log(Level.FINE, "Collected {0} nodes", reclaimedNodes);
                notifyAfterGc(reclaimedNodes, invalidatedLeaves);
                if (freeNodeCount() > size() / 4) {
                    assert checkOwner();
                    return false;
                }
                /* Only reachable under memory pressure (see liveNodeThreshold): The collection did not even
                 * lift the table above the threshold which triggered it, so try to grow anyway instead of
                 * collecting again on the very next allocation. Nodes and leaves are already reported. */
                invalidatedLeaves = BitSets.of();
            } else {
                logger.log(Level.FINER, "Not enough free nodes");
                futileGarbageCollectionCount += 1;
                /* Drop the dead nodes anyway - they are of no use in the grown table either and rebuilding
                 * their hash chain entries is the most expensive part of growing. */
                invalidatedNodes = invalidateUnmarkedNodes();
            }
        }

        int newSize = nextSize(currentSize, configuration);
        if (newSize <= currentSize) {
            // Growing is not possible - carry on with a densely packed table for as long as we can
            checkState(freeNodeCount() > 0, "Node table %s is full and cannot grow", this);
            assert checkOwner();
            return false;
        }

        grow(newSize);
        notifyAfterTableGrowth(invalidatedNodes, invalidatedLeaves);
        assert checkOwner();
        return true;
    }

    /**
     * The fraction of the table which may be live for a garbage collection to be worth it (as opposed to
     * growing the table).
     *
     * <p>Reclaiming has to leave enough headroom to be worth its cost: A collection is only triggered at
     * 25% free nodes and costs a full mark (which is a random access traversal of all live nodes) plus a
     * sweep of the whole table. Allowing, say, 70% live nodes means that the next collection is due after
     * allocating a mere 5% of the table's size, i.e. tens of node visits amortized per created node.
     *
     * <p>Under memory pressure we accept exactly that instead of failing to allocate the larger table.
     */
    private double liveNodeThreshold(NodeTableConfiguration configuration) {
        double threshold = configuration.gcLiveNodeThreshold();
        if (threshold >= MEMORY_PRESSURE_LIVE_NODE_THRESHOLD) {
            return threshold;
        }
        long required = (long) requiredSizeBytes(desiredSize(size(), configuration));
        return required <= availableMemory() ? threshold : MEMORY_PRESSURE_LIVE_NODE_THRESHOLD;
    }

    /** Storage in bytes each node slot of this table occupies, used to keep growth within the heap. */
    protected abstract int bytesPerSlot();

    private double requiredSizeBytes(long size) {
        /* The old arrays stay alive while the new ones are being filled, but they are already accounted for
         * in the used memory - so only the new arrays have to fit. Add a safety margin, since the estimate
         * ignores everything the caller may allocate in the meantime. */
        return size * (double) bytesPerSlot() * MEMORY_SAFETY_FACTOR;
    }

    /**
     * Approximation of the memory still available for allocation. Note that used memory includes garbage
     * which the JVM has not collected yet, so this is an under-approximation - which is the safe direction,
     * as it biases towards reclaiming instead of growing.
     */
    private static long availableMemory() {
        Runtime runtime = Runtime.getRuntime();
        long maximum = runtime.maxMemory();
        if (maximum == Long.MAX_VALUE) {
            // No bound configured
            return Long.MAX_VALUE;
        }
        return maximum - (runtime.totalMemory() - runtime.freeMemory());
    }

    private static long desiredSize(int currentSize, NodeTableConfiguration configuration) {
        return Math.min(MAXIMAL_NODE_COUNT, (long) (currentSize * configuration.growthFactor()));
    }

    /**
     * The size the table should grow to, bounded by {@link #MAXIMAL_NODE_COUNT} and the memory available for
     * the new arrays.
     *
     * @return The new size, or {@code currentSize} if the table cannot grow (at all) right now.
     */
    private int nextSize(int currentSize, NodeTableConfiguration configuration) {
        long desired = desiredSize(currentSize, configuration);
        long available = availableMemory();

        long size;
        if (available == Long.MAX_VALUE || requiredSizeBytes(desired) <= available) {
            size = desired;
        } else {
            // Grow by as much as we can still afford
            size = Math.min(desired, (long) (available / (bytesPerSlot() * MEMORY_SAFETY_FACTOR)));
            memoryLimitedGrowthCount += 1;
            logger.log(Level.FINE, "Limiting growth of {0} to {1} nodes, {2} bytes available", new Object[] {
                this, size, available
            });
        }
        return size <= currentSize ? currentSize : (int) size;
    }

    public void grow(int size) {
        int currentSize = size();

        growCount += 1;
        // Clamp before searching for a prime - nextPrime has no upper bound and would overflow
        int newSize = Primes.nextPrime(Math.min(MAXIMAL_NODE_COUNT, size));
        checkState(currentSize < newSize, "Got new size %s with old size %s", newSize, currentSize);

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, currentSize, newSize});

        nodeData = Arrays.copyOf(this.nodeData, newSize);
        hashChain = Arrays.copyOf(this.hashChain, newSize);
        growTo(newSize);

        // We need to re-build hashToChainStart completely
        hashToChainStart = new int[newSize];

        //noinspection ConstantValue
        assert PLACEHOLDER == 0;
        // Otherwise: Arrays.fill(hashToChainStart, NOT_A_NODE);

        // Chain start and next is used in calls to connectHashList so first enlarge and then copy to local reference
        int[] nodeData = this.nodeData;
        int[] hashToChainStart = this.hashToChainStart; // NOPMD
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
        int rehashedNodes = 0;
        for (int node = currentSize - 1; node >= FIRST_NODE; node--) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                rehashedNodes++;
                linkHashList(node, modHash(positiveHash(node, data)));
            } else {
                freeNodeCount++;
            }
        }
        this.rehashedNodeCount += rehashedNodes;

        this.firstFreeNode = firstFreeNode;
        this.freeNodeCount = freeNodeCount;
        this.nodeData = nodeData;
        this.hashToChainStart = hashToChainStart;
        this.hashChain = hashChain;

        assert check();

        logger.log(Level.FINE, "Finished growing the table");
    }

    protected abstract void growTo(int newSize);

    /**
     * Invalidates all unmarked nodes, clearing all marks. This deliberately does <b>not</b> fix up the free
     * list or the hash chains and hence is only valid immediately before {@link #grow(int)}, which rebuilds
     * both.
     *
     * @return The number of invalidated nodes.
     */
    public int invalidateUnmarkedNodes() {
        int[] nodeData = this.nodeData;
        int count = 0;
        // All dead nodes are removed by this, just as by reclaimUnmarkedNodes
        approximateDeadNodeCount = 0;
        sweptNodeCount += biggestValidNode;
        for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
            int metadata = nodeData[node];
            int unmarkedData = dataClearMark(metadata);
            if (metadata == unmarkedData) {
                // Node was unmarked, invalidate
                if (node == biggestValidNode) {
                    biggestValidNode--;
                }
                count += 1;
                nodeData[node] = dataMakeInvalid();
            } else {
                nodeData[node] = unmarkedData;
            }
        }
        return count;
    }

    public int reclaimUnmarkedNodes() {
        long startTimestamp = System.currentTimeMillis();

        int biggestValidNode = this.biggestValidNode;
        int[] nodeData = this.nodeData;
        int[] hashChain = this.hashChain;

        /* Clear the chain starts - they are rebuilt below, in one sequential sweep together with the free
         * list. Note that re-using the existing chains instead (unlinking only the dead nodes) is not
         * worthwhile, even though it looks like less work: a node has exactly one chain slot, shared with
         * the free list, so dead nodes have to be unlinked precisely - and doing so means traversing the
         * chains, which is a random access walk over nodeData and hashChain, whereas the rebuild below
         * streams through the table sequentially. Chain traversal also cannot produce the ascending free
         * list (see check()) which keeps allocation packed at low node indices, and it loses the chain
         * locality that rebuilding restores. The only variant that beats the rebuild - repairing just those
         * buckets which contain a dead node - pays off exclusively when very few nodes died, which
         * ensureCapacity() now avoids by growing instead of collecting. */
        Arrays.fill(hashToChainStart, PLACEHOLDER);

        int previousFreeNodes = this.freeNodeCount; // NOPMD
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
                linkHashList(node, modHash(positiveHash(node, unmarkedData)));
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
        // The whole table is swept: the chain starts are cleared and every node slot is visited
        this.sweptNodeCount += size();

        assert check();
        assert isNoneMarked();
        return collectedNodes;
    }

    // Marking

    public boolean isDecisionNodeMarked(int node) {
        assert isValidDecisionNode(node);
        return dataIsMarked(nodeData[node]);
    }

    protected boolean markNodeIfUnmarked(int node) {
        assert isValidDecisionNode(node);
        int metadata = nodeData[node];
        int markedData = dataSetMark(metadata);
        if (metadata == markedData) {
            return false;
        }
        nodeData[node] = markedData;
        return true;
    }

    public int findFirstMarkedDecisionNode() {
        for (int i = FIRST_NODE; i < nodeData.length; i++) {
            if (dataIsMarked(nodeData[i])) {
                return i;
            }
        }
        return PLACEHOLDER;
    }

    public boolean isNoneMarked() {
        return findFirstMarkedDecisionNode() == PLACEHOLDER && !anyManagedLeafMarked();
    }

    protected abstract boolean anyManagedLeafMarked();

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
        unmarkAllManagedLeafs();

        assert isNoneMarked();
        return unmarkedCount;
    }

    protected abstract void unmarkAllManagedLeafs();

    // Tree marking

    protected abstract boolean isLeafNodeMarkedOrUnmanaged(int leaf);

    protected abstract boolean isLeafUnmarkedOrUnmanaged(int leaf);

    public boolean isNoneMarkedBelowNode(int node) {
        assert isValidNode(node);
        return doIsNoneMarkedBelow(node);
    }

    protected boolean doIsNoneMarkedBelow(int node) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
            return isLeafUnmarkedOrUnmanaged(node);
        }
        return !isDecisionNodeMarked(node) && recurseNoneMarkedBelow(node);
    }

    protected abstract boolean recurseNoneMarkedBelow(int node);

    public boolean isAllMarkedBelowNode(int node) {
        return isAllMarkedBelowNode(node, true);
    }

    public boolean isAllMarkedBelowNode(int node, boolean includeLeafs) {
        assert isValidNode(node);
        return doIsAllMarkedBelow(node, includeLeafs);
    }

    protected boolean doIsAllMarkedBelow(int node, boolean includeLeafs) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
            return !includeLeafs || isLeafNodeMarkedOrUnmanaged(node);
        }
        return isDecisionNodeMarked(node) && recurseIsAllMarkedBelow(node, includeLeafs);
    }

    protected abstract boolean recurseIsAllMarkedBelow(int node, boolean includeLeafs);

    public int unMarkAllBelowNode(int node, boolean includeLeaves) {
        /* The algorithm does not descend into trees whose root is unmarked, hence at the start of the
         * algorithm, all children of marked nodes must be marked to ensure correctness. */
        assert isValidNode(node) && isAllMarkedBelowNode(node, includeLeaves);
        int unmarkedCount = doSetMarkBelow(node, false, includeLeaves);
        assert isNoneMarkedBelowNode(node);
        return unmarkedCount;
    }

    public int markAllBelowNode(int node, boolean includeLeaves) {
        /* The algorithm does not descend into trees whose root is marked, hence at the start of the
         * algorithm, every marked node must have all of its descendants marked to ensure correctness. */
        assert isValidNode(node);
        return doSetMarkBelow(node, true, includeLeaves);
    }

    protected abstract void markLeafNodeIfManaged(int node, boolean mark);

    protected int doSetMarkBelow(int node, boolean mark, boolean includeLeaves) {
        assert isValidNode(node);

        if (isLeafNode(node)) {
            if (includeLeaves) {
                markLeafNodeIfManaged(node, mark);
            }
            return 0;
        }

        int metadata = nodeData[node];
        int modifiedData = dataSetMark(metadata, mark);
        if (metadata == modifiedData) {
            return 0;
        }
        nodeData[node] = modifiedData;
        return 1 + recurseSetMarkBelow(node, mark, includeLeaves);
    }

    protected abstract int recurseSetMarkBelow(int node, boolean mark, boolean includeLeaves);

    public int markAllReferencedNodes() {
        int referencedNodes = 0;

        for (int i = 0; i < workStackIndex; i++) {
            int pointer = workStack[i];
            assert isValidPointer(pointer);
            referencedNodes += markAllBelowNode(treeNodeFor(pointer), true);
        }

        for (int i = 0; i < secondaryWorkStackIndex; i++) {
            int pointer = secondaryWorkStack[i];
            assert isValidPointer(pointer);
            referencedNodes += markAllBelowNode(treeNodeFor(pointer), true);
        }

        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            int metadata = nodeData[node];
            if (node <= biggestReferencedNode && dataIsReferencedOrSaturated(metadata)) {
                referencedNodes += markAllBelowNode(node, true);
            }
        }

        markedNodeCount += referencedNodes;
        if (peakLiveNodeCount < referencedNodes) {
            peakLiveNodeCount = referencedNodes;
        }
        return referencedNodes;
    }

    // Structural properties

    public void forEachVariable(int pointer, IntConsumer action) {
        assert isValidPointer(pointer);

        int node = treeNodeFor(pointer);
        assert isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, null, Integer.MAX_VALUE);
        // doForEachVariable marks decision nodes, so do not consider leaves
        unMarkAllBelowNode(node, false);
        assert isNoneMarkedBelowNode(node);
    }

    public void forEachVariable(int pointer, BitSet filter, IntConsumer action) {
        assert isValidPointer(pointer);

        int depthLimit = filter.length();
        if (depthLimit == 0) {
            return;
        }

        int node = treeNodeFor(pointer);
        assert isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, filter, depthLimit);
        // doForEachVariable never marks leaves (see the unfiltered overload above), so don't touch them here.
        doSetMarkBelow(node, false, false);
        assert isNoneMarkedBelowNode(node);
    }

    protected void doForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
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

    public boolean isValidNodeOrPlaceholder(int pointer) {
        return pointer == PLACEHOLDER || isValidNode(pointer);
    }

    public boolean isValidDecisionNode(int node) {
        return FIRST_NODE <= node && node <= biggestValidNode && dataIsValid(nodeData[node]);
    }

    public boolean isValidNode(int node) {
        return isValidLeafNode(node) || isValidDecisionNode(node);
    }

    /**
     * Performs some integrity / invariant checks.
     *
     * @return True. This way, check can easily be called by an {@code assert} statement.
     */
    boolean check() {
        logger.log(Level.FINER, "Running integrity check");
        checkState(biggestReferencedNode <= biggestValidNode);

        // Check the biggestValidNode variable. PLACEHOLDER means "no valid node at all" (a fresh table);
        // slot 0 itself is never a node, so it stays invalid and must not be checked for validity.
        checkState(
                biggestValidNode == PLACEHOLDER || dataIsValid(nodeData[biggestValidNode]),
                "Node (%s) is not valid or leaf",
                pointerToStringSupplier(biggestValidNode));
        for (int i = biggestValidNode + 1; i < size(); i++) {
            checkState(!dataIsValid(nodeData[i]), "Node (%s) is valid", pointerToStringSupplier(i));
        }

        // Check biggestReferencedNode variable (PLACEHOLDER means "nothing is referenced", see above)
        checkState(
                biggestReferencedNode == PLACEHOLDER || dataIsReferencedOrSaturated(nodeData[biggestReferencedNode]),
                "Node (%s) is not referenced",
                pointerToStringSupplier(biggestReferencedNode));
        for (int i = biggestReferencedNode + 1; i < size(); i++) {
            checkState(
                    !dataIsReferencedOrSaturated(nodeData[i]), "Node (%s) is referenced", pointerToStringSupplier(i));
        }

        // Check invalid nodes are not referenced
        for (int node = FIRST_NODE; node <= biggestReferencedNode; node++) {
            if (dataIsReferencedOrSaturated(nodeData[node])) {
                checkState(
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
        checkState(
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
                    checkState(
                            isValidPointer(child),
                            "Invalid entry (%s) -> (%s)",
                            pointerToStringSupplier(node),
                            pointerToStringSupplier(child));
                    if (!isValidConstant(child)) {
                        checkState(
                                dataGetVariable(metadata) < dataGetVariable(nodeData[treeNodeFor(child)]),
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
                            checkState(
                                    dataGetVariable(dataI) != dataGetVariable(dataJ) || !areChildrenEqual(node, j),
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
                if (isValidDecisionNode(node)) {
                    checkState(nodes.add(representative(node)), "Duplicate entry (%s)", pointerToStringSupplier(node));
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
                checkState(
                        found, "(%s) is not contained in it's hash list: %s", pointerToStringSupplier(node), hashChain);
            }
        }

        // Check firstFreeNode
        for (int i = FIRST_NODE; i < firstFreeNode; i++) {
            checkState(
                    dataIsValid(nodeData[i]),
                    "Invalid node (%s) smaller than firstFreeNode",
                    pointerToStringSupplier(i));
        }

        // Check free nodes chain
        int currentFreeNode = firstFreeNode;
        do {
            checkState(
                    !dataIsValid(nodeData[currentFreeNode]),
                    "Node (%s) in free node chain is valid",
                    pointerToStringSupplier(currentFreeNode));
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

    protected abstract boolean areChildrenEqual(int node1, int node2);

    protected abstract Object representative(int node);

    // Printing

    abstract String format(int pointer);

    private String pointerToString(int pointer) {
        int node = treeNodeFor(pointer);
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
        if (isValidConstant(pointer)) {
            return String.format("Fun %s%n", format(pointer));
        }
        //noinspection MagicNumber
        StringBuilder builder = new StringBuilder(50)
                .append("Fun ")
                .append(format(pointer))
                .append('\n')
                .append("  NODE|VAR|REF| CHILDREN \n");
        treeToStringRecursive(pointer, builder);
        unMarkAllBelowNode(treeNodeFor(pointer), false);
        return builder.toString();
    }

    private void treeToStringRecursive(int pointer, StringBuilder builder) {
        if (isValidConstant(pointer)) {
            return;
        }
        int node = treeNodeFor(pointer);
        int metadata = nodeData[node];
        if (dataIsMarked(metadata)) {
            return;
        }
        nodeData[node] = dataSetMark(metadata);
        builder.append(' ').append(pointerToString(pointer)).append('\n');
        forEachChildPointer(node, child -> treeToStringRecursive(child, builder));
    }

    // Statistics

    public long createdNodeCount() {
        return createdNodes;
    }

    public Map<String, Object> statistics(String prefix) {
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
                    childrenCount += markAllBelowNode(node, true);

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

        int chainLenghtSum = 0;
        int maximumChainLength = 0;
        for (int length : chainLength) {
            if (length == 0) {
                continue;
            }
            chainLenghtSum += 1;
            if (maximumChainLength < length) {
                maximumChainLength = length;
            }
        }

        return Map.ofEntries(
                entry(prefix + "node_table_size", String.valueOf(size())),
                entry(prefix + "biggest_referenced_node", String.valueOf(biggestReferencedNode)),
                entry(prefix + "created_nodes", String.valueOf(createdNodes)),
                entry(prefix + "valid_nodes", String.valueOf(validNodes)),
                entry(prefix + "referenced_nodes", String.valueOf(referencedNodes)),
                entry(prefix + "saturated_nodes", String.valueOf(saturatedNodes)),
                entry(prefix + "children_count", String.valueOf(childrenCount)),
                entry(prefix + "hash_table_load_factor", String.valueOf(chainLenghtSum * 1.0 / size())),
                entry(prefix + "hash_table_distinct_chains", String.valueOf(distinctChains)),
                entry(
                        prefix + "hash_table_average_chain_length",
                        String.valueOf(chainLenghtSum * 1.0 / distinctChains)),
                entry(prefix + "hash_table_longest_chain", String.valueOf(maximumChainLength)),
                entry(prefix + "hash_table_lookups", String.valueOf(hashChainLookups)),
                entry(
                        prefix + "hash_table_lookup_average_length",
                        String.valueOf(hashChainLookupLength * 1.0 / hashChainLookups)),
                entry(prefix + "node_table_gc_count", String.valueOf(garbageCollectionCount)),
                entry(prefix + "node_table_gc_time_milliseconds", String.valueOf(garbageCollectionTime)),
                entry(prefix + "node_table_gc_collected_nodes", String.valueOf(garbageCollectedNodeCount)),
                entry(prefix + "node_table_grow_count", String.valueOf(growCount)),
                entry(prefix + "node_table_gc_marked_nodes", String.valueOf(markedNodeCount)),
                entry(prefix + "node_table_gc_swept_nodes", String.valueOf(sweptNodeCount)),
                entry(prefix + "node_table_grow_rehashed_nodes", String.valueOf(rehashedNodeCount)),
                entry(prefix + "node_table_peak_live_nodes", String.valueOf(peakLiveNodeCount)),
                entry(prefix + "node_table_futile_gc_count", String.valueOf(futileGarbageCollectionCount)),
                entry(prefix + "node_table_memory_limited_grow_count", String.valueOf(memoryLimitedGrowthCount)),
                /* The cost of memory management, amortized over the nodes it produced - the number to watch
                 * for regressions. Rises sharply if the table is collected too often (see
                 * NodeTableConfiguration#gcLiveNodeThreshold), which the collected node count does not show:
                 * a table collected twice as often collects more nodes in total, not fewer. */
                entry(
                        prefix + "node_table_work_per_created_node",
                        String.valueOf(ratio(markedNodeCount + sweptNodeCount + rehashedNodeCount, createdNodes))),
                /* Fraction of each swept table which was actually reclaimed. Complements the above: keeping
                 * the work per created node low by simply growing the table shows up as a low yield. */
                entry(prefix + "node_table_gc_yield", String.valueOf(ratio(garbageCollectedNodeCount, sweptNodeCount))),
                /* Table slots held per live node, i.e. the memory paid for the work per created node above.
                 * Note that the live node count is only sampled during mark phases, so this and
                 * node_table_peak_live_nodes are 0 for a table which never collected. */
                entry(prefix + "node_table_slots_per_live_node", String.valueOf(ratio(size(), peakLiveNodeCount))));
    }

    private static double ratio(long value, long total) {
        return total == 0 ? 0.0 : value / (double) total;
    }

    private static final class FunctionToStringSupplier {
        private final int node;
        private final NodeTable table;

        FunctionToStringSupplier(NodeTable table, int node) {
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

    public abstract static class Binary extends NodeTable {
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
            assert isValidDecisionNode(node);
            action.accept(low[node]);
            action.accept(high[node]);
        }

        @Override
        protected boolean areChildrenEqual(int node1, int node2) {
            return low(node1) == low(node2) && high(node1) == high(node2);
        }

        @Override
        protected Object representative(int node) {
            return new BinaryNode(variable(node), low(node), high(node));
        }

        @Override
        protected int bytesPerSlot() {
            // nodeData, hashChain, hashToChainStart, low, high
            return 5 * Integer.BYTES;
        }

        public int low(int node) {
            assert isValidDecisionNode(node);
            return low[node];
        }

        public int high(int node) {
            assert isValidDecisionNode(node);
            return high[node];
        }

        @Override
        protected int positiveHash(int node, int metadata) {
            assert FIRST_NODE <= node && dataIsValid(metadata);
            return hash(dataGetVariable(metadata), low(node), high(node));
        }

        private static int hash(int variable, int low, int high) {
            // TODO This silly "hash function" seems to be significantly better than any "proper" one - why?
            //   Conjecture: This leads to "locality" in the hash chain?
            int hashCode = (low < 0 ? -low : low) + (high < 0 ? -high : high) + variable;
            return hashCode & Integer.MAX_VALUE;
        }

        public int makeNode(int variable, int lowPointer, int highPointer) {
            assert 0 <= variable && variable < INVALID_NODE_VARIABLE;
            assert isValidConstant(lowPointer) || variable < variable(treeNodeFor(lowPointer));
            assert isValidConstant(highPointer) || variable < variable(treeNodeFor(highPointer));
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

    public abstract static class Multi extends NodeTable {
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
            assert isValidDecisionNode(node);
            for (int child : tree[node]) {
                action.accept(child);
            }
        }

        @Override
        protected boolean areChildrenEqual(int node1, int node2) {
            return Arrays.equals(tree[node1], tree[node2]);
        }

        @Override
        protected Object representative(int node) {
            return new MultiNode(variable(node), tree[node]);
        }

        @Override
        protected int bytesPerSlot() {
            /* nodeData, hashChain, hashToChainStart and the spine of tree - the children arrays themselves
             * are not re-allocated when growing, so they are not counted here. */
            return 3 * Integer.BYTES + Long.BYTES;
        }

        public int follow(int node, int value) {
            assert isValidDecisionNode(node);
            return tree[node][value];
        }

        @Override
        protected int positiveHash(int node, int metadata) {
            assert FIRST_NODE <= node && dataIsValid(metadata);
            return hash(dataGetVariable(metadata), tree[node]);
        }

        private static int hash(int variable, int[] children) {
            int hashCode = variable;
            for (int child : children) {
                hashCode += child < 0 ? -child : child;
            }
            return hashCode & Integer.MAX_VALUE;
        }

        public int makeNode(int variable, int[] children) {
            assert 0 <= variable;
            assert Arrays.stream(children)
                    .allMatch(child -> isValidConstant(child) || variable < variable(treeNodeFor(child)));
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
            assert isValidDecisionNode(node);
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
