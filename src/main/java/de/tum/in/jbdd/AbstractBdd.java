package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

@SuppressWarnings({"NumericCastThatLosesPrecision"})
abstract class AbstractBdd extends NodeTable implements Bdd {
  static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);

  protected static final int FALSE_NODE = 0;
  protected static final int TRUE_NODE = 1;
  protected final BddCache cache;
  protected int numberOfVariables;
  protected int[] variableNodes;


  AbstractBdd(int nodeSize, BddConfiguration configuration) {
    super(MathUtil.nextPrime(nodeSize), configuration);
    cache = new BddCache(this);
    variableNodes = new int[configuration.initialVariableNodes()];
    numberOfVariables = 0;
  }

  protected static boolean isVariableOrNegatedStore(long nodeStore) {
    int low = (int) getLowFromStore(nodeStore);
    int high = (int) getHighFromStore(nodeStore);
    return (low == FALSE_NODE && high == TRUE_NODE)
        || (low == TRUE_NODE && high == FALSE_NODE);
  }

  @Override
  public int trueNode() {
    return TRUE_NODE;
  }

  @Override
  public int falseNode() {
    return FALSE_NODE;
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
    int notVariableNode = saturateNode(makeNode(numberOfVariables, TRUE_NODE, FALSE_NODE));

    if (numberOfVariables == variableNodes.length) {
      variableNodes = Arrays.copyOf(variableNodes, variableNodes.length * 2);
    }
    variableNodes[numberOfVariables] = variableNode;
    numberOfVariables++;

    cache.putNot(variableNode, notVariableNode);
    cache.putNot(notVariableNode, variableNode);
    growTree(numberOfVariables);
    cache.invalidateSatisfaction();
    cache.invalidateCompose();
    cache.reallocateVolatile();
    afterVariableCountChanged();

    return variableNode;
  }

  @Override
  public int[] createVariables(int count) {
    int newSize = numberOfVariables + count;
    if (newSize >= variableNodes.length) {
      variableNodes = Arrays.copyOf(variableNodes, Math.max(variableNodes.length * 2, newSize));
    }

    int[] variableNodes = new int[count];

    for (int i = 0; i < count; i++) {
      int variable = numberOfVariables + i;

      int variableNode = saturateNode(makeNode(variable, FALSE_NODE, TRUE_NODE));
      int notVariableNode = saturateNode(makeNode(variable, TRUE_NODE, FALSE_NODE));
      variableNodes[i] = variableNode;
      this.variableNodes[variable] = variableNode;

      cache.putNot(variableNode, notVariableNode);
      cache.putNot(notVariableNode, variableNode);
    }
    numberOfVariables += count;

    growTree(numberOfVariables);
    cache.invalidateSatisfaction();
    cache.invalidateCompose();
    cache.reallocateVolatile();
    afterVariableCountChanged();

    return variableNodes;
  }

  protected abstract void afterVariableCountChanged();

  @Override
  public boolean isVariable(int node) {
    if (isNodeRoot(node)) {
      return false;
    }
    long nodeStore = getNodeStore(node);
    return (int) getLowFromStore(nodeStore) == FALSE_NODE
        && (int) getHighFromStore(nodeStore) == TRUE_NODE;
  }

  @Override
  public boolean isVariableNegated(int node) {
    if (isNodeRoot(node)) {
      return false;
    }
    long nodeStore = getNodeStore(node);
    return (int) getLowFromStore(nodeStore) == TRUE_NODE
        && (int) getHighFromStore(nodeStore) == FALSE_NODE;
  }

  @Override
  public boolean isVariableOrNegated(int node) {
    assert isNodeValidOrRoot(node);
    if (isNodeRoot(node)) {
      return false;
    }
    long nodeStore = getNodeStore(node);
    return isVariableOrNegatedStore(nodeStore);
  }


  @Override
  public boolean evaluate(int node, boolean[] assignment) {
    int currentBdd = node;
    while (currentBdd >= 2) {
      long currentBddStore = getNodeStore(currentBdd);
      int currentBddVariable = (int) getVariableFromStore(currentBddStore);
      currentBdd = (int) (assignment[currentBddVariable]
          ? getHighFromStore(currentBddStore)
          : getLowFromStore(currentBddStore));
    }
    return currentBdd == TRUE_NODE;
  }

  @Override
  public boolean evaluate(int node, BitSet assignment) {
    int currentBdd = node;
    while (currentBdd >= 2) {
      long currentBddStore = getNodeStore(currentBdd);
      int currentBddVariable = (int) getVariableFromStore(currentBddStore);
      currentBdd = (int) (assignment.get(currentBddVariable)
          ? getHighFromStore(currentBddStore)
          : getLowFromStore(currentBddStore));
    }
    return currentBdd == TRUE_NODE;
  }


  @Override
  public BitSet getSatisfyingAssignment(int node) {
    assert isNodeValidOrRoot(node);

    if (node == FALSE_NODE) {
      throw new NoSuchElementException("False has no solution");
    }

    BitSet path = new BitSet(numberOfVariables);
    int currentNode = node;
    while (currentNode != TRUE_NODE) {
      long store = getNodeStore(currentNode);

      int lowNode = (int) getLowFromStore(store);
      if (lowNode == FALSE_NODE) {
        int highNode = (int) getHighFromStore(store);
        int variable = (int) getVariableFromStore(store);

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
    if (node == FALSE_NODE) {
      return Collections.emptyIterator();
    }
    if (node == TRUE_NODE) {
      return new PowerIterator(numberOfVariables);
    }

    return new NodeSolutionIterator(this, node);
  }


  abstract int existsSelfSubstitution(int node, BitSet quantifiedVariables);

  abstract int existsShannon(int node, BitSet quantifiedVariables);


  @Override
  void notifyGcRun() {
    cache.invalidate();
  }

  @Override
  void notifyTableSizeChanged() {
    cache.invalidate();
  }

  @Override
  public String statistics() {
    return super.getStatistics() + '\n' + cache.getStatistics();
  }

  static final class NodeSolutionIterator implements Iterator<BitSet> {
    private static final int NON_PATH_NODE = -1;

    private final AbstractBdd bdd;
    private final BitSet assignment;
    private final int variableCount;
    private final int[] path;
    private boolean firstRun = true;
    private int highestLowVariableWithNonFalseHighBranch = 0;
    private int leafNodeIndex;
    private boolean hasNextPath;
    private boolean hasNextAssignment;
    private final int rootVariable;

    NodeSolutionIterator(AbstractBdd bdd, int node) {
      // Require at least one possible solution to exist.
      assert bdd.isNodeValid(node) || node == TRUE_NODE;
      variableCount = bdd.numberOfVariables();

      // Assignments don't make much sense otherwise
      assert variableCount > 0;

      this.bdd = bdd;

      this.path = new int[variableCount];
      this.assignment = new BitSet(variableCount);
      rootVariable = bdd.variable(node);

      Arrays.fill(path, NON_PATH_NODE);
      path[rootVariable] = node;

      leafNodeIndex = 0;
      hasNextPath = true;
      hasNextAssignment = true;
    }

    @Override
    public boolean hasNext() {
      assert !hasNextPath || hasNextAssignment;
      return hasNextAssignment;
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    @Override
    public BitSet next() {
      int currentNode;
      if (firstRun) {
        firstRun = false;
        currentNode = path[rootVariable];
      } else {
        // Check if we can flip any non-path variable
        boolean clearedAny = false;
        for (int index = 0; index < variableCount; index++) {
          // Strategy: Perform binary addition on the NON_PATH_NODEs
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
                for (int i = index + 1; i < variableCount; i++) {
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

        // Situation: All non-path variables are set to zero and we need to find a new path
        assert IntStream.range(0, variableCount).noneMatch(index ->
            path[index] == NON_PATH_NODE && assignment.get(index));
        assert hasNextPath : "Expected another path after " + assignment + ", node:\n"
            + bdd.treeToString(path[rootVariable]);

        // Backtrack on the current path until we find a node set to low and non-false high branch
        // to find a new path in the BDD
        // TODO Use highestLowVariableWithNonFalseHighBranch?
        currentNode = path[leafNodeIndex];
        int branchIndex = leafNodeIndex;
        while (assignment.get(branchIndex) || bdd.high(currentNode) == FALSE_NODE) {
          // This node does not give us another branch, backtrack over the path until we get to
          // the next element of the path
          // TODO Could track the previous path element in int[]
          do {
            branchIndex -= 1;
            if (branchIndex == -1) {
              throw new NoSuchElementException("No next element");
            }
          } while (path[branchIndex] == NON_PATH_NODE);
          currentNode = path[branchIndex];
        }
        assert !assignment.get(branchIndex) && bdd.high(currentNode) != FALSE_NODE;
        assert leafNodeIndex >= highestLowVariableWithNonFalseHighBranch;
        assert bdd.variable(currentNode) == branchIndex;

        // currentNode is the lowest node we can switch to high; set the value and descend the tree
        assignment.clear(branchIndex + 1, leafNodeIndex + 1);
        Arrays.fill(path, branchIndex + 1, leafNodeIndex + 1, NON_PATH_NODE);

        assignment.set(branchIndex);
        assert path[branchIndex] == currentNode;
        currentNode = bdd.high(currentNode);
        assert currentNode != FALSE_NODE;
        leafNodeIndex = branchIndex;

        // We flipped the candidate for low->high transition, clear this information
        if (highestLowVariableWithNonFalseHighBranch == leafNodeIndex) {
          highestLowVariableWithNonFalseHighBranch = -1;
        }
      }

      // Situation: The currentNode valuation was just flipped to 1 or we are in initial state.
      // Descend the tree, searching for a solution and determine if there is a next assignment.

      // If there is a possible path higher up, there definitely are more solutions
      hasNextPath = highestLowVariableWithNonFalseHighBranch > -1
          && highestLowVariableWithNonFalseHighBranch < leafNodeIndex;

      while (currentNode != TRUE_NODE) {
        assert currentNode != FALSE_NODE;
        long currentNodeStore = bdd.getNodeStore(currentNode);
        leafNodeIndex = (int) getVariableFromStore(currentNodeStore);
        path[leafNodeIndex] = currentNode;

        int low = (int) getLowFromStore(currentNodeStore);
        if (low == FALSE_NODE) {
          // Descend high path
          assignment.set(leafNodeIndex);
          currentNode = (int) getHighFromStore(currentNodeStore);
        } else {
          // If there is a non-false high node, we will be able to swap this node later on so we
          // definitely have a next assignment. On the other hand, if there is no such node, the
          // last possible assignment has been reached, as there are no more possible switches
          // higher up in the tree.
          if (!hasNextPath && (int) getHighFromStore(currentNodeStore) != FALSE_NODE) {
            hasNextPath = true;
            highestLowVariableWithNonFalseHighBranch = leafNodeIndex;
          }
          currentNode = low;
        }
      }
      assert bdd.evaluate(path[rootVariable], assignment);

      // If this is a unique path, there won't be any trivial assignments
      // TODO We can make this faster!
      for (int node : path) {
        if (node == NON_PATH_NODE) {
          hasNextAssignment = true;
          return assignment;
        }
      }
      hasNextAssignment = hasNextPath;
      return assignment;
    }
  }
}
