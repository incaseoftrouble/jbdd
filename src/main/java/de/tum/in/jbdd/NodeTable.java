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

import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("PMD.TooManyFields")
public abstract class NodeTable {
    private static final Logger logger = Logger.getLogger(NodeTable.class.getName());
    private static final int[] EMPTY_INT_ARRAY = new int[0];
    private static final int[][] EMPTY_INT_ARRAY_ARRAY = new int[0][];

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
    /* Variables the per-variable list is sized for initially; it grows as variables appear. */
    private static final int MINIMUM_VARIABLE_SLOTS = 16;
    private static final int MINIMUM_CHAIN_SLOTS = 8;
    private static final int MAXIMAL_NODE_COUNT = Integer.MAX_VALUE / 2 - 8;
    /* How much a table grows when it cannot be collected far enough; the heap caps it, see nextSize. */
    private static final double GROWTH_FACTOR = 2.0;
    /* Margin applied to the estimated memory requirement of a larger table, see availableMemory(). */
    private static final double MEMORY_SAFETY_FACTOR = 1.25;
    /* Live node ratio accepted before growing when the table cannot be grown within the available memory -
     * a densely packed table which is collected often is still better than not being able to allocate. */
    private static final double MEMORY_PRESSURE_LIVE_NODE_THRESHOLD = 0.9;
    /* The smallest growth worth rehashing the table for, as a fraction of its size: under memory pressure,
     * growing by a few slots would only buy a few allocations before the next full collection. */
    private static final int MINIMUM_GROWTH_DIVISOR = 16;
    /* A table that cannot grow carries on densely packed only while a collection leaves at least this fraction
     * free; below it, every few allocations would cost a full mark, so it gives up instead. */
    private static final int MINIMUM_FREE_DIVISOR = 32;

    static {
        //noinspection ConstantValue
        assert VARIABLE_BIT_SIZE + REFERENCE_COUNT_BIT_SIZE + 1 == Integer.SIZE;
    }

    /* Approximation of dead node count. */
    private int approximateDeadNodeCount = 0;
    /* An upper bound on the referenced nodes: biggestReferencedNode <= biggestValidNode, and a node with a
     * positive reference count has an index of at most biggestReferencedNode. Exact after every sweep, which
     * passes every node anyway, and lax in between: letting go of the topmost referenced node does not search
     * for the next one below, which cost as many slot visits as lay between the two - the whole table, for a
     * fresh result referenced over a few operations and released again. */
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
     * When a node with a certain hash is created, we add a function to the corresponding hash bucket
     * obtainable by hashToChainStart. Whenever we add another node with the same value, this
     * node gets added to the chain and one can traverse the chain by repeatedly accessing
     * hashChain on the chain start. If however a node is invalid, the "next chain
     * entry" points to the next free node. This saves some time when creating nodes, as we don't have
     * to scan through the table to find the next node which we can use.
     */
    private int[] hashToChainStart;
    private int[] hashChain;

    // Reordering structures, required for a level swap. Only maintained once enableReorderingSupport() is called

    /*
     * variableChains / variableChainSize collect the nodes of each variable in a plain array, so a level
     * can be enumerated without scanning the table - sequentially, and counted without walking at all.
     * Nothing ever removes a single node from one: a swap takes a whole list and appends what it produces,
     * and both a collection and a growth rebuild all of them from the table. */
    private boolean reorderBookkeeping = false;
    private int[][] variableChains = EMPTY_INT_ARRAY_ARRAY;
    private int[] variableChainSize = EMPTY_INT_ARRAY;
    /* The buffer the last detach handed out, taken back as the empty replacement for the next one, so a
     * swap neither allocates nor copies. */
    private int[] detachedBuffer = EMPTY_INT_ARRAY;

    /* How many *live* nodes name this one as a child - live, not merely valid: a node nothing can reach
     * still structurally names its children, but it no longer keeps them alive. With it, "is this node
     * still reachable" is an O(1) question instead of a mark from the roots. */
    private int[] parentCount = EMPTY_INT_ARRAY;
    private int deadNodeCount = 0;

    /* Statistics. Counters describing the cost of memory management (marked / swept / rehashed nodes) are
     * the interesting ones to watch: Collected node count alone says nothing about efficiency - a table
     * which is collected far too often collects *more* nodes in total, not fewer. */
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
    /* Collections asked of the JVM to read the available memory exactly, see collectedAvailableMemory. */
    private int jvmGcRequestCount = 0;
    /* The table size at which the JVM was last asked for a collection, -1 if never. */
    private int jvmGcRequestSize = -1;
    /* While the table cannot grow because of memory limits: the free node count at which ensureCapacity
     * next acts, half of what the last collection left, so each full mark is paid for by as many
     * allocations. -1 while the table can grow. */
    private int denseFreeThreshold = -1;
    /* The share of the counters above spent while the variable order was being changed, so reordering's
     * cost can be told apart from the operations'. Taken as differences between a snapshot at the start of
     * each reordering and its end, so nothing on the hot path pays for the distinction. */
    private long reorderCreatedNodes = 0;
    private int reorderGarbageCollectionCount = 0;
    private long reorderGarbageCollectedNodeCount = 0;
    private long reorderGarbageCollectionTime = 0;
    private long @Nullable [] reorderingSnapshot = null;

    /* The work stack is used to store intermediate nodes created by operations. While constructing
     * a new node, e.g. "v1 AND v2", we may need to create multiple intermediate nodes. As
     * during each creation, the node table may run out of space, GC might be called and could
     * delete the intermediately created nodes. Increasing and decreasing the reference counter
     * every time is more expensive than just putting the values on the stack.*/
    private int[] workStack;
    /* Current top of the work stack. */
    private int workStackIndex = 0;
    /* Secondary work stack, for protecting intermediates that must outlive a nested call's own work-stack
     * usage (e.g. a recursion accumulating results across several levels) without forcing every ancestor
     * frame to drop and re-push them just to keep popFromWorkStack's LIFO counting correct. */
    private int[] secondaryWorkStack;
    private int secondaryWorkStackIndex = 0;

    /*
     * Depth of the in-place rewrites currently running - a level swap, in practice. Two things follow.
     * A collection cannot run: the caller holds the nodes it is rewriting in a plain int[], which is no
     * root, so they would be reclaimed underneath it; ensureCapacity therefore grows instead, and the
     * caller needs no reservation up front. And a node flagged for rewriting is out of its hash chain,
     * so findNode cannot hand it out, with the mark bit recording that it is - the only use of the mark
     * bit outside a collection, safe exactly because no collection can run while this is set. A growth
     * in between must leave such a node out of the chains it rebuilds.
     */
    private int rewriteDepth = 0;
    /* How many nodes are flagged right now - O(1), where isNoneMarked() would sweep the whole table on
     * every swap and assertion-enabled runs do thousands of them. */
    private int hiddenForRewriteCount = 0;

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

    abstract int nodeFor(int function);

    protected abstract boolean isLeafNode(int node);

    protected abstract boolean isValidLeafNode(int node);

    public boolean isValidConstant(int function) {
        return isValidLeafNode(nodeFor(function));
    }

    public boolean isValidFunction(int function) {
        return isValidNode(nodeFor(function));
    }

    // Creating nodes

    /**
     * The position of {@code variable} in the owning diagram's variable order. The identity unless that
     * diagram reorders; used only by the ordering assertions, which are about levels, not variable numbers.
     */
    protected abstract int levelOfVariable(int variable);

    protected abstract int positiveHash(int node, int metaData);

    protected int modHash(int hashCode) {
        return hashCode % size();
    }

    /**
     * Links {@code node} into the hash chain of the bucket {@code hash}, which must not contain it already.
     */
    protected void linkHashList(int node, int hash) {
        assert isValidDecisionNode(node);
        // Each node has exactly one chain slot (hashChain[node]), which is shared with the free node
        // list - so a node is either free or in exactly one hash chain, and all three callers (fresh
        // allocation plus the two chain rebuilds, which start from cleared buckets) insert every node
        // exactly once.
        assert !isInHashList(node, hash) : String.format("Node %d already in chain of %d", node, hash);

        hashChain[node] = hashToChainStart[hash];
        hashToChainStart[hash] = node;
    }

    /** Replaces {@code node}'s variable, preserving its reference count and mark. */
    protected final void setVariable(int node, int variable) {
        assert isValidDecisionNode(node) && 0 <= variable && variable < INVALID_NODE_VARIABLE;
        nodeData[node] = (nodeData[node] & ~(-1 << VARIABLE_OFFSET)) | (variable << VARIABLE_OFFSET);
    }

    /**
     * Hands over {@code variable}'s nodes and empties its list. The caller must put each one back with
     * {@link #addToVariableList}, under whichever variable it ends up carrying - which is how a swap moves
     * nodes between two levels.
     *
     * <p>The array is the table's own buffer, not a copy: it holds {@link #nodesWithVariable} entries -
     * ask before detaching - may be longer than that, and stays the caller's only until the next detach.
     */
    protected final int[] detachNodesWithVariable(int variable) {
        enableReorderingBookkeeping();
        if (variable >= variableChainSize.length) {
            return EMPTY_INT_ARRAY;
        }
        int[] detached = variableChains[variable];
        /* Put back the buffer handed out last time rather than a fresh array - but not this one: the
         * caller appends to this very variable while walking what it was given (a node that merely
         * descends a level keeps its variable), so the two must not be the same array. */
        variableChains[variable] = detachedBuffer;
        variableChainSize[variable] = 0;
        detachedBuffer = detached;
        return detached;
    }

    /** How many nodes carry {@code variable}. */
    public final int nodesWithVariable(int variable) {
        enableReorderingBookkeeping();
        return variable < variableChainSize.length ? variableChainSize[variable] : 0;
    }

    public final void beginRewrite() {
        rewriteDepth += 1;
    }

    public final void endRewrite() {
        checkState(rewriteDepth > 0, "Unbalanced rewrite bracket");
        rewriteDepth -= 1;
        assert rewriteDepth > 0 || hiddenForRewriteCount == 0 : "A node is still flagged for rewriting";
    }

    /**
     * Hides {@code node} from {@link #findNode} until the swap rewriting it puts it back, so nothing can
     * be handed a node that still carries its old variable and is about to be rewritten out from under
     * its new parent.
     */
    public final void rewriteHideAndUnlink(int node) {
        assert isValidDecisionNode(node) && rewriteDepth > 0;
        unlinkHashList(node, bucketOf(node));
        boolean hidden = markNodeIfUnmarked(node);
        assert hidden : "Node " + node + " is already flagged";
        hiddenForRewriteCount += 1;
    }

    /** Clears the flag of {@code node}, once its rewrite is done; linking it back is the caller's responsibility. */
    protected final void unhideAfterRewrite(int node) {
        assert rewriteDepth > 0 && dataIsMarked(nodeData[node]);
        nodeData[node] = dataClearMark(nodeData[node]);
        hiddenForRewriteCount -= 1;
    }

    /** The bucket {@code node} is currently linked in - what {@link #unlinkHashList} has to be given. */
    protected final int bucketOf(int node) {
        assert isValidDecisionNode(node);
        return modHash(positiveHash(node, nodeData[node]));
    }

    /**
     * Removes {@code node} from the chain of bucket {@code hash}, which must be the one it is linked in.
     */
    protected void unlinkHashList(int node, int hash) {
        assert isValidDecisionNode(node);
        assert isInHashList(node, hash) : String.format("Node %d not in chain of %d", node, hash);

        // We find the position by forward scan: Empirically, the chains have an average length of 1.x,
        // so this is faster than maintaining a doubly linked list
        int previous = PLACEHOLDER;
        for (int current = hashToChainStart[hash]; current != node; current = hashChain[current]) {
            previous = current;
        }
        if (previous == PLACEHOLDER) {
            hashToChainStart[hash] = hashChain[node];
        } else {
            hashChain[previous] = hashChain[node];
        }
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

    /**
     * Drops the nodes a sweep just invalidated from the per-variable lists, keeping the rest in place.
     */
    private void compactVariableLists() {
        assert reorderBookkeeping;
        for (int variable = 0; variable < variableChainSize.length; variable++) {
            int[] chain = variableChains[variable];
            int size = variableChainSize[variable];
            int kept = 0;
            for (int index = 0; index < size; index++) {
                int node = chain[index];
                if (dataIsValid(nodeData[node])) {
                    chain[kept] = node;
                    kept += 1;
                }
            }
            variableChainSize[variable] = kept;
            /* A level that lost most of its nodes would otherwise keep the array it once grew to - and
             * detachNodesWithVariable passes these buffers between variables, so the widest level's
             * capacity would spread to all of them. Shrink with hysteresis, so a level that refills does
             * not pay for it: only once three quarters of it is empty, and only to twice what is left. */
            if (kept * 4 < chain.length && chain.length > MINIMUM_CHAIN_SLOTS) {
                variableChains[variable] = Arrays.copyOf(chain, Math.max(MINIMUM_CHAIN_SLOTS, kept * 2));
            }
        }
    }

    /** Appends {@code node}, which must already carry {@code variable}, to that variable's list. */
    public final void addToVariableList(int node, int variable) {
        assert reorderBookkeeping && isValidDecisionNode(node) && variable(node) == variable;
        if (variable >= variableChainSize.length) {
            int oldLength = variableChainSize.length;
            int newLength = Math.max(variable + 1, Math.max(oldLength * 2, MINIMUM_VARIABLE_SLOTS));
            variableChains = Arrays.copyOf(variableChains, newLength);
            // Never null, so nothing below has to ask - an empty list and a missing one are the same thing
            Arrays.fill(variableChains, oldLength, newLength, EMPTY_INT_ARRAY);
            variableChainSize = Arrays.copyOf(variableChainSize, newLength);
        }
        int[] chain = variableChains[variable];
        int size = variableChainSize[variable];
        if (size == chain.length) {
            chain = Arrays.copyOf(chain, Math.max(MINIMUM_CHAIN_SLOTS, size * 2));
            variableChains[variable] = chain;
        }
        chain[size] = node;
        variableChainSize[variable] = size + 1;
    }

    final void enableReorderingBookkeeping() {
        if (reorderBookkeeping) {
            return;
        }
        parentCount = new int[size()];
        variableChains = new int[MINIMUM_VARIABLE_SLOTS][];
        Arrays.fill(variableChains, EMPTY_INT_ARRAY);
        variableChainSize = new int[MINIMUM_VARIABLE_SLOTS];
        reorderBookkeeping = true;
        deadNodeCount = computeLiveParentCounts(parentCount);
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                addToVariableList(node, dataGetVariable(data));
            }
        }
        assert !Assertions.COSTLY_ASSERTIONS || check();
    }

    /**
     * Releases the reordering bookkeeping and stops maintaining it.
     */
    public final void dropReorderingBookkeeping() {
        reorderBookkeeping = false;
        variableChains = EMPTY_INT_ARRAY_ARRAY;
        variableChainSize = EMPTY_INT_ARRAY;
        detachedBuffer = EMPTY_INT_ARRAY;
        parentCount = EMPTY_INT_ARRAY;
        deadNodeCount = 0;
    }

    /** Valid nodes, live or not - O(1), unlike {@link #nodeCount()}, which marks from the roots. */
    public final int validNodeCount() {
        return size() - freeNodeCount() - FIRST_NODE;
    }

    /** How much of the table is unreachable - what a reordering pass watches to decide when to collect. */
    public final double deadNodeFraction() {
        assert reorderBookkeeping;
        return Util.ratio(deadNodeCount, validNodeCount());
    }

    @SuppressWarnings("AssertWithSideEffects")
    public final int liveNodeCountFromBookkeeping() {
        assert reorderBookkeeping;
        assert !Assertions.COSTLY_ASSERTIONS || deadNodeCount == computeLiveParentCounts(new int[parentCount.length])
                : "Parent counts drifted";
        int validNodes = size() - freeNodeCount() - FIRST_NODE;
        assert !Assertions.COSTLY_ASSERTIONS || validNodes - deadNodeCount == markedNodeCount()
                : String.format(
                        "Live count %d disagrees with a mark: %d", validNodes - deadNodeCount, markedNodeCount());
        return validNodes - deadNodeCount;
    }

    /**
     * Fills {@code counts} with each node's live-parent count and returns how many valid nodes are dead.
     * A node is live if something outside holds it, or a live node names it - so this walks down from the
     * roots, and only edges leaving a live node are counted.
     */
    private int computeLiveParentCounts(int[] counts) {
        Arrays.fill(counts, 0);
        MutableNatSet live = MutableNatSet.dense(biggestValidNode + 1);
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            int data = nodeData[node];
            if (dataIsValid(data) && dataIsReferencedOrSaturated(data) && !live.contains(node)) {
                live.set(node);
                countChildrenBelow(node, counts, live);
            }
        }

        int dead = 0;
        for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
            if (dataIsValid(nodeData[node]) && !live.contains(node)) {
                dead += 1;
            }
        }
        return dead;
    }

    /** Nothing names this node and nothing outside holds it - so nothing can reach it. */
    boolean isUnreached(int node) {
        assert reorderBookkeeping;
        return parentCount[node] == 0 && !dataIsSaturated(nodeData[node]) && dataGetReferenceCount(nodeData[node]) == 0;
    }

    /**
     * Records that {@code function} gained a parent. If nothing reached it before, it and whatever it
     * reaches come back to life - a walk down the diagram, so it recurses like the rest of them.
     */
    final void addParent(int function) {
        assert reorderBookkeeping;
        int node = nodeFor(function);
        if (isLeafNode(node)) {
            return;
        }
        boolean wasUnreached = isUnreached(node);
        parentCount[node] += 1;
        if (wasUnreached) {
            // Back in reach, so it starts keeping its own children alive again.
            deadNodeCount -= 1;
            forEachChild(node, this::addParent);
        }
    }

    /** Records that {@code function} lost a parent, and with it whatever only it still reached. */
    final void removeParent(int function) {
        assert reorderBookkeeping;
        int node = nodeFor(function);
        if (isLeafNode(node)) {
            return;
        }
        assert parentCount[node] > 0 : String.format("Node %d lost a parent it never had", node);
        parentCount[node] -= 1;
        if (isUnreached(node)) {
            // Out of reach, so it stops keeping its own children alive.
            deadNodeCount += 1;
            forEachChild(node, this::removeParent);
        }
    }

    /* Recursive, like every other walk down a diagram here (markAllBelowNode, recurseNoneMarkedBelow,
     * addParent, removeParent): children are strictly deeper than their parent, so the depth is the
     * number of levels. */
    private void countChildrenBelow(int node, int[] counts, MutableNatSet live) {
        forEachChild(node, child -> {
            int childNode = nodeFor(child);
            if (isLeafNode(childNode)) {
                return;
            }
            counts[childNode] += 1;
            if (!live.contains(childNode)) {
                live.set(childNode);
                countChildrenBelow(childNode, counts, live);
            }
        });
    }

    /**
     * A node was just built. Nothing names it and nothing outside holds it yet, so it is not live and
     * therefore keeps nothing alive either - its children are only credited once something reaches it.
     */
    final void onNodeCreated(int node) {
        if (reorderBookkeeping) {
            parentCount[node] = 0;
            deadNodeCount += 1;
        }
    }

    boolean reorderingBookkeeping() {
        return reorderBookkeeping;
    }

    /**
     * Visits every node currently carrying {@code variable}, in no particular order. Builds the
     * per-variable lists if they are not around, so this is the entry point a reordering pass starts from;
     * {@link #dropReorderingBookkeeping()} releases them again afterwards.
     */
    public void forEachNodeWithVariable(int variable, IntConsumer action) {
        enableReorderingBookkeeping();
        if (variable >= variableChainSize.length) {
            return;
        }
        int[] chain = variableChains[variable];
        int size = variableChainSize[variable];
        for (int index = 0; index < size; index++) {
            int node = chain[index];
            assert isValidDecisionNode(node) && variable(node) == variable;
            action.accept(node);
        }
    }

    protected int findNode(int variable, int hash, IntPredicate lookupComparison) {
        int currentLookupNode = hashToChainStart[hash];
        assert currentLookupNode < size() : "Invalid previous entry for " + hash;

        int chainLookups = 1;
        this.hashChainLookups += 1;
        // Search for the node in the hash chain
        // While rewriting, nodes are unlinked from the hashlist and cannot appear here
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

    void beginReorderingStatistics() {
        assert reorderingSnapshot == null;
        reorderingSnapshot =
                new long[] {createdNodes, garbageCollectionCount, garbageCollectedNodeCount, garbageCollectionTime};
    }

    void endReorderingStatistics() {
        long[] snapshot = reorderingSnapshot;
        assert snapshot != null;
        reorderingSnapshot = null;
        reorderCreatedNodes += createdNodes - snapshot[0];
        reorderGarbageCollectionCount += garbageCollectionCount - (int) snapshot[1];
        reorderGarbageCollectedNodeCount += garbageCollectedNodeCount - snapshot[2];
        reorderGarbageCollectionTime += garbageCollectionTime - snapshot[3];
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
        if (reorderBookkeeping) {
            addToVariableList(freeNode, variable);
        }
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

        if (reorderBookkeeping && isUnreached(node)) {
            // An outside reference reaches it just as a live parent would.
            deadNodeCount -= 1;
            forEachChild(node, this::addParent);
        }
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
        }
        nodeData[node] = dataDecreaseReferenceCount(metadata);
        if (reorderBookkeeping && isUnreached(node)) {
            // The outside let go and nothing names it, so it and its subtree are out of reach.
            deadNodeCount += 1;
            forEachChild(node, this::removeParent);
        }
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
        boolean wasUnreached = reorderBookkeeping && isUnreached(node);
        nodeData[node] = dataSaturate(nodeData[node]);
        if (wasUnreached) {
            deadNodeCount -= 1;
            forEachChild(node, this::addParent);
        }
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
     * Counts the number of active decision nodes in the structure (i.e. the ones which are not invalid),
     * <b>excluding</b> leaves.
     *
     * @return Number of active nodes.
     */
    public int nodeCount() {
        /* Already known when the reordering bookkeeping is around: a node is live exactly when something
         * outside holds it or a live node names it, which is what the mark below computes - liveNodeCount
         * asserts the two agree. */
        if (reorderBookkeeping) {
            return validNodeCount() - deadNodeCount;
        }
        return markedNodeCount();
    }

    private int markedNodeCount() {
        // Strategy: We gather all root nodes (i.e. nodes which are referenced) on the mark stack, mark
        // all of their children, count all marked nodes and un-mark them.
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();

        int count = 0;
        for (int node = FIRST_NODE; node < size(); node++) {
            int metadata = nodeData[node];
            if (dataIsValid(metadata) && dataIsReferencedOrSaturated(metadata)) {
                count += markAllBelowNode(node, true);
            }
        }

        int unmarkedCount = unMarkAll();

        assert count == unmarkedCount;

        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();
        return count;
    }

    /**
     * Counts the number of nodes below the specified {@code node} (including the node itself).
     *
     * @param node The node to be counted.
     * @return The number of non-leaf nodes below {@code node}.
     */
    public int nodeCountBelow(int node) {
        assert isValidNode(node);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();

        // Only decision nodes are tallied, so leaves never need to be marked here at all - unlike a full
        // GC-style mark (markAllBelowNode(node), the default), which also marks managed leaves.
        int count = markAllBelowNode(node, false);
        if (count > 0) {
            int unmarked = unMarkAllBelowNode(node, false);
            assert count == unmarked : "Expected " + count + " but only unmarked " + unmarked;
        }

        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();
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
     * Pushes the given function onto the stack. While a function is on the work stack, it will not be garbage
     * collected. Hence, elements should be popped from the stack as soon as they are not used anymore.
     *
     * @param function The function to be pushed.
     * @return The given {@code function}, to be used for chaining.
     * @see #popFromWorkStack(int)
     */
    int pushToWorkStack(int function) {
        assert isValidFunction(function);
        ensureWorkStackSize(workStackIndex);
        workStack[workStackIndex] = function;
        workStackIndex += 1;
        return function;
    }

    void pushToWorkStack(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        ensureWorkStackSize(workStackIndex + 1);
        workStack[workStackIndex] = function1;
        workStack[workStackIndex + 1] = function2;
        workStackIndex += 2;
    }

    void pushToWorkStack(int function1, int function2, int function3) {
        assert isValidFunction(function1) && isValidFunction(function2) && isValidFunction(function3);
        ensureWorkStackSize(workStackIndex + 2);
        workStack[workStackIndex] = function1;
        workStack[workStackIndex + 1] = function2;
        workStack[workStackIndex + 2] = function3;
        workStackIndex += 3;
    }

    void pushToWorkStack(int function1, int function2, int function3, int function4) {
        assert isValidFunction(function1)
                && isValidFunction(function2)
                && isValidFunction(function3)
                && isValidFunction(function4);
        ensureWorkStackSize(workStackIndex + 3);
        workStack[workStackIndex] = function1;
        workStack[workStackIndex + 1] = function2;
        workStack[workStackIndex + 2] = function3;
        workStack[workStackIndex + 3] = function4;
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
     * Pushes the given function onto the secondary stack. Behaves exactly like {@link #pushToWorkStack(int)},
     * but on an independent stack - so pushing here does not disturb what {@link #popFromWorkStack(int)}
     * considers the top of the primary stack.
     *
     * @param function The function to be pushed.
     * @return The given {@code function}, to be used for chaining.
     * @see #popFromSecondaryWorkStack(int)
     */
    int pushToSecondaryWorkStack(int function) {
        assert isValidFunction(function);
        ensureSecondaryWorkStackSize(secondaryWorkStackIndex);
        secondaryWorkStack[secondaryWorkStackIndex] = function;
        secondaryWorkStackIndex += 1;
        return function;
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
    protected abstract void notifyAfterGc(int reclaimedNodes, NatSet reclaimedValues);

    /** Notifies the owning diagram after the table has grown. */
    protected abstract void notifyAfterTableGrowth(int invalidatedNodes, NatSet reclaimedValues);

    /**
     * Sweeps managed leaves (i.e. MTBDD terminal values) which are neither marked nor referenced, returning
     * the freed values. Clear all marks on managed leaves.
     */
    protected abstract NatSet clearUnreferencedLeaves();

    /**
     * Runs integrity checks of the owning diagram (triggered e.g. after GC)
     */
    protected abstract boolean checkOwner();

    /**
     * Ensures that a node can be allocated by running a garbage collection and / or growing the table.
     *
     * @return Whether the table has grown (and hence hash values have to be recomputed).
     */
    final boolean ensureCapacity() {
        int threshold = denseFreeThreshold >= 0 ? denseFreeThreshold : size() / 4;
        if (freeNodeCount() > threshold) {
            return false;
        }

        NodeTableConfiguration configuration = configuration();
        int currentSize = size();
        int invalidatedNodes = 0;
        NatSet invalidatedLeaves = NatSet.of();
        // The heap is read at most once, and only where it decides between collecting and growing.
        int newSize = -1;
        boolean collected = false;

        /* Growing rather than collecting while a rewrite is running - see rewriteDepth. Growing moves no
         * node; it only has to leave the flagged ones out of the chains, which grow does. */
        if (configuration.useGarbageCollection() && rewriteDepth == 0) {
            // Perform any pre-gc cleanup, e.g. releasing phantom references
            notifyBeforeGc();

            logger.log(Level.FINE, "Attempting GC on {0} of size {1}", new Object[] {this, currentSize});
            // Mark all live nodes to find out whether we want to clean up or grow
            int liveNodes = markAllReferencedNodes();
            invalidatedLeaves = clearUnreferencedLeaves();

            // The fraction of live nodes above which we grow instead of completing the gc:
            // If we have many live nodes, clearing the invalid nodes alone is not worth it
            double liveNodeThreshold = configuration.gcLiveNodeThreshold();
            if (liveNodes > currentSize * liveNodeThreshold) {
                // Too many live nodes, so we want to grow; determine how much we want to grow
                newSize = nextSize(currentSize);
                if (newSize < desiredSize(currentSize)) {
                    // We want more than we can get, so we are under memory pressure
                    liveNodeThreshold = Math.max(liveNodeThreshold, MEMORY_PRESSURE_LIVE_NODE_THRESHOLD);
                }
            }
            @SuppressWarnings("NumericCastThatLosesPrecision")
            int maximumLiveNodes = (int) (currentSize * liveNodeThreshold);
            if (liveNodes <= maximumLiveNodes || newSize <= currentSize) {
                // Either enough dead nodes or we cannot afford growing at all:
                // Reclaim dead nodes and rebuild
                int reclaimedNodes = reclaimUnmarkedNodes();
                logger.log(Level.FINE, "Collected {0} nodes", reclaimedNodes);
                notifyAfterGc(reclaimedNodes, invalidatedLeaves);
                collected = true;
                if (freeNodeCount() > threshold) {
                    // Collected enough, reset the pressure
                    denseFreeThreshold = -1;
                    assert !Assertions.COSTLY_ASSERTIONS || rewriteDepth > 0 || checkOwner();
                    return false;
                }
                // Reachable under memory pressure: We have little room, but collecting did not collect much.
                // So, as a last resort, we try to grow anyway instead of collecting again on the next request.

                // Nodes and leaves are already reported.
                invalidatedLeaves = NatSet.of();
            } else {
                logger.log(Level.FINER, "Not enough free nodes");
                futileGarbageCollectionCount += 1;
                // We don't rebuild the hash chains, but we know which nodes are dead, so use this information
                int[] nodeData = this.nodeData;
                int invalidatedCount = 0;
                approximateDeadNodeCount = 0;
                biggestReferencedNode = PLACEHOLDER;
                sweptNodeCount += biggestValidNode;
                for (int node = biggestValidNode; node >= FIRST_NODE; node--) {
                    int metadata = nodeData[node];
                    int unmarkedData = dataClearMark(metadata);
                    if (metadata == unmarkedData) {
                        // Node was unmarked, invalidate
                        if (node == biggestValidNode) {
                            biggestValidNode--;
                        }
                        invalidatedCount += 1;
                        nodeData[node] = dataMakeInvalid();
                    } else {
                        nodeData[node] = unmarkedData;
                        if (biggestReferencedNode == PLACEHOLDER && dataIsReferencedOrSaturated(unmarkedData)) {
                            biggestReferencedNode = node;
                        }
                    }
                }
                /* Same argument as in reclaimUnmarkedNodes: the nodes dropped here are exactly the ones the parent
                 * counts already call dead, so the survivors keep their counts. */
                if (reorderBookkeeping) {
                    compactVariableLists();
                    deadNodeCount -= invalidatedCount;
                }
                invalidatedNodes = invalidatedCount;
            }
        }

        // We need to grow

        if (newSize < 0) {
            newSize = nextSize(currentSize);
        }
        if (newSize <= currentSize) {
            // Memory pressure: We cannot grow at all.

            int freeNodes = freeNodeCount();
            if (collected && freeNodes < currentSize / MINIMUM_FREE_DIVISOR) {
                // We attempted collection, it did not free enough, and we cannot grow
                throw new OutOfMemoryError(String.format(
                        "Node table %s is full: %d of %d nodes live after collecting, and the heap (%d bytes"
                                + " available) does not allow growing it",
                        this, currentSize - freeNodes, currentSize, availableMemory()));
            }
            // Growing is not possible - carry on with a densely packed table for as long as we can
            checkState(freeNodes > 0, "Node table %s is full and cannot grow", this);

            // Don't go through this whole procedure again, allow the table to grow fuller
            denseFreeThreshold = freeNodes / 2;
            assert !Assertions.COSTLY_ASSERTIONS || rewriteDepth > 0 || checkOwner();
            return false;
        }

        grow(newSize);
        notifyAfterTableGrowth(invalidatedNodes, invalidatedLeaves);
        assert !Assertions.COSTLY_ASSERTIONS || rewriteDepth > 0 || checkOwner();
        return true;
    }

    /** Storage per node slot of the columns this table's shape defines, ignoring the reordering ones. */
    protected abstract int structuralBytesPerSlot();

    /**
     * Storage in bytes each node slot of this table occupies, used to keep growth within the heap: the
     * columns {@link #structuralBytesPerSlot()} names, plus the reordering bookkeeping while it is live.
     */
    private int bytesPerSlot() {
        /* parentCount is as long as the table and is grown with it, so it is part of what a grow has to
         * fit; the per-variable node lists hold only the valid nodes, not the slots. */
        return structuralBytesPerSlot() + (reorderBookkeeping ? Integer.BYTES : 0);
    }

    private double requiredSizeBytes(long size) {
        /* The old arrays stay alive while the new ones are being filled, but they are already accounted for
         * in the used memory - so only the new arrays have to fit. Add a safety margin, since the estimate
         * ignores everything the caller may allocate in the meantime. */
        return size * (double) bytesPerSlot() * MEMORY_SAFETY_FACTOR;
    }

    /**
     * Approximation of the memory still available for allocation. Note that used memory includes garbage
     * which the JVM has not collected yet, so this is an under-approximation.
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

    /**
     * {@link #availableMemory()} after asking the JVM for a collection, at most once per table size. The used memory
     * includes garbage the JVM has not collected yet, so a shortage may not be real; one full JVM collection costs
     * less than collecting a dense table every few allocations, growing it by too little, or giving up needlessly.
     * Once per size, so a heap that is really full does not pay a JVM collection per table collection.
     */
    private long collectedAvailableMemory() {
        int size = size();
        if (jvmGcRequestSize != size) {
            jvmGcRequestSize = size;
            jvmGcRequestCount += 1;
            //noinspection CallToSystemGC
            Runtime.getRuntime().gc(); // NOPMD - the point: an exact reading, see above
        }
        return availableMemory();
    }

    private static long desiredSize(int currentSize) {
        //noinspection NumericCastThatLosesPrecision
        return Math.min(MAXIMAL_NODE_COUNT, (long) (currentSize * GROWTH_FACTOR));
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private int nextSize(int currentSize) {
        long desired = desiredSize(currentSize);
        long available = availableMemory();
        if (available == Long.MAX_VALUE || requiredSizeBytes(desired) <= available) {
            return (int) desired;
        }
        // We currently don't have enough space on the heap, but maybe GC didn't run
        long collected = collectedAvailableMemory();
        if (requiredSizeBytes(desired) <= collected) {
            return (int) desired;
        }

        // Grow by as much as we can still afford, if that is worth a rehash
        memoryLimitedGrowthCount += 1;
        long affordable = Math.min(desired, (long) (collected / (bytesPerSlot() * MEMORY_SAFETY_FACTOR)));
        logger.log(Level.FINE, "Limiting growth of {0} to {1} nodes, {2} bytes available", new Object[] {
            this, affordable, collected
        });
        return affordable < currentSize + currentSize / MINIMUM_GROWTH_DIVISOR ? currentSize : (int) affordable;
    }

    public void grow(int size) {
        int currentSize = size();

        growCount += 1;
        denseFreeThreshold = -1;
        // Clamp before searching for a prime - nextPrime has no upper bound and would overflow
        int newSize = Primes.nextPrime(Math.min(MAXIMAL_NODE_COUNT, size));
        checkState(currentSize < newSize, "Got new size %s with old size %s", newSize, currentSize);

        // Could not free enough space by GC, start growing
        logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}", new Object[] {this, currentSize, newSize});

        nodeData = Arrays.copyOf(this.nodeData, newSize);
        hashChain = Arrays.copyOf(this.hashChain, newSize);
        if (reorderBookkeeping) {
            parentCount = Arrays.copyOf(this.parentCount, newSize);
        }
        growTo(newSize);

        // We need to re-build hashToChainStart completely
        hashToChainStart = new int[newSize];

        assert hashToChainStart[0] == PLACEHOLDER;
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
        // Mid-rewrite, a marked node is one flagged for rewriting and stays out of the chains - see
        // rewriteDepth. Outside, the marks are a collection's, and every valid node is linked.
        boolean rewriting = rewriteDepth > 0;
        int rehashedNodes = 0;
        for (int node = currentSize - 1; node >= FIRST_NODE; node--) {
            int data = nodeData[node];
            if (dataIsValid(data)) {
                if (!rewriting || !dataIsMarked(data)) {
                    rehashedNodes++;
                    linkHashList(node, modHash(positiveHash(node, data)));
                }
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

        /* Nothing is rebuilt here: growing moves no node, so the parent counts (copied above, node
         * indexed) and the per-variable lists (node ids, not slots) are still exactly right. Only the
         * hash chains depend on the size, and they are rebuilt above. The one caller that invalidates
         * nodes first, invalidateUnmarkedNodes, drops the bookkeeping itself. */
        // Mid-rewrite the diagram is deliberately inconsistent - the order is already flipped while the
        // nodes still carry the old variable - so there is nothing for check() to agree with yet.
        assert !Assertions.COSTLY_ASSERTIONS || rewriteDepth > 0 || check();

        logger.log(Level.FINE, "Finished growing the table");
    }

    protected abstract void growTo(int newSize);

    public int reclaimUnmarkedNodes() {
        long startTimestamp = System.currentTimeMillis();

        int biggestValidNode = this.biggestValidNode;
        int[] hashChain = this.hashChain;

        /* Clear the chain starts - they are rebuilt below, in one sequential sweep together with the free
         * list. Note that re-using the existing chains instead (unlinking only the dead nodes) is not
         * worthwhile, even though it looks like less work: a node has exactly one chain slot, shared with
         * the free list, so dead nodes have to be unlinked precisely - and doing so means traversing the
         * chains, which is a random access walk over nodeData and hashChain, whereas the rebuild below
         * streams through the table sequentially. Chain traversal also cannot produce the ascending free
         * list (see check()) which keeps allocation packed at low node indices, and it loses the chain
         * locality that rebuilding restores. */
        Arrays.fill(hashToChainStart, PLACEHOLDER);

        int previousFreeNodes = this.freeNodeCount; // NOPMD
        int firstFreeNode = FIRST_NODE;

        // Connect all definitely invalid nodes in the free node chain
        for (int i = size() - 1; i > biggestValidNode; i--) {
            hashChain[i] = firstFreeNode;
            firstFreeNode = i;
        }

        int[] nodeData = this.nodeData;
        int referencedNodes = 0;
        int biggestReferencedNode = PLACEHOLDER;
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
                if (biggestReferencedNode == PLACEHOLDER && dataIsReferencedOrSaturated(unmarkedData)) {
                    biggestReferencedNode = node;
                }
            }
        }

        this.biggestReferencedNode = biggestReferencedNode;
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

        /* The parent counts are kept, not re-derived. A collection reclaims exactly the nodes nothing
         * reaches, which are precisely the ones the counts already call dead - and a dead node is counted
         * as a parent of nothing, so every survivor keeps the count it had and the dead ones simply stop
         * existing. (A node reachable only from a work stack survives while staying dead by that
         * definition, which is consistent: it stays valid and stays counted.) */
        if (reorderBookkeeping) {
            compactVariableLists();
            deadNodeCount -= collectedNodes;
        }

        assert !Assertions.COSTLY_ASSERTIONS || check();
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();
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
        unmarkAllManagedLeaves();

        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();
        return unmarkedCount;
    }

    protected abstract void unmarkAllManagedLeaves();

    // Tree marking

    protected abstract boolean isLeafNodeMarkedOrUnmanaged(int leaf);

    protected abstract boolean isLeafUnmarkedOrUnmanaged(int leaf);

    public boolean isNoneMarkedBelowNode(int node) {
        assert isValidNode(node);
        return doIsNoneMarkedBelow(node, MutableNatSet.create());
    }

    protected boolean doIsNoneMarkedBelow(int node, MutableNatSet visited) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
            return isLeafUnmarkedOrUnmanaged(node);
        }
        if (visited.contains(node)) {
            return true;
        }
        visited.set(node);
        return !isDecisionNodeMarked(node) && recurseNoneMarkedBelow(node, visited);
    }

    protected abstract boolean recurseNoneMarkedBelow(int node, MutableNatSet visited);

    public boolean isAllMarkedBelowNode(int node) {
        return isAllMarkedBelowNode(node, true);
    }

    public boolean isAllMarkedBelowNode(int node, boolean includeLeaves) {
        assert isValidNode(node);
        return doIsAllMarkedBelow(node, includeLeaves, MutableNatSet.create());
    }

    protected boolean doIsAllMarkedBelow(int node, boolean includeLeaves, MutableNatSet visited) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
            return !includeLeaves || isLeafNodeMarkedOrUnmanaged(node);
        }
        if (visited.contains(node)) {
            return true;
        }
        visited.set(node);
        return isDecisionNodeMarked(node) && recurseIsAllMarkedBelow(node, includeLeaves, visited);
    }

    protected abstract boolean recurseIsAllMarkedBelow(int node, boolean includeLeaves, MutableNatSet visited);

    public int unMarkAllBelowNode(int node, boolean includeLeaves) {
        /* The algorithm does not descend into trees whose root is unmarked, hence at the start of the
         * algorithm, all children of marked nodes must be marked to ensure correctness. */
        assert isValidNode(node);
        assert !Assertions.COSTLY_ASSERTIONS || isAllMarkedBelowNode(node, includeLeaves);
        int unmarkedCount = doSetMarkBelow(node, false, includeLeaves);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarkedBelowNode(node);
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
            int function = workStack[i];
            assert isValidFunction(function);
            referencedNodes += markAllBelowNode(nodeFor(function), true);
        }

        for (int i = 0; i < secondaryWorkStackIndex; i++) {
            int function = secondaryWorkStack[i];
            assert isValidFunction(function);
            referencedNodes += markAllBelowNode(nodeFor(function), true);
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

    public void forEachVariable(int function, IntConsumer action) {
        assert isValidFunction(function);

        int node = nodeFor(function);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, null, Integer.MAX_VALUE);
        // doForEachVariable marks decision nodes only, and not below a cached support (so not unMarkAllBelowNode,
        // which asserts every node below is marked)
        doSetMarkBelow(node, false, false);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarkedBelowNode(node);
    }

    /** The greatest level any variable of {@code variables} sits at, or -1 if there is none. */
    private int maxLevelOf(NatSet variables) {
        int max = -1;
        PrimitiveIterator.OfInt iterator = variables.iterator();
        while (iterator.hasNext()) {
            int variable = iterator.nextInt();
            max = Math.max(max, levelOfVariable(variable));
        }
        return max;
    }

    public void forEachVariable(int function, NatSet filter, IntConsumer action) {
        assert isValidFunction(function);

        int depthLimit = maxLevelOf(filter) + 1;
        if (depthLimit == 0) {
            return;
        }

        int node = nodeFor(function);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarkedBelowNode(node);
        doForEachVariable(node, action, filter, depthLimit);
        // doForEachVariable never marks leaves (see the unfiltered overload above), so don't touch them here.
        doSetMarkBelow(node, false, false);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarkedBelowNode(node);
    }

    protected void doForEachVariable(int node, IntConsumer action, @Nullable NatSet filter, int depthLimit) {
        assert isValidNode(node);
        if (isLeafNode(node)) {
            return;
        }

        int metadata = nodeData[node];
        int variable = dataGetVariable(metadata);
        if (levelOfVariable(variable) >= depthLimit) {
            return;
        }
        int markedData = dataSetMark(metadata);
        if (metadata == markedData) {
            return;
        }
        nodeData[node] = markedData;

        int[] cached = cachedSupport(node);
        if (cached != null) {
            // the node's whole support, its own variable included: nothing below needs a visit
            for (int supportVariable : cached) {
                if (filter == null || filter.contains(supportVariable)) {
                    action.accept(supportVariable);
                }
            }
            return;
        }

        if (filter == null || filter.contains(variable)) {
            action.accept(variable);
        }

        recurseForEachVariable(node, action, filter, depthLimit);
    }

    /** The support of {@code node} if a cache holds it, as ascending variables never to be modified; else null. */
    @SuppressWarnings({"PMD.EmptyMethodInAbstractClassShouldBeAbstract", "PMD.ReturnEmptyCollectionRatherThanNull"})
    // null says no cache holds it, which is what most tables answer; an empty array would say the support is empty
    protected int @Nullable [] cachedSupport(int node) {
        return null;
    }

    protected abstract void recurseForEachVariable(
            int node, IntConsumer action, @Nullable NatSet filter, int depthLimit);

    protected abstract void forEachChild(int node, IntConsumer action);

    // Integrity checks and utility

    public boolean isValidNodeOrPlaceholder(int function) {
        return function == PLACEHOLDER || isValidNode(function);
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
                functionToStringSupplier(biggestValidNode));
        for (int i = biggestValidNode + 1; i < size(); i++) {
            checkState(!dataIsValid(nodeData[i]), "Node (%s) is valid", functionToStringSupplier(i));
        }

        // Check biggestReferencedNode, a bound: the node there is valid, nothing above it is referenced
        checkState(
                biggestReferencedNode == PLACEHOLDER || dataIsValid(nodeData[biggestReferencedNode]),
                "Node (%s) is not valid",
                functionToStringSupplier(biggestReferencedNode));
        for (int i = biggestReferencedNode + 1; i < size(); i++) {
            checkState(
                    !dataIsReferencedOrSaturated(nodeData[i]), "Node (%s) is referenced", functionToStringSupplier(i));
        }

        // Check invalid nodes are not referenced
        for (int node = FIRST_NODE; node <= biggestReferencedNode; node++) {
            if (dataIsReferencedOrSaturated(nodeData[node])) {
                checkState(
                        dataIsValid(nodeData[node]),
                        "Node (%s) is referenced but invalid",
                        functionToStringSupplier(node));
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
                forEachChild(node, child -> {
                    checkState(
                            isValidFunction(child),
                            "Invalid entry (%s) -> (%s)",
                            functionToStringSupplier(node),
                            functionToStringSupplier(child));
                    if (!isValidConstant(child)) {
                        checkState(
                                levelOfVariable(dataGetVariable(metadata))
                                        < levelOfVariable(dataGetVariable(nodeData[nodeFor(child)])),
                                "(%s) -> (%s) does not descend tree",
                                functionToStringSupplier(node),
                                functionToStringSupplier(child));
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
                                    functionToStringSupplier(node),
                                    functionToStringSupplier(j));
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
                    checkState(nodes.add(representative(node)), "Duplicate entry (%s)", functionToStringSupplier(node));
                }
            }
        }

        if (reorderBookkeeping) {
            int[] counted = new int[parentCount.length];
            int expectedDead = computeLiveParentCounts(counted);
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                if (dataIsValid(nodeData[node])) {
                    checkState(
                            counted[node] == parentCount[node],
                            "Node (%s) has %s parents but is counted as %s",
                            functionToStringSupplier(node),
                            counted[node],
                            parentCount[node]);
                }
            }
            checkState(
                    deadNodeCount == expectedDead, "Dead node count is %s, should be %s", deadNodeCount, expectedDead);

            // Every valid node appears exactly once in its variable's list.
            MutableNatSet listed = MutableNatSet.create();
            for (int variable = 0; variable < variableChainSize.length; variable++) {
                int[] chain = variableChains[variable];
                for (int index = 0; index < variableChainSize[variable]; index++) {
                    int node = chain[index];
                    checkState(
                            isValidDecisionNode(node) && variable(node) == variable,
                            "Node (%s) in the list of variable %s",
                            functionToStringSupplier(node),
                            variable);
                    checkState(!listed.contains(node), "Node (%s) listed twice", functionToStringSupplier(node));
                    listed.set(node);
                }
            }
            for (int node = FIRST_NODE; node <= biggestValidNode; node++) {
                checkState(
                        dataIsValid(nodeData[node]) == listed.contains(node),
                        "Node (%s) is %s but %s listed",
                        functionToStringSupplier(node),
                        dataIsValid(nodeData[node]) ? "valid" : "invalid",
                        listed.contains(node) ? "is" : "is not");
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
                        found,
                        "(%s) is not contained in it's hash list: %s",
                        functionToStringSupplier(node),
                        hashChain);
            }
        }

        // Check firstFreeNode
        for (int i = FIRST_NODE; i < firstFreeNode; i++) {
            checkState(
                    dataIsValid(nodeData[i]),
                    "Invalid node (%s) smaller than firstFreeNode",
                    functionToStringSupplier(i));
        }

        // Check free nodes chain
        int currentFreeNode = firstFreeNode;
        do {
            checkState(
                    !dataIsValid(nodeData[currentFreeNode]),
                    "Node (%s) in free node chain is valid",
                    functionToStringSupplier(currentFreeNode));
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

    abstract String format(int function);

    private String functionToString(int function) {
        int node = nodeFor(function);
        int metadata = nodeData[node];
        if (!dataIsValid(metadata)) {
            return String.format("%5d| == INVALID ==", function);
        }
        String referenceCountString;
        if (dataIsSaturated(metadata)) {
            referenceCountString = "SAT";
        } else {
            referenceCountString = String.format("%3d", dataGetReferenceCount(metadata));
        }
        String baseString =
                String.format("%5s|%3d|%s|", format(function), dataGetVariable(metadata), referenceCountString);
        StringBuilder string = new StringBuilder(baseString);
        forEachChild(node, child -> string.append(format(child)).append(' '));
        string.deleteCharAt(string.length() - 1);
        return string.toString();
    }

    private FunctionToStringSupplier functionToStringSupplier(int function) {
        return new FunctionToStringSupplier(this, function);
    }

    /**
     * Generates a string representation of the given {@code function}.
     *
     * @param function The function to be printed.
     * @return A string representing the given function.
     */
    public String treeToString(int function) {
        assert isValidFunction(function);
        assert !Assertions.COSTLY_ASSERTIONS || isNoneMarked();
        if (isValidConstant(function)) {
            return String.format("Fun %s%n", format(function));
        }
        //noinspection MagicNumber
        StringBuilder builder = new StringBuilder(50)
                .append("Fun ")
                .append(format(function))
                .append('\n')
                .append("  NODE|VAR|REF| CHILDREN \n");
        treeToStringRecursive(function, builder);
        unMarkAllBelowNode(nodeFor(function), false);
        return builder.toString();
    }

    private void treeToStringRecursive(int function, StringBuilder builder) {
        if (isValidConstant(function)) {
            return;
        }
        int node = nodeFor(function);
        int metadata = nodeData[node];
        if (dataIsMarked(metadata)) {
            return;
        }
        nodeData[node] = dataSetMark(metadata);
        builder.append(' ').append(functionToString(function)).append('\n');
        forEachChild(node, child -> treeToStringRecursive(child, builder));
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

        int chainLengthSum = 0;
        int maximumChainLength = 0;
        for (int length : chainLength) {
            if (length == 0) {
                continue;
            }
            chainLengthSum += 1;
            if (maximumChainLength < length) {
                maximumChainLength = length;
            }
        }

        return Map.ofEntries(
                entry(prefix + "node_table_size", size()),
                entry(prefix + "biggest_referenced_node", biggestReferencedNode),
                entry(prefix + "created_nodes", createdNodes),
                entry(prefix + "valid_nodes", validNodes),
                entry(prefix + "referenced_nodes", referencedNodes),
                entry(prefix + "saturated_nodes", saturatedNodes),
                entry(prefix + "children_count", childrenCount),
                entry(prefix + "hash_table_load_factor", chainLengthSum * 1.0 / size()),
                entry(prefix + "hash_table_distinct_chains", distinctChains),
                entry(prefix + "hash_table_average_chain_length", Util.ratio(chainLengthSum, distinctChains)),
                entry(prefix + "hash_table_longest_chain", maximumChainLength),
                entry(prefix + "hash_table_lookups", hashChainLookups),
                entry(prefix + "hash_table_lookup_average_length", Util.ratio(hashChainLookupLength, hashChainLookups)),
                entry(prefix + "node_table_gc_count", garbageCollectionCount),
                entry(prefix + "node_table_reorder_created_nodes", reorderCreatedNodes),
                entry(prefix + "node_table_reorder_gc_count", reorderGarbageCollectionCount),
                entry(prefix + "node_table_reorder_gc_collected_nodes", reorderGarbageCollectedNodeCount),
                entry(prefix + "node_table_reorder_gc_time_milliseconds", reorderGarbageCollectionTime),
                entry(prefix + "node_table_gc_time_milliseconds", garbageCollectionTime),
                entry(prefix + "node_table_gc_collected_nodes", garbageCollectedNodeCount),
                entry(prefix + "node_table_grow_count", growCount),
                entry(prefix + "node_table_gc_marked_nodes", markedNodeCount),
                entry(prefix + "node_table_gc_swept_nodes", sweptNodeCount),
                entry(prefix + "node_table_grow_rehashed_nodes", rehashedNodeCount),
                entry(prefix + "node_table_peak_live_nodes", peakLiveNodeCount),
                entry(prefix + "node_table_futile_gc_count", futileGarbageCollectionCount),
                entry(prefix + "node_table_memory_limited_grow_count", memoryLimitedGrowthCount),
                entry(prefix + "node_table_jvm_gc_request_count", jvmGcRequestCount),
                /* The cost of memory management, amortized over the nodes produced, prime indicator
                 * for regressions; rises sharply if the table is collected too often. */
                entry(
                        prefix + "node_table_work_per_created_node",
                        Util.ratio(markedNodeCount + sweptNodeCount + rehashedNodeCount, createdNodes)),
                /* Fraction of each swept table which was actually reclaimed. Complements the above: keeping
                 * the work per created node low by simply growing the table shows up as a low yield. */
                entry(prefix + "node_table_gc_yield", Util.ratio(garbageCollectedNodeCount, sweptNodeCount)),
                /* Table slots held per live node, i.e. the memory paid for the work per created node above.
                 * Note that the live node count is only sampled during mark phases, so this and
                 * node_table_peak_live_nodes are 0 for a table which never collected. */
                entry(prefix + "node_table_slots_per_live_node", Util.ratio(size(), peakLiveNodeCount)));
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
            return table.functionToString(node);
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
        protected void forEachChild(int node, IntConsumer action) {
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
        protected int structuralBytesPerSlot() {
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

        int lowUnchecked(int node) {
            return low[node];
        }

        int highUnchecked(int node) {
            return high[node];
        }

        @Override
        protected int positiveHash(int node, int metadata) {
            assert FIRST_NODE <= node && dataIsValid(metadata);
            return hash(dataGetVariable(metadata), low(node), high(node));
        }

        private static int hash(int variable, int low, int high) {
            /* Deliberately trivial: Benchmarking shows this outperforms other "proper" hash functions.
             * The conjecture is locality - children are allocated near their parent, so a sum keeps a node
             * near its children in the table and a chain walk stays within the same cache lines, which
             * outweighs the better spread of a mixed hash. */
            int hashCode = (low < 0 ? -low : low) + (high < 0 ? -high : high) + variable;
            return hashCode & Integer.MAX_VALUE;
        }

        /**
         * Replaces {@code node}'s variable and children in place and puts it back.
         */
        void rewriteNode(int node, int variable, int lowFunction, int highFunction) {
            assert isValidDecisionNode(node);
            assert lowFunction != highFunction;

            /* Only a node something can reach keeps its children alive, so only then does re-pointing it
             * move credit from the old children to the new. Skipping the pair when a child function did
             * not actually change was measured *slower*: the cascade it would avoid needs the node to
             * be its child's last live parent, which sharing makes rare, so the two tests cost more
             * than they save. */
            boolean live = !isUnreached(node);
            if (live) {
                removeParent(low[node]);
                removeParent(high[node]);
            }
            setVariable(node, variable);
            low[node] = lowFunction;
            high[node] = highFunction;
            if (live) {
                addParent(lowFunction);
                addParent(highFunction);
            }

            unhideAfterRewrite(node);
            int hash = bucketOf(node);
            //noinspection AssertWithSideEffects
            assert findNode(
                                    variable,
                                    hash,
                                    other -> other != node && low[other] == lowFunction && high[other] == highFunction)
                            == PLACEHOLDER
                    : "Rewriting node " + node + " would duplicate an existing one";
            linkHashList(node, hash);
            addToVariableList(node, variable);
        }

        public int makeNode(int variable, int lowFunction, int highFunction) {
            assert 0 <= variable && variable < INVALID_NODE_VARIABLE;
            assert isValidConstant(lowFunction)
                    || levelOfVariable(variable) < levelOfVariable(variable(nodeFor(lowFunction)));
            assert isValidConstant(highFunction)
                    || levelOfVariable(variable) < levelOfVariable(variable(nodeFor(highFunction)));
            assert highFunction != lowFunction;

            int hash = hash(variable, lowFunction, highFunction);

            int modHash = modHash(hash);
            int lookup = findNode(variable, modHash, node -> low[node] == lowFunction && high[node] == highFunction);
            if (lookup != PLACEHOLDER) {
                return lookup;
            }
            if (ensureCapacity()) {
                modHash = modHash(hash);
            }
            int freeNode = allocateNode(variable, modHash);
            this.low[freeNode] = lowFunction;
            this.high[freeNode] = highFunction;
            onNodeCreated(freeNode);
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
        protected void forEachChild(int node, IntConsumer action) {
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
        protected int structuralBytesPerSlot() {
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
                    .allMatch(child -> isValidConstant(child)
                            || levelOfVariable(variable) < levelOfVariable(variable(nodeFor(child))));
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
            onNodeCreated(freeNode);
            return freeNode;
        }

        public int[] children(int node) {
            assert isValidDecisionNode(node);
            return tree[node];
        }

        // Assertion-free reads for the recursion hot paths, as NodeTable.Binary#lowUnchecked.
        int[] childrenUnchecked(int node) {
            return tree[node];
        }

        int followUnchecked(int node, int value) {
            return tree[node][value];
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
