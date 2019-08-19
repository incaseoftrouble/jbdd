package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.function.BiConsumer;

@SuppressWarnings({"NumericCastThatLosesPrecision", "ValueOfIncrementOrDecrementUsed",
                      "BreakStatementWithLabel", "LabeledStatement", "NestedAssignment",
                  "PMD.AssignmentInOperand"})
public class BddIterative extends AbstractBdd {
  private static final int[] EMPTY_INT_ARRAY = new int[0];

  private int[] cacheStackHash = EMPTY_INT_ARRAY;
  private int[] cacheStackFirstArg = EMPTY_INT_ARRAY;
  private int[] cacheStackSecondArg = EMPTY_INT_ARRAY;
  private int[] cacheStackThirdArg = EMPTY_INT_ARRAY;
  private int[] branchStackParentVar = EMPTY_INT_ARRAY;
  private int[] branchStackFirstArg = EMPTY_INT_ARRAY;
  private int[] branchStackSecondArg = EMPTY_INT_ARRAY;
  private int[] branchStackThirdArg = EMPTY_INT_ARRAY;

  BddIterative(int nodeSize) {
    this(nodeSize, ImmutableBddConfiguration.builder().build());
  }

  BddIterative(int nodeSize, BddConfiguration configuration) {
    super(nodeSize, configuration);
  }

  @Override
  protected void afterVariableCountChanged() {
    growStacks();
  }

  private void growStacks() {
    int minimumSize = numberOfVariables + 2;
    if (cacheStackHash.length > minimumSize) {
      return;
    }

    cacheStackHash = new int[minimumSize * 2];
    cacheStackFirstArg = new int[minimumSize * 2];
    cacheStackSecondArg = new int[minimumSize * 2];
    cacheStackThirdArg = new int[minimumSize * 2];

    branchStackParentVar = new int[minimumSize * 2];
    branchStackFirstArg = new int[minimumSize * 2];
    branchStackSecondArg = new int[minimumSize * 2];
    branchStackThirdArg = new int[minimumSize * 2];
  }


  @Override
  public void forEachPath(int node, int highestVariable, BiConsumer<BitSet, BitSet> action) {
    assert isNodeValidOrRoot(node) && highestVariable >= 0;

    if (node == FALSE_NODE) {
      return;
    }
    if (node == TRUE_NODE) {
      action.accept(new BitSet(0), new BitSet(0));
      return;
    }

    int numberOfVariables = numberOfVariables();
    int depthLimit = Math.min(highestVariable, numberOfVariables);

    BitSet path = new BitSet(depthLimit);
    BitSet pathSupport = new BitSet(depthLimit);

    forEachPathIterative(node, depthLimit, path, pathSupport, action, 0);
  }

  public void forEachPathIterative(int node, int highestVariable,
      BitSet path, BitSet pathSupport, BiConsumer<BitSet, BitSet> action, int baseStackIndex) {
    int[] branchStackParentVariable = this.branchStackParentVar;
    int[] branchStackNode = this.branchStackFirstArg;

    int stackIndex = baseStackIndex;

    int current = node;

    while (true) {
      assert stackIndex >= baseStackIndex;

      while (current != TRUE_NODE) {
        long store = getNodeStore(current);

        int variable = (int) getVariableFromStore(store);
        if (variable > highestVariable) {
          break;
        }

        int lowNode = (int) getLowFromStore(store);
        int highNode = (int) getHighFromStore(store);

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
      } while ((current = branchStackNode[--stackIndex]) == FALSE_NODE);

      int variable = branchStackParentVariable[stackIndex];
      path.set(variable);

      path.clear(variable + 1, Integer.MAX_VALUE);
      pathSupport.clear(variable + 1, Integer.MAX_VALUE);
    }
  }


  @Override
  public void support(int node, BitSet bitSet, int variableCutoff) {
    assert isNodeValidOrRoot(node);
    assert 0 <= variableCutoff;

    supportIterative(node, bitSet, variableCutoff, 0);
    unMarkAllBelow(node);
  }

  private void supportIterative(int node, BitSet bitSet, int variableCutoff, int baseStackIndex) {
    int[] branchStackNode = this.branchStackFirstArg;

    int stackIndex = baseStackIndex;

    int current = node;

    while (true) {
      assert stackIndex >= baseStackIndex;

      while (!isNodeRoot(current)) {
        long store = getNodeStore(current);

        if (isNodeStoreMarked(store)) {
          break;
        }

        int variable = (int) getVariableFromStore(store);

        if (variable >= variableCutoff) {
          break;
        }

        bitSet.set(variable);
        markNode(node);

        int lowNode = (int) getLowFromStore(store);
        int highNode = (int) getHighFromStore(store);

        branchStackNode[stackIndex] = highNode;
        current = lowNode;

        stackIndex += 1;
      }

      if (stackIndex == baseStackIndex) {
        return;
      }

      current = branchStackNode[--stackIndex];
    }
  }

  @Override
  public BigInteger countSatisfyingAssignments(int node) {
    assert isNodeValidOrRoot(node);

    if (node == FALSE_NODE) {
      return BigInteger.ZERO;
    }
    if (node == TRUE_NODE) {
      return TWO.pow(numberOfVariables);
    }

    long nodeStore = getNodeStore(node);
    int variable = (int) getVariableFromStore(nodeStore);
    return TWO.pow(variable).multiply(countSatisfyingAssignmentsIterative(node, 0));
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
          long nodeStore = getNodeStore(current);
          nodeVar = (int) getVariableFromStore(nodeStore);

          if ((result = cache.lookupSatisfaction(current)) == null) {
            int lowNode = (int) getLowFromStore(nodeStore);
            int highNode = (int) getHighFromStore(nodeStore);

            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackArg[stackIndex] = current;

            branchStackParentVar[stackIndex] = nodeVar;
            branchTaskStack[stackIndex] = highNode;

            current = lowNode;

            stackIndex += 1;
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
      resultStack[stackIndex] = result.multiply(TWO.pow((nodeVar - parentVar - 1)));

      current = branchTaskStack[stackIndex];
      stackIndex += 1;
    }
  }


  @Override
  public int conjunction(int... variables) {
    int node = TRUE_NODE;
    for (int variable : variables) {
      // Variable nodes are saturated, no need to guard them
      pushToWorkStack(node);
      node = andIterative(node, variableNodes[variable], 0);
      popWorkStack();
    }
    return node;
  }

  @Override
  public int conjunction(BitSet variables) {
    int node = TRUE_NODE;
    for (int currentVariableNumber = variables.nextSetBit(0); currentVariableNumber != -1;
         currentVariableNumber = variables.nextSetBit(currentVariableNumber + 1)) {
      // Variable nodes are saturated, no need to guard them
      pushToWorkStack(node);
      node = andIterative(node, variableNodes[currentVariableNumber], 0);
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
      node = orIterative(node, variableNodes[variable], 0);
      popWorkStack();
    }
    return node;
  }

  @Override
  public int disjunction(BitSet variables) {
    int node = FALSE_NODE;
    for (int currentVariableNumber = variables.nextSetBit(0); currentVariableNumber != -1;
         currentVariableNumber = variables.nextSetBit(currentVariableNumber + 1)) {
      // Variable nodes are saturated, no need to guard them
      pushToWorkStack(node);
      node = orIterative(node, variableNodes[currentVariableNumber], 0);
      popWorkStack();
    }
    return node;
  }

  @Override
  public int and(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = andIterative(node1, node2, 0);

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

      int result = -1;
      do {
        if (current1 == current2 || current2 == TRUE_NODE) {
          result = current1;
        } else if (current1 == FALSE_NODE || current2 == FALSE_NODE) {
          result = FALSE_NODE;
        } else if (current1 == TRUE_NODE) {
          result = current2;
        } else {
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          if (node2var < node1var || (node2var == node1var && current2 < current1)) {
            int nodeSwap = current1;
            current1 = current2;
            current2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;

            long storeSwap = node1store;
            node1store = node2store;
            node2store = storeSwap;
          }

          if (cache.lookupAnd(current1, current2)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackLeft[stackIndex] = current1;
            cacheStackRight[stackIndex] = current2;

            assert isNodeValid(current1) && isNodeValid(current2);

            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = (int) getHighFromStore(node1store);
            if (node1var == node2var) {
              branchStackRight[stackIndex] = (int) getHighFromStore(node2store);
              current2 = (int) getLowFromStore(node2store);
            } else {
              branchStackRight[stackIndex] = current2;
            }
            current1 = (int) getLowFromStore(node1store);

            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));

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


  @Override
  public int compose(int node, int[] variableNodes) {
    assert isWorkStackEmpty();

    assert variableNodes.length <= numberOfVariables;
    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }

    pushToWorkStack(node);

    // Guard the elements and replace -1 by actual variable reference
    int workStackCount = 1;
    for (int i = 0; i < variableNodes.length; i++) {
      int variableNode = variableNodes[i];
      if (variableNode == -1) {
        variableNodes[i] = this.variableNodes[i];
      } else {
        assert isNodeValidOrRoot(variableNode);
        if (!isNodeSaturated(variableNode)) {
          pushToWorkStack(variableNode);
          workStackCount++;
        }
      }
    }

    int highestReplacedVariable = variableNodes.length - 1;
    // Optimise the replacement array
    for (int i = variableNodes.length - 1; i >= 0; i--) {
      if (variableNodes[i] != -1) {
        highestReplacedVariable = i;
        break;
      }
    }
    if (highestReplacedVariable == -1) {
      popWorkStack(workStackCount);
      return node;
    }

    int hash;
    if (getConfiguration().useGlobalComposeCache()) {
      if (cache.lookupCompose(node, variableNodes)) {
        popWorkStack(workStackCount);
        return cache.getLookupResult();
      }
      hash = cache.getLookupHash();
    } else {
      hash = -1;
    }

    cache.clearVolatileCache();
    int result = composeIterative(node, variableNodes, highestReplacedVariable);
    if (getConfiguration().useGlobalComposeCache()) {
      cache.putCompose(hash, node, variableNodes, result);
    }

    popWorkStack(workStackCount);
    assert isWorkStackEmpty();
    return result;
  }

  private int composeIterative(int node, int[] variableNodes, int highestReplacedVariable) {
    int[] cacheStackHash = this.cacheStackHash;
    int[] cacheArgStack = this.cacheStackFirstArg;

    int[] branchStackParentVar = this.branchStackParentVar;
    int[] branchTaskStack = this.branchStackFirstArg;

    int initialSize = workStackSize();

    int stackIndex = 0;
    int current = node;

    while (true) {
      assert stackIndex >= 0;
      assert workStackSize() >= initialSize;

      int result = -1;
      do {
        if (current == TRUE_NODE || current == FALSE_NODE) {
          result = current;
        } else {
          long nodeStore = getNodeStore(current);
          int nodeVariable = (int) getVariableFromStore(nodeStore);

          if (nodeVariable > highestReplacedVariable) {
            result = current;
          } else {
            int replacementNode = variableNodes[nodeVariable];

            if (replacementNode == TRUE_NODE) {
              current = (int) getHighFromStore(nodeStore);
            } else if (replacementNode == FALSE_NODE) {
              current = (int) getLowFromStore(nodeStore);
            } else if (cache.lookupVolatile(current)) {
              result = cache.getLookupResult();
            } else {
              cacheStackHash[stackIndex] = cache.getLookupHash();
              cacheArgStack[stackIndex] = current;

              branchStackParentVar[stackIndex] = nodeVariable;
              branchTaskStack[stackIndex] = (int) getHighFromStore(nodeStore);

              current = (int) getLowFromStore(nodeStore);
              stackIndex += 1;
            }
          }
        }
      } while (result == -1);

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
        int lowResult = getWorkStack();
        pushToWorkStack(result);
        result = ifThenElseIterative(replacementNode, result, lowResult, stackIndex);
        popWorkStack(2);

        cache.putVolatile(currentHash, currentNode, result);

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


  @Override
  public int equivalence(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = equivalenceIterative(node1, node2, 0);

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

      int result = -1;
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
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          if (node2var < node1var || (node2var == node1var && current2 < current1)) {
            int nodeSwap = current1;
            current1 = current2;
            current2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;

            long storeSwap = node1store;
            node1store = node2store;
            node2store = storeSwap;
          }

          if (cache.lookupEquivalence(current1, current2)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackLeft[stackIndex] = current1;
            cacheStackRight[stackIndex] = current2;

            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = (int) getHighFromStore(node1store);
            if (node1var == node2var) {
              branchStackRight[stackIndex] = (int) getHighFromStore(node2store);
              current2 = (int) getLowFromStore(node2store);
            } else {
              branchStackRight[stackIndex] = current2;
            }
            current1 = (int) getLowFromStore(node1store);

            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
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

  @Override
  public int exists(int node, BitSet quantifiedVariables) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node);
    pushToWorkStack(node);
    int result = getConfiguration().useShannonExists()
        ? existsShannon(node, quantifiedVariables)
        : existsSelfSubstitution(node, quantifiedVariables);

    popWorkStack();
    assert isWorkStackEmpty();
    return result;
  }

  // VisibleForTesting
  @Override
  int existsSelfSubstitution(int node, BitSet quantifiedVariables) {
    assert quantifiedVariables.previousSetBit(quantifiedVariables.length()) <= numberOfVariables;
    if (quantifiedVariables.cardinality() == numberOfVariables) {
      return TRUE_NODE;
    }

    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }

    pushToWorkStack(node);

    int[] replacementArray = new int[quantifiedVariables.length()];
    System.arraycopy(variableNodes, 0, replacementArray, 0, replacementArray.length);
    int quantifiedNode = node;
    int workStackElements = 1;
    for (int i = 0; i < quantifiedVariables.length(); i++) {
      if (!quantifiedVariables.get(i)) {
        continue;
      }
      int variableNode = replacementArray[i];

      replacementArray[i] = TRUE_NODE;
      cache.clearVolatileCache();
      // compute f(x, 1)
      replacementArray[i] =
          pushToWorkStack(composeIterative(quantifiedNode, replacementArray, i));
      cache.clearVolatileCache();
      // compute f(x, f(x, 1))
      quantifiedNode = composeIterative(quantifiedNode, replacementArray, i);
      popWorkStack();
      // restore previous replacement value
      replacementArray[i] = variableNode;
      pushToWorkStack(quantifiedNode);
      workStackElements += 1;
    }
    popWorkStack(workStackElements);
    return quantifiedNode;
  }

  // VisibleForTesting
  @Override
  int existsShannon(int node, BitSet quantifiedVariables) {
    assert quantifiedVariables.previousSetBit(quantifiedVariables.length()) <= numberOfVariables;
    if (quantifiedVariables.cardinality() == numberOfVariables) {
      return TRUE_NODE;
    }

    pushToWorkStack(node);
    int quantifiedVariablesConjunction = conjunction(quantifiedVariables);
    pushToWorkStack(quantifiedVariablesConjunction);
    int result = existsShannonIterative(node, quantifiedVariablesConjunction, 0);
    popWorkStack(2);
    return result;
  }

  private int existsShannonIterative(int node, int quantifiedVariableCube, int baseStackIndex) {
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

      int result = -1;
      loop:
      do {
        if (current == TRUE_NODE || current == FALSE_NODE) {
          result = current;
        } else if (quantifiedVariableCube == TRUE_NODE) {
          result = current;
        } else {
          long nodeStore = getNodeStore(current);
          int nodeVariable = (int) getVariableFromStore(nodeStore);

          long currentCubeNodeStore = getNodeStore(currentCubeNode);
          int currentCubeNodeVariable = (int) getVariableFromStore(currentCubeNodeStore);
          while (currentCubeNodeVariable < nodeVariable) {
            currentCubeNode = (int) getHighFromStore(currentCubeNodeStore);
            if (currentCubeNode == TRUE_NODE) {
              // No more variables to project
              result = current;
              break loop;
            }
            currentCubeNodeStore = getNodeStore(currentCubeNode);
            currentCubeNodeVariable = (int) getVariableFromStore(currentCubeNodeStore);
          }

          if (isVariableOrNegatedStore(nodeStore)) {
            if (nodeVariable == currentCubeNodeVariable) {
              result = TRUE_NODE;
            } else {
              result = current;
            }
          } else if (cache.lookupExists(current, currentCubeNode)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackArg[stackIndex] = current;

            branchStackParentVar[stackIndex] = nodeVariable;
            branchStackArg[stackIndex] = (int) getHighFromStore(nodeStore);
            branchStackCubeNode[stackIndex] = currentCubeNode;

            current = (int) getLowFromStore(nodeStore);
            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        int currentNode = cacheStackArg[stackIndex];
        int currentHash = cacheStackHash[stackIndex];

        currentCubeNode = branchStackCubeNode[stackIndex];
        if (getVariableFromStore(getNodeStore(currentCubeNode)) > variable) {
          // The variable of this node is smaller than the variable looked for - only propagate the
          // quantification downward
          result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
          popWorkStack(2);
        } else {
          // nodeVariable == nextVariable, i.e. "quantify out" the current node.
          result = orIterative(getAndPopWorkStack(), result, stackIndex);
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

  @Override
  public int ifThenElse(int ifNode, int thenNode, int elseNode) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(ifNode) && isNodeValidOrRoot(thenNode) && isNodeValidOrRoot(elseNode);
    pushToWorkStack(ifNode);
    pushToWorkStack(thenNode);
    pushToWorkStack(elseNode);
    int result = ifThenElseIterative(ifNode, thenNode, elseNode, 0);

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

      int result = -1;
      do {
        if (currentIf == TRUE_NODE) {
          result = currentThen;
        } else if (currentIf == FALSE_NODE) {
          result = currentElse;
        } else if (currentThen == currentElse) {
          result = currentThen;
        } else if (currentThen == TRUE_NODE) {
          result = currentElse == FALSE_NODE
              ? currentIf
              : orIterative(currentIf, currentElse, stackIndex);
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
          result = cache.getLookupResult();
        } else {
          long ifStore = getNodeStore(currentIf);
          long thenStore = getNodeStore(currentThen);
          long elseStore = getNodeStore(currentElse);

          int ifVar = (int) getVariableFromStore(ifStore);
          int thenVar = (int) getVariableFromStore(thenStore);
          int elseVar = (int) getVariableFromStore(elseStore);

          int minVar = MathUtil.min(ifVar, thenVar, elseVar);
          int ifLowNode;
          int ifHighNode;

          if (ifVar == minVar) {
            ifLowNode = (int) getLowFromStore(ifStore);
            ifHighNode = (int) getHighFromStore(ifStore);
          } else {
            ifLowNode = currentIf;
            ifHighNode = currentIf;
          }

          int thenHighNode;
          int thenLowNode;
          if (thenVar == minVar) {
            thenLowNode = (int) getLowFromStore(thenStore);
            thenHighNode = (int) getHighFromStore(thenStore);
          } else {
            thenLowNode = currentThen;
            thenHighNode = currentThen;
          }

          int elseHighNode;
          int elseLowNode;
          if (elseVar == minVar) {
            elseLowNode = (int) getLowFromStore(elseStore);
            elseHighNode = (int) getHighFromStore(elseStore);
          } else {
            elseLowNode = currentElse;
            elseHighNode = currentElse;
          }

          cacheStackHash[stackIndex] = cache.getLookupHash();
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
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
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


  @Override
  public int implication(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = implicationIterative(node1, node2, 0);

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

      int result = -1;
      do {
        if (current1 == FALSE_NODE || current2 == TRUE_NODE || current1 == current2) {
          result = TRUE_NODE;
        } else if (current1 == TRUE_NODE) {
          result = current2;
        } else if (current2 == FALSE_NODE) {
          result = notIterative(current1, stackIndex);
        } else if (cache.lookupImplication(current1, current2)) {
          result = cache.getLookupResult();
        } else {
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          int node1low = (int) getLowFromStore(node1store);
          int node1high = (int) getHighFromStore(node1store);
          int node2low = (int) getLowFromStore(node2store);
          int node2high = (int) getHighFromStore(node2store);

          cacheStackHash[stackIndex] = cache.getLookupHash();
          cacheStackLeft[stackIndex] = current1;
          cacheStackRight[stackIndex] = current2;

          if (node1var > node2var) {
            branchStackParentVar[stackIndex] = node2var;
            branchStackLeft[stackIndex] = current1;
            branchStackRight[stackIndex] = node2high;

            current2 = node2low;
          } else if (node1var == node2var) {
            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = node1high;
            branchStackRight[stackIndex] = node2high;

            current1 = node1low;
            current2 = node2low;
          } else {
            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = node1high;
            branchStackRight[stackIndex] = current2;

            current1 = node1low;
          }

          stackIndex += 1;
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
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


  @Override
  public boolean implies(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    boolean result = impliesIterative(node1, node2, 0);

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
          if (cache.getLookupResult() == TRUE_NODE) {
            break;
          }
          return false;
        }

        long node1store = getNodeStore(current1);
        long node2store = getNodeStore(current2);
        int node1var = (int) getVariableFromStore(node1store);
        int node2var = (int) getVariableFromStore(node2store);

        int node1low = (int) getLowFromStore(node1store);
        int node1high = (int) getHighFromStore(node1store);
        int node2low = (int) getLowFromStore(node2store);
        int node2high = (int) getHighFromStore(node2store);

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


  @Override
  public int not(int node) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node);
    pushToWorkStack(node);
    int result = notIterative(node, 0);

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

      int result = -1;
      do {
        if (current == FALSE_NODE) {
          result = TRUE_NODE;
        } else if (current == TRUE_NODE) {
          result = FALSE_NODE;
        } else if (cache.lookupNot(current)) {
          result = cache.getLookupResult();
        } else {
          long nodeStore = getNodeStore(current);

          int nodeVar = (int) getVariableFromStore(nodeStore);

          int nodeLow = (int) getLowFromStore(nodeStore);
          int nodeHigh = (int) getHighFromStore(nodeStore);

          cacheStackHash[stackIndex] = cache.getLookupHash();
          cacheArgStack[stackIndex] = current;

          branchStackParentVar[stackIndex] = nodeVar;
          branchTaskStack[stackIndex] = nodeHigh;

          current = nodeLow;

          stackIndex += 1;
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        assert stackIndex >= baseStackIndex;
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
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


  @Override
  public int notAnd(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = notAndIterative(node1, node2, 0);

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

      int result = -1;
      do {
        if (current1 == FALSE_NODE || current2 == FALSE_NODE) {
          result = TRUE_NODE;
        } else if (current1 == TRUE_NODE || current1 == current2) {
          result = notIterative(current2, stackIndex);
        } else if (current2 == TRUE_NODE) {
          result = notIterative(current1, stackIndex);
        } else {
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          if (node2var < node1var || (node2var == node1var && current2 < current1)) {
            int nodeSwap = current1;
            current1 = current2;
            current2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;

            long storeSwap = node1store;
            node1store = node2store;
            node2store = storeSwap;
          }

          if (cache.lookupNAnd(current1, current2)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackLeft[stackIndex] = current1;
            cacheStackRight[stackIndex] = current2;

            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = (int) getHighFromStore(node1store);
            if (node1var == node2var) {
              branchStackRight[stackIndex] = (int) getHighFromStore(node2store);
              current2 = (int) getLowFromStore(node2store);
            } else {
              branchStackRight[stackIndex] = current2;
            }
            current1 = (int) getLowFromStore(node1store);

            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
        popWorkStack(2);

        int left = cacheStackLeft[stackIndex];
        int right = cacheStackRight[stackIndex];
        cache.putNAnd(cacheStackHash[stackIndex], left, right, result);

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

  @Override
  public int or(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = orIterative(node1, node2, 0);

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

      int result = -1;
      do {
        if (current1 == TRUE_NODE || current2 == TRUE_NODE) {
          result = TRUE_NODE;
        } else if (current1 == FALSE_NODE || current1 == current2) {
          result = current2;
        } else if (current2 == FALSE_NODE) {
          result = current1;
        } else {
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          if (node2var < node1var || (node2var == node1var && current2 < current1)) {
            int nodeSwap = current1;
            current1 = current2;
            current2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;

            long storeSwap = node1store;
            node1store = node2store;
            node2store = storeSwap;
          }

          if (cache.lookupOr(current1, current2)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackLeft[stackIndex] = current1;
            cacheStackRight[stackIndex] = current2;

            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = (int) getHighFromStore(node1store);
            if (node1var == node2var) {
              branchStackRight[stackIndex] = (int) getHighFromStore(node2store);
              current2 = (int) getLowFromStore(node2store);
            } else {
              branchStackRight[stackIndex] = current2;
            }
            current1 = (int) getLowFromStore(node1store);

            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
        popWorkStack(2);

        int left = cacheStackLeft[stackIndex];
        int right = cacheStackRight[stackIndex];
        cache.putOr(cacheStackHash[stackIndex], left, right, result);

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


  @Override
  public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node);
    pushToWorkStack(node);
    cache.clearVolatileCache();
    int result = restrictIterative(node, restrictedVariables, restrictedVariableValues, 0);

    popWorkStack();
    assert isWorkStackEmpty();
    return result;
  }

  private int restrictIterative(int node, BitSet restrictedVariables,
      BitSet restrictedVariableValues, int baseStackIndex) {
    int[] cacheStackHash = this.cacheStackHash;
    int[] cacheArgStack = this.cacheStackFirstArg;

    int[] branchStackParentVar = this.branchStackParentVar;
    int[] branchTaskStack = this.branchStackFirstArg;

    int initialSize = workStackSize();

    int stackIndex = baseStackIndex;
    int current = node;

    int highestRestrictedVariable = restrictedVariables.length();

    while (true) {
      assert stackIndex >= baseStackIndex;
      assert workStackSize() >= initialSize;

      int result = -1;
      do {
        if (current == TRUE_NODE || current == FALSE_NODE) {
          result = current;
        } else {
          long nodeStore = getNodeStore(current);
          int nodeVariable = (int) getVariableFromStore(nodeStore);

          if (nodeVariable > highestRestrictedVariable) {
            result = current;
          } else if (restrictedVariables.get(nodeVariable)) {
            current = restrictedVariableValues.get(nodeVariable)
                ? (int) getHighFromStore(nodeStore)
                : (int) getLowFromStore(nodeStore);
          } else if (cache.lookupVolatile(current)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheArgStack[stackIndex] = current;

            branchStackParentVar[stackIndex] = nodeVariable;
            branchTaskStack[stackIndex] = (int) getHighFromStore(nodeStore);

            current = (int) getLowFromStore(nodeStore);
            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
        popWorkStack(2);

        cache.putVolatile(cacheStackHash[stackIndex], cacheArgStack[stackIndex], result);

        if (stackIndex == baseStackIndex) {
          return result;
        }
      }
      branchStackParentVar[stackIndex] = -(parentVar + 1);
      pushToWorkStack(result);

      current = branchTaskStack[stackIndex];
      stackIndex += 1;
    }
  }


  @Override
  public int xor(int node1, int node2) {
    assert isWorkStackEmpty();

    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = xorIterative(node1, node2, 0);
    popWorkStack(2);

    assert isWorkStackEmpty();
    return result;
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

      int result = -1;
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
          long node1store = getNodeStore(current1);
          long node2store = getNodeStore(current2);
          int node1var = (int) getVariableFromStore(node1store);
          int node2var = (int) getVariableFromStore(node2store);

          if (node2var < node1var || (node2var == node1var && current2 < current1)) {
            int nodeSwap = current1;
            current1 = current2;
            current2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;

            long storeSwap = node1store;
            node1store = node2store;
            node2store = storeSwap;
          }

          if (cache.lookupXor(current1, current2)) {
            result = cache.getLookupResult();
          } else {
            cacheStackHash[stackIndex] = cache.getLookupHash();
            cacheStackLeft[stackIndex] = current1;
            cacheStackRight[stackIndex] = current2;

            branchStackParentVar[stackIndex] = node1var;
            branchStackLeft[stackIndex] = (int) getHighFromStore(node1store);
            if (node1var == node2var) {
              branchStackRight[stackIndex] = (int) getHighFromStore(node2store);
              current2 = (int) getLowFromStore(node2store);
            } else {
              branchStackRight[stackIndex] = current2;
            }
            current1 = (int) getLowFromStore(node1store);

            stackIndex += 1;
          }
        }
      } while (result == -1);

      if (stackIndex == baseStackIndex) {
        return result;
      }

      int parentVar;
      while ((parentVar = branchStackParentVar[--stackIndex]) < 0) {
        int variable = -parentVar - 1;
        result = makeNode(variable, getWorkStack(), pushToWorkStack(result));
        popWorkStack(2);

        int left = cacheStackLeft[stackIndex];
        int right = cacheStackRight[stackIndex];
        cache.putXor(cacheStackHash[stackIndex], left, right, result);

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
}
