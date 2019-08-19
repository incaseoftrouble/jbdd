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

import static de.tum.in.jbdd.BitUtil.fits;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO Recursive methods should be delegated downwards to allow for iterative implementation

/* Implementation notes:
 * - Many asserts in the long store accessor functions are commented out, as the JVM might not
 *   inline the methods when they are too big. */
@SuppressWarnings({"WeakerAccess", "unused",
                      "NumericCastThatLosesPrecision", "ValueOfIncrementOrDecrementUsed",
                      "PMD.TooManyFields"})
class NodeTable {
  /*
   * The maximal supported bit size of the node identifier. Internally, we store the tree structure
   * (variable, low and high) inside longs, which means that we have a special upper bound for each
   * of those values. 25 bits allows us to have 33.554.432 different nodes, which should be more
   * than enough.
   */
  public static final int NODE_IDENTIFIER_BIT_SIZE = 25;
  /* Bits allocated for the hash list references */
  private static final int CHAIN_REFERENCE_BIT_SIZE = NODE_IDENTIFIER_BIT_SIZE;
  private static final int INITIAL_STACK_SIZE = 32;
  private static final long LIST_REFERENCE_MASK = BitUtil.maskLength(CHAIN_REFERENCE_BIT_SIZE);
  /* The _OFFSET variables are used to indicate the bit offset of each value,
   * the _MASK variables are the masks used to filter the values _after_ shifting them to the front
   */
  private static final int LOW_OFFSET = 1;
  /* Bits allocated for the reference counter */
  private static final int REFERENCE_COUNT_BIT_SIZE = 13;
  private static final long MAXIMAL_REFERENCE_COUNT = (1L << REFERENCE_COUNT_BIT_SIZE) - 1L;
  private static final long REFERENCE_COUNT_MASK = BitUtil.maskLength(REFERENCE_COUNT_BIT_SIZE);
  private static final int REFERENCE_COUNT_OFFSET = 1;
  private static final int CHAIN_NEXT_OFFSET = REFERENCE_COUNT_BIT_SIZE + REFERENCE_COUNT_OFFSET;
  private static final int CHAIN_START_OFFSET =
      REFERENCE_COUNT_BIT_SIZE + REFERENCE_COUNT_OFFSET + CHAIN_REFERENCE_BIT_SIZE;
  /* Bits allocated for the high and low numbers */
  private static final int TREE_REFERENCE_BIT_SIZE = NODE_IDENTIFIER_BIT_SIZE;
  private static final int HIGH_OFFSET = TREE_REFERENCE_BIT_SIZE + 1;
  private static final long TREE_REFERENCE_MASK = BitUtil.maskLength(TREE_REFERENCE_BIT_SIZE);
  /* Bits allocated for the variable number */
  private static final int VARIABLE_BIT_SIZE = 13;
  /* Mask used to indicate invalid nodes */
  private static final long INVALID_NODE_VALUE = BitUtil.maskLength(VARIABLE_BIT_SIZE);
  private static final int VARIABLE_OFFSET = 2 * TREE_REFERENCE_BIT_SIZE + 1;
  private static final int MINIMUM_NODE_TABLE_SIZE = 1_000;
  private static final Logger logger = Logger.getLogger(NodeTable.class.getName());

  static {
    //noinspection ConstantConditions
    assert VARIABLE_BIT_SIZE + 2 * TREE_REFERENCE_BIT_SIZE + 1 <= Long.SIZE :
        "Bit sizes don't match";
    //noinspection ConstantConditions
    assert 2 * CHAIN_REFERENCE_BIT_SIZE <= Long.SIZE : "Bit sizes don't match";
  }

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
  /* The internals stack is used by, e.g., the marking algorithm to keep track of nodes which need
   * to be explored. The stack is kept as a field to avoid repeated allocation. */
  private int[] internalStack;
  /* The nodeStorage contains the actual BDD nodes, where each long is used to store the variable,
   * low, high and a mask used by various internal algorithms. These values are manipulated through
   * various static helper functions. Layout: <---VAR---><---HIGH--><---LOW---><MASK> */
  private long[] nodeStorage;
  /* The referenceStorage contains multiple values used to ensure uniqueness of nodes and keeping
   * track of reference counts. Aside from the straightforward reference counting, the array is used
   * as a hash map for existing nodes and a linked list for free nodes. The semantics of the "next
   * chain entry" change, depending on whether the node is valid or not.
   *
   * When a node with a certain hash is created, we add a pointer to the corresponding hash bucket
   * obtainable by getChainStartFromStore. Whenever we add another node with the same value, this
   * node gets added to the chain and one can traverse the chain by repeatedly calling
   * getNextChainEntryFromStore on the chain start. If however a node is invalid, the "next chain
   * entry" points to the next free node. This saves some time when creating nodes, as we don't have
   * to scan through our BDD to find the next node which we can update a value.
   *
   * Layout: <---START--><---NEXT--><---REF---><SATURATED> */
  private long[] referenceStorage;
  /* This is just the number of variables. As currently, BDD and NodeTable are separate, both need
   * to keep track of this value. While not entirely necessary, we can use this value to ensure
   * that our markStack is big enough for a full recursive marking. */
  private int treeSize = 0;
  /* The work stack is used to store intermediate nodes created by some BDD operations. While
   * constructing a new BDD, e.g. v1 and v2, we may need to create multiple intermediate BDDs. As
   * during each creation the node table may run out of space, GC might be called and could
   * delete the intermediately created nodes. As increasing and decreasing the reference counter
   * every time is more expensive than just putting the values on the stack, we use this data
   * structure. */
  private int[] workStack;
  /* Current top of the work stack. */
  private int workStackIndex = 0;

  // Statistics
  private long createdNodes = 0;
  private long hashChainLookups = 0;
  private long hashChainLookupLength = 0;
  private long growCount = 0;
  private long garbageCollectionCount = 0;
  private long garbageCollectedNodeCount = 0;

  NodeTable(int nodeSize, BddConfiguration configuration) {
    this.configuration = configuration;
    int initialSize = Math.max(nodeSize, MINIMUM_NODE_TABLE_SIZE);
    nodeStorage = new long[initialSize];
    referenceStorage = new long[initialSize];

    firstFreeNode = 2;
    freeNodeCount = initialSize - 2;
    biggestReferencedNode = 1;
    biggestValidNode = 1;

    Arrays.fill(nodeStorage, 2, initialSize, invalidStore());
    for (int i = 0; i < initialSize - 1; i++) {
      referenceStorage[i] = startNoneAndNextStore(i + 1);
    }
    referenceStorage[initialSize - 1] = startNoneAndNextStore(0);

    nodeStorage[0] = buildNodeStore(INVALID_NODE_VALUE, 0L, 0L);
    nodeStorage[1] = buildNodeStore(INVALID_NODE_VALUE, 1L, 1L);
    referenceStorage[0] = saturateStore(referenceStorage[0]);
    referenceStorage[1] = saturateStore(referenceStorage[1]);

    workStack = new int[INITIAL_STACK_SIZE];
    internalStack = new int[INITIAL_STACK_SIZE];
  }

  private static long buildNodeStore(long variable, long low, long high) {
    assert fits(variable, VARIABLE_BIT_SIZE)
        && fits(low, TREE_REFERENCE_BIT_SIZE)
        && fits(high, TREE_REFERENCE_BIT_SIZE) : "Bit size exceeded";
    long store = 0L;
    store |= low << LOW_OFFSET;
    store |= high << HIGH_OFFSET;
    store |= variable << VARIABLE_OFFSET;
    return store;
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

  private static long clearChainStartInStore(long referenceStore) {
    return referenceStore & ~(LIST_REFERENCE_MASK << CHAIN_START_OFFSET);
  }

  private static long decreaseReferenceCountInStore(long referenceStore) {
    assert !isStoreSaturated(referenceStore);
    return referenceStore - 2L;
  }

  private static long getChainStartFromStore(long referenceStore) {
    return (referenceStore >>> CHAIN_START_OFFSET) & LIST_REFERENCE_MASK;
  }

  static long getHighFromStore(long nodeStore) {
    return (nodeStore >>> HIGH_OFFSET) & TREE_REFERENCE_MASK;
  }

  static long getLowFromStore(long nodeStore) {
    return (nodeStore >>> LOW_OFFSET) & TREE_REFERENCE_MASK;
  }

  private static long getMarkFromStore(long nodeStore) {
    return nodeStore & 1L;
  }

  private static int getMarkStackSizeForTreeSize(int treeSize) {
    return treeSize + 3;
  }

  private static long getNextChainEntryFromStore(long referenceStore) {
    return (referenceStore >>> CHAIN_NEXT_OFFSET) & LIST_REFERENCE_MASK;
  }

  private static long getReferenceCountFromStore(long referenceStore) {
    assert !isStoreSaturated(referenceStore);
    return (referenceStore >>> 1) & REFERENCE_COUNT_MASK;
  }

  static long getVariableFromStore(long nodeStore) {
    return nodeStore >>> VARIABLE_OFFSET;
  }

  private static long increaseReferenceCountInStore(long referenceStore) {
    assert !isStoreSaturated(referenceStore);
    return referenceStore + 2L;
  }

  private static long invalidStore() {
    return INVALID_NODE_VALUE << VARIABLE_OFFSET;
  }

  static boolean isNodeStoreMarked(long nodeStore) {
    return getMarkFromStore(nodeStore) != 0L;
  }

  private static boolean isReferencedOrSaturatedNodeStore(long referenceStore) {
    return isStoreSaturated(referenceStore) || getReferenceCountFromStore(referenceStore) > 0L;
  }

  private static boolean isStoreSaturated(long referenceStore) {
    return (referenceStore & 1L) != 0L;
  }

  private static boolean isValidNodeStore(long nodeStore) {
    return (nodeStore >>> VARIABLE_OFFSET) != INVALID_NODE_VALUE;
  }

  private static boolean nodeStoresEqual(long nodeStore, long otherNodeStore) {
    // See if the two nodes are equal. As the layout is <VAL><HI><LO><MASK>, value, high and low
    // are equal if the two differ at most on the mask.
    return (nodeStore >>> 1) == (otherNodeStore >>> 1);
  }

  private static long saturateStore(long referenceStore) {
    return referenceStore | 1L;
  }

  @SuppressWarnings("PMD.LinguisticNaming")
  private static long setChainStartInStore(long referenceStore, long chainStart) {
    assert 0L <= chainStart && fits(chainStart, CHAIN_REFERENCE_BIT_SIZE);
    return (referenceStore & ~(LIST_REFERENCE_MASK << CHAIN_START_OFFSET))
        | chainStart << CHAIN_START_OFFSET;
  }

  @SuppressWarnings("PMD.LinguisticNaming")
  private static long setMarkInStore(long nodeStore) {
    assert isValidNodeStore(nodeStore);
    return nodeStore | 1L;
  }

  @SuppressWarnings("PMD.LinguisticNaming")
  private static long setNextChainEntryInStore(long referenceStore, long next) {
    assert 0L <= next && fits(next, CHAIN_REFERENCE_BIT_SIZE);
    return (referenceStore & ~(LIST_REFERENCE_MASK << CHAIN_NEXT_OFFSET))
        | next << CHAIN_NEXT_OFFSET;
  }

  private static long startNoneAndNextStore(int next) {
    assert 0 <= next && fits(next, CHAIN_REFERENCE_BIT_SIZE);
    return ((long) next) << CHAIN_NEXT_OFFSET;
  }

  private static long unsetMarkInStore(long nodeStore) {
    assert isValidNodeStore(nodeStore);
    return nodeStore & ~1L;
  }

  /**
   * Over-approximates the number of nodes below the specified {@code node}, possibly counting
   * shared sub-trees multiple times. Guaranteed to be bigger or equal to {@link #nodeCount(int)}.
   *
   * @param node
   *     The node to be counted.
   *
   * @return An approximate number of non-root nodes below {@code node}.
   *
   * @see #nodeCount(int)
   */
  public final int approximateNodeCount(int node) {
    assert isNodeValidOrRoot(node);
    if (isNodeRoot(node)) {
      return 0;
    }
    long bddStore = nodeStorage[node];
    return 1 + approximateNodeCount((int) getLowFromStore(bddStore)) + approximateNodeCount(
        (int) getHighFromStore(bddStore));
  }

  /**
   * Performs some integrity / invariant checks.
   *
   * @return True. This way, check can easily be called by an {@code assert} statement.
   */
  @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
  final boolean check() {
    logger.log(Level.FINER, "Running integrity check");
    checkState(biggestReferencedNode <= biggestValidNode);

    // Check the biggestValidNode variable
    checkState(biggestValidNode < 2 || isValidNodeStore(nodeStorage[biggestValidNode]),
        "Node (%s) is not valid or root", nodeToStringSupplier(biggestValidNode));
    for (int i = biggestValidNode + 1; i < getTableSize(); i++) {
      checkState(!isValidNodeStore(nodeStorage[i]), "Node (%s) is valid", nodeToStringSupplier(i));
    }

    // Check biggestReferencedNode variable
    checkState(isReferencedOrSaturatedNodeStore(referenceStorage[biggestReferencedNode]),
        "Node (%s) is not referenced", nodeToStringSupplier(biggestReferencedNode));
    for (int i = biggestReferencedNode + 1; i < getTableSize(); i++) {
      checkState(!isReferencedOrSaturatedNodeStore(referenceStorage[i]), "Node (%s) is referenced",
          nodeToStringSupplier(i));
    }

    // Check invalid nodes are not referenced
    for (int i = 2; i <= biggestReferencedNode; i++) {
      if (isReferencedOrSaturatedNodeStore(referenceStorage[i])) {
        checkState(isValidNodeStore(nodeStorage[i]), "Node (%s) is referenced but invalid",
            nodeToStringSupplier(i));
      }
    }

    // Check if the number of free nodes is correct
    int count = 2;
    for (int i = 2; i <= biggestValidNode; i++) {
      if (isValidNodeStore(nodeStorage[i])) {
        count++;
      }
    }
    checkState(count == (getTableSize() - freeNodeCount),
        "Invalid # of free nodes: #live=%s, size=%s, free=%s", count, getTableSize(),
        freeNodeCount);

    // Check each node's children
    for (int i = 2; i <= biggestValidNode; i++) {
      long nodeStore = nodeStorage[i];
      if (isValidNodeStore(nodeStore)) {
        int low = (int) getLowFromStore(nodeStore);
        int high = (int) getHighFromStore(nodeStore);
        checkState(isNodeValidOrRoot(low), "Invalid low entry (%s) -> (%s)",
            nodeToStringSupplier(i), nodeToStringSupplier(low));
        checkState(isNodeValidOrRoot(high), "Invalid high entry (%s) -> (%s)",
            nodeToStringSupplier(i), nodeToStringSupplier(high));
        if (!isNodeRoot(low)) {
          checkState(getVariableFromStore(nodeStore) < getVariableFromStore(nodeStorage[low]),
              "(%s) -> (%s) does not descend tree", nodeToStringSupplier(i),
              nodeToStringSupplier(low));
        }
        if (!isNodeRoot(high)) {
          checkState(getVariableFromStore(nodeStore) < getVariableFromStore(nodeStorage[high]),
              "(%s) -> (%s) does not descend tree", nodeToStringSupplier(i),
              nodeToStringSupplier(high));
        }
      }
    }

    // Check if there are duplicate nodes
    //noinspection MagicNumber
    int maximalNodeCountCheckedPairs = 1000;
    if (getTableSize() < maximalNodeCountCheckedPairs) {
      for (int i = 2; i <= biggestValidNode; i++) {
        long storeOfI = nodeStorage[i];
        if (isValidNodeStore(storeOfI)) {
          for (int j = i + 1; j < getTableSize(); j++) {
            long storeOfJ = nodeStorage[j];
            if (isValidNodeStore(storeOfJ)) {
              checkState(!nodeStoresEqual(storeOfI, storeOfJ), "Duplicate entries (%s) and (%s)",
                  nodeToStringSupplier(i), nodeToStringSupplier(j));
            }
          }
        }
      }
    }

    int maximalNodeCountCheckedSet = configuration.integrityDuplicatesMaximalSize();
    if (maximalNodeCountCheckedSet == -1 || getTableSize() < maximalNodeCountCheckedSet) {
      logger.log(Level.FINER, "Checking duplicate nodes");
      Set<Long> nodes = new HashSet<>();
      for (int node = 2; node <= biggestValidNode; node++) {
        long nodeStore = nodeStorage[node];
        if (isValidNodeStore(nodeStore)) {
          long prunedStore = nodeStore & ~1L;
          checkState(nodes.add(nodeStore), "Duplicate entry (%s)", nodeToStringSupplier(node));
        }
      }
    }

    // Check the integrity of the hash chain
    for (int i = 2; i < getTableSize(); i++) {
      long nodeStore = nodeStorage[i];
      if (isValidNodeStore(nodeStore)) {
        // Check if each element is in its own hash chain
        int chainPosition =
            (int) getChainStartFromStore(referenceStorage[hashNodeStore(nodeStore)]);
        boolean found = false;
        //noinspection MagicNumber
        StringBuilder hashChain = new StringBuilder(32);
        while (chainPosition != 0) {
          hashChain.append(' ').append(chainPosition);
          if (chainPosition == i) {
            found = true;
            break;
          }
          chainPosition = (int) getNextChainEntryFromStore(referenceStorage[chainPosition]);
        }
        checkState(found, "(%s) is not contained in it's hash list: %s", nodeToStringSupplier(i),
            hashChain);
      }
    }

    // Check firstFreeNode
    for (int i = 2; i < firstFreeNode; i++) {
      checkState(isValidNodeStore(nodeStorage[i]), "Invalid node (%s) smaller than firstFreeNode",
          nodeToStringSupplier(i));
    }

    // Check free nodes chain
    int currentFreeNode = firstFreeNode;
    do {
      checkState(!isValidNodeStore(nodeStorage[currentFreeNode]),
          "Node (%s) in free node chain is valid", nodeToStringSupplier(currentFreeNode));
      int nextFreeNode = (int) getNextChainEntryFromStore(referenceStorage[currentFreeNode]);
      // This also excludes possible loops
      checkState(nextFreeNode == 0 || currentFreeNode < nextFreeNode,
          "Free node chain is not well ordered, %s <= %s", nextFreeNode, currentFreeNode);
      checkState(nextFreeNode < nodeStorage.length,
          "Next free node points over horizon, %s -> %s (%s)", currentFreeNode, nextFreeNode,
          nodeStorage.length);
      currentFreeNode = nextFreeNode;
    } while (currentFreeNode != 0);

    return true;
  }

  private void connectHashList(int node, int hash) {
    assert isNodeValid(node) && 0 <= hash && hash == hashNodeStore(nodeStorage[node]);
    long hashReferenceStore = referenceStorage[hash];
    int hashChainStart = (int) getChainStartFromStore(hashReferenceStore);

    // Search the hash list if this node is already in there in order to avoid loops
    int chainLength = 1;
    int currentChainIndex = hashChainStart;
    while (currentChainIndex != 0) {
      if (currentChainIndex == node) {
        // The node is already contained in the hash list
        return;
      }
      long currentChainStore = referenceStorage[currentChainIndex];
      assert (int) getNextChainEntryFromStore(currentChainStore) != currentChainIndex;
      currentChainIndex = (int) getNextChainEntryFromStore(currentChainStore);
      chainLength += 1;
    }
    this.hashChainLookupLength += chainLength;
    this.hashChainLookups += 1;

    referenceStorage[node] = setNextChainEntryInStore(referenceStorage[node], hashChainStart);
    // when node == hash, we have to re-fetch the changed entry and can't reuse hashReferenceStore
    referenceStorage[hash] = setChainStartInStore(referenceStorage[hash], node);
  }

  @SuppressWarnings({"PMD.AvoidDeeplyNestedIfStmts", "UnusedReturnValue"})
  public final int dereference(int node) {
    assert isNodeValidOrRoot(node);
    long referenceStore = referenceStorage[node];
    if (isStoreSaturated(referenceStore)) {
      return node;
    }
    assert getReferenceCountFromStore(referenceStore) > 0L;
    if (getReferenceCountFromStore(referenceStore) == 1L) {
      // After decrease its 0

      // We are approximating the actual dead node count here - it could be the case that
      // this node was the only one keeping it's children "alive" - similarly, this node could be
      // kept alive by other nodes "above" it.
      approximateDeadNodeCount++;
      if (node == biggestReferencedNode) {
        // Update biggestReferencedNode
        for (int i = biggestReferencedNode - 1; i >= 0; i--) {
          if (isReferencedOrSaturatedNodeStore(referenceStorage[i])) {
            biggestReferencedNode = i;
            break;
          }
        }
      }
    }
    referenceStorage[node] = decreaseReferenceCountInStore(referenceStore);
    return node;
  }

  private int doGarbageCollection() {
    assert check();
    garbageCollectionCount += 1;

    int markedNodes = 0;
    for (int i = 0; i < workStackIndex; i++) {
      int node = workStack[i];
      if (!isNodeRoot(node) && isNodeValid(node)) {
        markedNodes += markAllBelow(node);
      }
    }

    // Clear chain starts (we need to rebuild them) and push referenced nodes on the mark stack.
    // Loops are merged so that referenceStore[i] does not have to be loaded twice (this the JVM
    // probably can't optimise as there are no guarantees about multi-threading etc.). All those
    // nodes bigger than biggestValidNode will be cleared below.
    referenceStorage[0] = clearChainStartInStore(referenceStorage[0]);
    referenceStorage[1] = clearChainStartInStore(referenceStorage[1]);
    for (int i = 2; i <= biggestValidNode; i++) {
      long referenceStore = referenceStorage[i];
      if (i <= biggestReferencedNode && isReferencedOrSaturatedNodeStore(referenceStore)) {
        markAllBelow(i);
      }
      referenceStorage[i] = clearChainStartInStore(referenceStore);
    }

    int previousFreeNodes = freeNodeCount;
    firstFreeNode = 0;
    freeNodeCount = getTableSize() - (biggestValidNode + 1);

    // Connect all definitely invalid nodes in the free node chain
    for (int i = getTableSize() - 1; i > biggestValidNode; i--) {
      referenceStorage[i] = setNextChainEntryInStore(referenceStorage[i], firstFreeNode);
      firstFreeNode = i;
    }
    // Rebuild hash chain for valid nodes, connect invalid nodes into the free chain
    // We need to rebuild the chain for unused nodes first as a smaller, unused node might be part
    // of a chain containing bigger nodes which are in use.
    for (int i = biggestValidNode; i >= 2; i--) {
      long nodeStore = nodeStorage[i];
      if (!isNodeStoreMarked(nodeStore)) {
        // This node is unused
        nodeStorage[i] = invalidStore();
        referenceStorage[i] = setNextChainEntryInStore(referenceStorage[i], firstFreeNode);
        firstFreeNode = i;
        if (i == biggestValidNode) {
          biggestValidNode--;
        }
        freeNodeCount++;
      }
    }
    for (int i = biggestValidNode; i >= 2; i--) {
      long nodeStore = nodeStorage[i];
      if (isNodeStoreMarked(nodeStore)) {
        // This node is used
        nodeStorage[i] = unsetMarkInStore(nodeStore);
        connectHashList(i, hashNodeStore(nodeStore));
      }
    }

    approximateDeadNodeCount = 0;

    assert check();
    int collectedNodes = freeNodeCount - previousFreeNodes;
    this.garbageCollectedNodeCount += collectedNodes;
    return collectedNodes;
  }

  private void ensureMarkStackSize(int size) {
    if (size < internalStack.length) {
      return;
    }
    // At least double the size each new allocation
    internalStack = Arrays.copyOf(internalStack, Math.max(size, 2 * internalStack.length));
  }

  private void ensureWorkStackSize(int size) {
    if (size < workStack.length) {
      return;
    }
    int newSize = workStack.length * 2;
    workStack = Arrays.copyOf(workStack, newSize);
  }

  /**
   * Perform garbage collection by freeing up dead nodes.
   *
   * @return Number of freed nodes.
   */
  public final int forceGc() {
    int dead = approximateDeadNodeCount;
    int freedNodes = doGarbageCollection();
    notifyGcRun();
    return freedNodes;
  }

  public final int getApproximateDeadNodeCount() {
    return approximateDeadNodeCount;
  }

  final BddConfiguration getConfiguration() {
    return configuration;
  }

  public final int getFreeNodeCount() {
    return freeNodeCount;
  }

  public final int high(int node) {
    assert isNodeValid(node);
    return (int) getHighFromStore(nodeStorage[node]);
  }

  public final int low(int node) {
    assert isNodeValid(node);
    return (int) getLowFromStore(nodeStorage[node]);
  }

  /**
   * Returns the {@code node}'s store. Probably only useful if used in conjunction with the static
   * node store manipulation methods of this class. Undefined behaviour when the node is invalid.
   *
   * @param node
   *     The node whose store should be returned.
   *
   * @return The store of {@code node}.
   *
   * @see #isNodeValid(int)
   */
  final long getNodeStore(int node) {
    assert isNodeValid(node);
    return nodeStorage[node];
  }

  /**
   * Returns the reference count of the given node or -1 if the node is saturated.
   *
   * @param node
   *     The node to be queried.
   *
   * @return The reference count of {@code node}.
   */
  public final int getReferenceCount(int node) {
    assert isNodeValidOrRoot(node);
    long referenceStore = referenceStorage[node];
    if (isStoreSaturated(referenceStore)) {
      return -1;
    }
    return (int) getReferenceCountFromStore(referenceStore);
  }

  public final int getTableSize() {
    return nodeStorage.length;
  }

  public final int variable(int node) {
    assert isNodeValid(node);
    return (int) getVariableFromStore(nodeStorage[node]);
  }

  /**
   * Tries to free space by garbage collection and, if that does not yield enough free nodes,
   * re-sizes the table, recreating hashes.
   *
   * @return Whether the table size has changed.
   */
  final boolean ensureCapacity() {
    assert check();

    if (configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
      logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes",
          new Object[] {this, getTableSize(), approximateDeadNodeCount});

      int clearedNodes = doGarbageCollection();
      logger.log(Level.FINE, "Collected {0} nodes", clearedNodes);

      if (freeNodeCount > (getTableSize() * configuration.minimumFreeNodePercentageAfterGc())) {
        notifyGcRun(); // Force all caches to be wiped out!
        return false;
      }
    }

    growCount += 1;
    int oldSize = getTableSize();
    int newSize = MathUtil.nextPrime((int) Math.ceil(oldSize * configuration.growthFactor()));
    assert oldSize < newSize : "Got new size " + newSize + " with old size " + oldSize;

    // Could not free enough space by GC, start growing
    logger.log(Level.FINE, "Growing the table of {0} from {1} to {2}",
        new Object[] {this, oldSize, newSize});

    nodeStorage = Arrays.copyOf(nodeStorage, newSize);
    referenceStorage = Arrays.copyOf(referenceStorage, newSize);

    // Invalidate the new nodes and insert them into the reference list
    Arrays.fill(nodeStorage, oldSize, newSize, invalidStore());
    for (int i = 0; i < oldSize; i++) {
      long referenceStore = referenceStorage[i];
      referenceStore = clearChainStartInStore(referenceStore);
      referenceStore = setNextChainEntryInStore(referenceStore, i + 1);
      referenceStorage[i] = referenceStore;
    }
    for (int i = oldSize; i < newSize - 1; i++) {
      referenceStorage[i] = startNoneAndNextStore(i + 1);
    }
    referenceStorage[newSize - 1] = startNoneAndNextStore(0);

    firstFreeNode = oldSize;
    freeNodeCount = newSize - oldSize;

    // Update the hash references and free nodes chain of the old nodes.
    for (int i = oldSize - 1; i >= 2; i--) {
      long nodeStore = nodeStorage[i];
      if (isValidNodeStore(nodeStore)) {
        connectHashList(i, hashNodeStore(nodeStore));
      } else {
        assert !isValidNodeStore(nodeStore);
        referenceStorage[i] = setNextChainEntryInStore(referenceStorage[i], firstFreeNode);
        firstFreeNode = i;
        freeNodeCount++;
      }
    }

    assert check();

    notifyTableSizeChanged();
    return true;
  }

  /**
   * Inform the table about a new tree size, i.e. the amount of variables changed.
   *
   * @param newTreeSize
   *     The new maximal tree height (when interpreting a BDD as a graph)
   */
  final void growTree(int newTreeSize) {
    assert newTreeSize >= treeSize;
    treeSize = newTreeSize;
    ensureMarkStackSize(getMarkStackSizeForTreeSize(newTreeSize));
    ensureWorkStackSize(newTreeSize * 2);
  }

  private int hashNodeStore(long nodeStore) {
    int tableSize = getTableSize();
    // Mark must not be hashed
    int hash = HashUtil.hash(nodeStore >>> 1) % tableSize;
    if (hash < 0) {
      return hash + tableSize;
    }
    return hash;
  }

  /**
   * Determines whether the given {@code node} is a root node (i.e. either <tt>false</tt> or
   * <tt>true</tt>.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If {@code node} is a root node.
   */
  public final boolean isNodeRoot(int node) {
    assert 0 <= node && node < getTableSize();
    return node <= 1;
  }

  /**
   * Checks if the given {@code node} is saturated. This can happen if the node is explicitly marked
   * as saturated or gets referenced too often.
   *
   * @param node
   *     The node to be checked
   *
   * @return Whether the node is saturated
   *
   * @see #saturateNode(int)
   */
  public final boolean isNodeSaturated(int node) {
    assert isNodeValidOrRoot(node);
    return isStoreSaturated(referenceStorage[node]);
  }

  public final boolean isNodeValid(int node) {
    assert 0 <= node && node < getTableSize();
    return 2 <= node && node <= biggestValidNode && isValidNodeStore(nodeStorage[node]);
  }

  /**
   * Determines if the given {@code node} is either a root node or valid. For most operations it is
   * required that this is the case.
   *
   * @param node
   *     The node to be checked.
   *
   * @return If {@code} is valid or root node.
   *
   * @see #isNodeRoot(int)
   */
  public final boolean isNodeValidOrRoot(int node) {
    assert 0 <= node && node < getTableSize() : node + " invalid";
    return node <= biggestValidNode && (isNodeRoot(node) || isValidNodeStore(nodeStorage[node]));
  }

  private boolean isNoneMarked() {
    for (int i = 2; i < nodeStorage.length; i++) {
      if (isNodeStoreMarked(nodeStorage[i])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks if the work stack is empty. Mostly useful for integrity checking.
   *
   * @return True iff the work stack is empty.
   */
  final boolean isWorkStackEmpty() {
    return workStackIndex == 0;
  }


  final int makeNode(int variable, int low, int high) {
    assert 0 <= variable;

    if (low == high) {
      return low;
    }

    createdNodes += 1;
    long nodeStore = buildNodeStore(variable, low, high);
    int hash = hashNodeStore(nodeStore);
    int currentLookupNode = (int) getChainStartFromStore(referenceStorage[hash]);
    assert currentLookupNode < getTableSize() : "Invalid previous entry for " + hash;

    // Search for the the node in the hash chain
    int chainLookups = 1;
    while (currentLookupNode != 0) {
      if (nodeStoresEqual(nodeStore, nodeStorage[currentLookupNode])) {
        return currentLookupNode;
      }
      long currentLookupNodeReferenceStore = referenceStorage[currentLookupNode];
      assert (int) getNextChainEntryFromStore(currentLookupNodeReferenceStore) != currentLookupNode;
      currentLookupNode = (int) getNextChainEntryFromStore(currentLookupNodeReferenceStore);
      chainLookups += 1;
    }
    this.hashChainLookupLength += chainLookups;
    this.hashChainLookups += 1;

    // Check we have enough space to add the node
    assert freeNodeCount > 0;
    if (freeNodeCount == 1) {
      // We need a starting point for the free chain node, hence grow if only one node is remaining
      // instead of occupying that node
      if (ensureCapacity()) { // NOPMD
        // Table size has changed, hence re-hash
        hash = hashNodeStore(nodeStore);
      }
    }

    // Take next free node
    int freeNode = firstFreeNode;
    firstFreeNode = (int) getNextChainEntryFromStore(referenceStorage[firstFreeNode]);
    assert firstFreeNode < nodeStorage.length;
    freeNodeCount--;
    assert !isValidNodeStore(nodeStorage[freeNode]) : "Overwriting existing node";
    // Adjust and write node
    nodeStorage[freeNode] = nodeStore;
    if (biggestValidNode < freeNode) {
      biggestValidNode = freeNode;
    }
    connectHashList(freeNode, hash);
    return freeNode;
  }

  private int markAllBelow(int node) {
    /* The algorithm does not descend into trees whose root is marked, hence at the start of the
     * algorithm, there must be no marks in the tree to ensure correctness. */
    assert isNodeValidOrRoot(node);

    int[] markStack = this.internalStack;
    long[] nodeStorage = this.nodeStorage;

    int stackIndex = 0;
    int markedCount = 0;

    int current = node;

    while (true) {
      while (!isNodeRoot(current)) {
        long store = nodeStorage[current];
        long markedNodeStore = setMarkInStore(store);

        if (store == markedNodeStore) {
          // Node was marked
          break;
        }

        nodeStorage[current] = markedNodeStore;
        markedCount++;

        markStack[stackIndex] = (int) getHighFromStore(store);
        current = (int) getLowFromStore(store);

        stackIndex += 1;
      }

      if (stackIndex == 0) {
        break;
      }

      current = markStack[--stackIndex];
    }

    return markedCount;
  }

  final int unMarkAll() {
    int unmarkedCount = 0;
    for (int i = 2; i < getTableSize(); i++) {
      long nodeStore = nodeStorage[i];
      if (isValidNodeStore(nodeStore)) {
        long unmarkedNodeStore = unsetMarkInStore(nodeStore);
        if (nodeStore != unmarkedNodeStore) { // Node was marked
          unmarkedCount++;
          nodeStorage[i] = unmarkedNodeStore;
        }
      }
    }
    return unmarkedCount;
  }

  /**
   * Removes the marks from all nodes below the specified one.
   *
   * @param node
   *     The node whose descendants should be unmarked.
   *
   * @return The number of unmarked nodes.
   */
  final int unMarkAllBelow(int node) {
    assert isNodeValidOrRoot(node);

    int[] markStack = this.internalStack;
    long[] nodeStorage = this.nodeStorage;

    int stackIndex = 0;
    int unmarkedCount = 0;

    int current = node;

    while (true) {
      while (!isNodeRoot(current)) {
        long store = nodeStorage[current];
        long unmarkedNodeStore = unsetMarkInStore(store);

        if (store == unmarkedNodeStore) {
          // Node was not marked
          break;
        }

        nodeStorage[current] = unmarkedNodeStore;
        unmarkedCount++;

        markStack[stackIndex] = (int) getHighFromStore(store);
        current = (int) getLowFromStore(store);

        stackIndex += 1;
      }

      if (stackIndex == 0) {
        break;
      }

      current = markStack[--stackIndex];
    }

    assert isNoneMarked();
    return unmarkedCount;
  }

  private void unMarkTreeRecursive(int node) {
    if (isNodeRoot(node)) {
      return;
    }

    long nodeStore = nodeStorage[node];
    if (!isNodeStoreMarked(nodeStore)) {
      return;
    }

    nodeStorage[node] = unsetMarkInStore(nodeStore);
    unMarkTreeRecursive((int) getLowFromStore(nodeStore));
    unMarkTreeRecursive((int) getHighFromStore(nodeStore));
  }

  final boolean allAreMarkedBelow(int node) {
    if (isNodeRoot(node)) {
      return true;
    }

    long nodeStore = nodeStorage[node];
    return isNodeStoreMarked(nodeStore)
        && allAreMarkedBelow((int) getLowFromStore(nodeStore))
        && allAreMarkedBelow((int) getHighFromStore(nodeStore));
  }

  final boolean noneIsMarkedBelow(int node) {
    if (isNodeRoot(node)) {
      return true;
    }

    long nodeStore = nodeStorage[node];
    return !isNodeStoreMarked(nodeStore)
        && noneIsMarkedBelow((int) getLowFromStore(nodeStore))
        && noneIsMarkedBelow((int) getHighFromStore(nodeStore));
  }

  final void markNode(int node) {
    assert isNodeValid(node);
    nodeStorage[node] = setMarkInStore(nodeStorage[node]);
  }

  /**
   * Counts the number of nodes below the specified {@code node}.
   *
   * @param node
   *     The node to be counted.
   *
   * @return The number of non-root nodes below {@code node}.
   */
  public final int nodeCount(int node) {
    assert isNodeValidOrRoot(node);
    assert isNoneMarked();

    int count = markAllBelow(node);
    if (count > 0) {
      unMarkAllBelow(node);
    }

    assert isNoneMarked();
    return count;
  }

  /**
   * Counts the number of active nodes in the BDD (i.e. the ones which are not invalid),
   * <b>excluding</b> the root nodes. <p> Note that this operation is somewhat expensive. </p>
   *
   * @return Number of active nodes.
   */
  public final int nodeCount() {
    // Strategy: We gather all root nodes (i.e. nodes which are referenced) on the mark stack, mark
    // all of their children, count all marked nodes and un-mark them.
    assert isNoneMarked();

    int count = 0;
    for (int i = 2; i < getTableSize(); i++) {
      long nodeStore = nodeStorage[i];
      if (isValidNodeStore(nodeStore)) {
        long referenceStore = referenceStorage[i];
        if (isReferencedOrSaturatedNodeStore(referenceStore)) {
          count += markAllBelow(i);
        }
      }
    }

    int unmarkedCount = unMarkAll();

    assert count == unmarkedCount;

    assert isNoneMarked();
    return count;
  }

  private int nodeCountRecursive(int node) {
    if (isNodeRoot(node)) {
      return 0;
    }
    long bddStore = nodeStorage[node];
    if (isNodeStoreMarked(bddStore)) {
      // This node has already been counted
      return 0;
    }
    nodeStorage[node] = setMarkInStore(bddStore);
    return 1
        + nodeCountRecursive((int) getLowFromStore(bddStore))
        + nodeCountRecursive((int) getHighFromStore(bddStore));
  }

  final String nodeToString(int node) {
    long nodeStore = nodeStorage[node];
    if (!isValidNodeStore(nodeStore)) {
      return String.format("%5d| == INVALID ==", node);
    }
    long referenceStore = referenceStorage[node];
    String referenceCountString;
    if (isStoreSaturated(referenceStore)) {
      referenceCountString = "SAT";
    } else {
      referenceCountString = String.format("%3d", getReferenceCountFromStore(referenceStore));
    }
    return String.format("%5d|%3d|%5d|%5d|%s", node, getVariableFromStore(nodeStore),
        getLowFromStore(nodeStore), getHighFromStore(nodeStore), referenceCountString);
  }

  final NodeToStringSupplier nodeToStringSupplier(int node) {
    return new NodeToStringSupplier(this, node);
  }

  void notifyGcRun() {
    /* do nothing */
  }

  void notifyTableSizeChanged() {
    /* do nothing */
  }

  /**
   * Removes the topmost element from the stack.
   *
   * @see #pushToWorkStack(int)
   */
  final void popWorkStack() {
    assert workStackIndex >= 1;
    workStackIndex--;
  }

  /**
   * Removes the {@code amount} topmost elements from the stack.
   *
   * @param amount
   *     The amount of elements to be removed.
   *
   * @see #pushToWorkStack(int)
   */
  final void popWorkStack(int amount) {
    assert workStackIndex >= amount;
    workStackIndex -= amount;
  }

  /**
   * Removes the topmost element from the stack and returns the given argument (for chaining).
   *
   * @see #popWorkStack()
   */
  final int popWorkStackAndReturn(int result) {
    assert workStackIndex >= 1;
    workStackIndex--;
    return result;
  }

  final int getWorkStack() {
    assert workStackIndex > 0;
    return workStack[workStackIndex - 1];
  }

  @SuppressWarnings("ValueOfIncrementOrDecrementUsed")
  final int getAndPopWorkStack() {
    assert workStackIndex >= 1;
    return workStack[--workStackIndex];
  }

  /**
   * Pushes the given node onto the stack. While a node is on the work stack, it will not be garbage
   * collected. Hence, elements should be popped from the stack as soon as they are not used
   * anymore.
   *
   * @param node
   *     The node to be pushed.
   *
   * @return The given {@code node}, to be used for chaining.
   *
   * @see #popWorkStack(int)
   */
  final int pushToWorkStack(int node) {
    assert isNodeValidOrRoot(node);
    ensureWorkStackSize(workStackIndex);
    workStack[workStackIndex] = node;
    workStackIndex += 1;
    return node;
  }

  final int workStackSize() {
    return workStackIndex;
  }

  final int[] cloneWorkStack() {
    return Arrays.copyOf(workStack, workStackIndex);
  }

  public final int reference(int node) {
    assert isNodeValidOrRoot(node);
    long referenceStore = referenceStorage[node];
    if (isStoreSaturated(referenceStore)) {
      return node;
    }
    assert 0L <= getReferenceCountFromStore(referenceStore);

    // This node is referenced to often, we have to saturate it. :(
    if (getReferenceCountFromStore(referenceStore) == MAXIMAL_REFERENCE_COUNT) {
      referenceStorage[node] = saturateStore(referenceStore);
    } else {
      referenceStorage[node] = increaseReferenceCountInStore(referenceStore);
    }
    // Can't decrease approximateDeadNodeCount here - we may reference a node for the first time.
    if (node > biggestReferencedNode) {
      biggestReferencedNode = node;
    }
    return node;
  }

  /**
   * Counts the number of referenced or saturated nodes.
   *
   * @return Number of referenced nodes.
   */
  public final int referencedNodeCount() {
    int count = 0;
    for (int i = 2; i <= biggestReferencedNode; i++) {
      if (isValidNodeStore(nodeStorage[i])
          && isReferencedOrSaturatedNodeStore(referenceStorage[i])) {
        count++;
      }
    }
    return count;
  }

  final int saturateNode(int node) {
    assert isNodeValidOrRoot(node);
    if (node > biggestReferencedNode) {
      biggestReferencedNode = node;
    }
    referenceStorage[node] = saturateStore(referenceStorage[node]);
    return node;
  }

  /**
   * Generates a string representation of the given {@code node}.
   *
   * @param node
   *     The node to be printed.
   *
   * @return A string representing the given node.
   */
  public final String treeToString(int node) {
    assert isNodeValidOrRoot(node);
    assert isNoneMarked();
    if (isNodeRoot(node)) {
      return String.format("Node %d%n", node);
    }
    //noinspection MagicNumber
    StringBuilder builder = new StringBuilder(50).append("Node ").append(node).append('\n')
        .append("  NODE|VAR| LOW | HIGH|REF\n");
    treeToStringRecursive(node, builder);
    unMarkAllBelow(node);
    return builder.toString();
  }

  private void treeToStringRecursive(int node, StringBuilder builder) {
    if (isNodeRoot(node)) {
      return;
    }
    long nodeStore = nodeStorage[node];
    if (isNodeStoreMarked(nodeStore)) {
      return;
    }
    nodeStorage[node] = setMarkInStore(nodeStore);
    builder.append(' ').append(nodeToString(node)).append('\n');
    treeToStringRecursive((int) getLowFromStore(nodeStore), builder);
    treeToStringRecursive((int) getHighFromStore(nodeStore), builder);
  }

  public String getStatistics() {
    int childrenCount = 0;
    int saturatedNodes = 0;
    int referencedNodes = 0;
    int validNodes = 0;

    for (int node = 2; node < getTableSize(); node++) {
      long nodeStore = nodeStorage[node];
      if (isValidNodeStore(nodeStore)) {
        validNodes += 1;
        long referenceStore = referenceStorage[node];
        if (isReferencedOrSaturatedNodeStore(referenceStore)) {
          referencedNodes += 1;
          childrenCount += markAllBelow(node);

          if (isStoreSaturated(referenceStore)) {
            saturatedNodes += 1;
          }
        }
      }
    }

    unMarkAll();

    int[] chainLength = new int[getTableSize()];
    Deque<Integer> path = new ArrayDeque<>();
    int distinctChains = 0;

    for (int node = 2; node < getTableSize(); node++) {
      long nodeStore = nodeStorage[node];
      if (chainLength[node] > 0) {
        continue;
      }

      if (isValidNodeStore(nodeStore)) {
        int chainPosition =
            (int) getChainStartFromStore(referenceStorage[hashNodeStore(nodeStore)]);
        int length = 0;
        while (chainPosition != 0) {
          path.push(chainPosition);
          if (chainPosition == node) {
            distinctChains += 1;
            break;
          }
          chainPosition = (int) getNextChainEntryFromStore(referenceStorage[chainPosition]);
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

    return String.format("Node table statistics:%n"
            + "Table Size: %1$d, (largest ref: %2$d), %3$d created nodes%n"
            + "%4$d valid nodes, %5$d referenced (%6$d saturated), %7$d children%n"
            + "Hash table: %8$d chains %9$.2f load, %10$.2f avg, %11$d max; "
            /* */ + "%12$d lookups, %13$.2f avg. len%n"
            + "%14$d GC runs, %15$d freed, %16$d grows",
        getTableSize(), biggestReferencedNode, createdNodes,
        validNodes, referencedNodes, saturatedNodes, childrenCount,
        distinctChains, sum * 1.0f / getTableSize(), sum * 1.0f / distinctChains, max,
        /* */ hashChainLookups, hashChainLookupLength * 1.0f / hashChainLookups,
        garbageCollectionCount, garbageCollectedNodeCount, growCount
    );
  }

  private static final class NodeToStringSupplier {
    private final int node;
    private final NodeTable table;

    public NodeToStringSupplier(NodeTable table, int node) {
      this.table = table;
      this.node = node;
    }

    @Override
    public String toString() {
      return table.nodeToString(node);
    }
  }
}
