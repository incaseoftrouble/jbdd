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
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.BiConsumer;

/* Implementation notes:
 * - Many of the methods are practically copy-paste of each other except for a few variables and
 *   corner cases, as the structure of BDD algorithms is the same for most of the operations.
 * - Due to the implementation of all operations, variable numbers increase while descending the
 *   tree of a particular node. */
@SuppressWarnings({"NumericCastThatLosesPrecision", "PMD.AvoidReassigningParameters"})
class BddRecursive extends AbstractBdd {

  BddRecursive(int nodeSize) {
    this(nodeSize, ImmutableBddConfiguration.builder().build());
  }

  BddRecursive(int nodeSize, BddConfiguration configuration) {
    super(nodeSize, configuration);
  }

  @Override
  protected void afterVariableCountChanged() {
    // empty
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
    int bitSetSize;
    int depthLimit;
    if (highestVariable >= numberOfVariables) {
      bitSetSize = numberOfVariables;
      depthLimit = Integer.MAX_VALUE;
    } else {
      bitSetSize = highestVariable;
      depthLimit = highestVariable;
    }

    BitSet path = new BitSet(bitSetSize);
    BitSet pathSupport = new BitSet(bitSetSize);
    forEachPathRecursive(node, depthLimit, path, pathSupport, action);
  }

  private void forEachPathRecursive(int node, int highestVariable,
      BitSet path, BitSet pathSupport, BiConsumer<BitSet, BitSet> action) {
    assert isNodeValid(node) || node == TRUE_NODE;

    if (node == TRUE_NODE) {
      action.accept(path, pathSupport);
      return;
    }

    long store = getNodeStore(node);
    int variable = (int) getVariableFromStore(store);
    if (variable > highestVariable) {
      action.accept(path, pathSupport);
      return;
    }
    pathSupport.set(variable);

    int lowNode = (int) getLowFromStore(store);
    if (lowNode != FALSE_NODE) {
      forEachPathRecursive(lowNode, highestVariable, path, pathSupport, action);
    }

    int highNode = (int) getHighFromStore(store);
    if (highNode != FALSE_NODE) {
      path.set(variable);
      forEachPathRecursive(highNode, highestVariable, path, pathSupport, action);
      path.clear(variable);
    }

    assert pathSupport.get(variable);
    pathSupport.clear(variable);
  }


  @Override
  public void support(int node, BitSet bitSet, int variableCutoff) {
    assert isNodeValidOrRoot(node);
    assert 0 <= variableCutoff;
    supportRecursive(node, bitSet, variableCutoff);
    unMarkAllBelow(node);
  }

  private void supportRecursive(int node, BitSet bitSet, int variableCutoff) {
    if (isNodeRoot(node)) {
      return;
    }

    long nodeStore = getNodeStore(node);

    if (isNodeStoreMarked(nodeStore)) {
      return;
    }

    int variable = (int) getVariableFromStore(nodeStore);

    if (variable < variableCutoff) {
      bitSet.set(variable);
      markNode(node);
      supportRecursive((int) getLowFromStore(nodeStore), bitSet, variableCutoff);
      supportRecursive((int) getHighFromStore(nodeStore), bitSet, variableCutoff);
    }
  }

  @Override
  public BigInteger countSatisfyingAssignments(int node) {
    if (node == FALSE_NODE) {
      return BigInteger.ZERO;
    }
    if (node == TRUE_NODE) {
      //noinspection MagicNumber
      return TWO.pow(numberOfVariables);
    }
    long nodeStore = getNodeStore(node);
    int variable = (int) getVariableFromStore(nodeStore);
    //noinspection MagicNumber
    return TWO.pow(variable).multiply(countSatisfyingAssignmentsRecursive(node));
  }

  private BigInteger countSatisfyingAssignmentsRecursive(int node) {
    if (node == FALSE_NODE) {
      return BigInteger.ZERO;
    }
    if (node == TRUE_NODE) {
      return BigInteger.ONE;
    }

    BigInteger cacheLookup = cache.lookupSatisfaction(node);
    if (cacheLookup != null) {
      return cacheLookup;
    }
    int hash = cache.getLookupHash();

    long nodeStore = getNodeStore(node);
    int nodeVar = (int) getVariableFromStore(nodeStore);

    BigInteger lowCount = doCountSatisfyingAssignments((int) getLowFromStore(nodeStore), nodeVar);
    BigInteger highCount = doCountSatisfyingAssignments((int) getHighFromStore(nodeStore), nodeVar);

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
    long subStore = getNodeStore(subNode);
    int subVar = (int) getVariableFromStore(subStore);
    BigInteger multiplier = TWO.pow(subVar - currentVar - 1);
    return multiplier.multiply(countSatisfyingAssignmentsRecursive(subNode));
  }


  @Override
  public int conjunction(int... variables) {
    int node = TRUE_NODE;
    for (int variable : variables) {
      // Variable nodes are saturated, no need to guard them
      pushToWorkStack(node);
      node = andRecursive(node, variableNodes[variable]);
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
      node = andRecursive(node, variableNodes[currentVariableNumber]);
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
      node = orRecursive(node, variableNodes[variable]);
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
      node = orRecursive(node, variableNodes[currentVariableNumber]);
      popWorkStack();
    }
    return node;
  }

  @Override
  public int and(int node1, int node2) {
    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = andRecursive(node1, node2);
    popWorkStack(2);
    return result;
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

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node2var < node1var || (node2var == node1var && node2 < node1)) {
      int nodeSwap = node1;
      node1 = node2;
      node2 = nodeSwap;

      int varSwap = node1var;
      node1var = node2var;
      node2var = varSwap;

      long storeSwap = node1store;
      node1store = node2store;
      node2store = storeSwap;
    }

    if (cache.lookupAnd(node1, node2)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    int lowNode;
    int highNode;
    if (node1var == node2var) {
      lowNode = andRecursive((int) getLowFromStore(node1store), (int) getLowFromStore(node2store));
      pushToWorkStack(lowNode);
      highNode = andRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store));
      pushToWorkStack(highNode);
    } else { // v < getVariable(node2)
      lowNode = pushToWorkStack(andRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(andRecursive((int) getHighFromStore(node1store), node2));
    }
    int resultNode = makeNode(node1var, lowNode, highNode);
    popWorkStack(2);
    cache.putAnd(hash, node1, node2, resultNode);
    return resultNode;
  }


  @Override
  public int compose(int node, int[] variableNodes) {
    assert variableNodes.length <= numberOfVariables;

    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }

    // Guard the elements and replace -1 by actual variable reference
    pushToWorkStack(node);
    int workStackCount = 1;
    for (int i = 0; i < variableNodes.length; i++) {
      if (variableNodes[i] == -1) {
        variableNodes[i] = this.variableNodes[i];
      } else {
        assert isNodeValidOrRoot(variableNodes[i]);
        if (!isNodeSaturated(variableNodes[i])) {
          pushToWorkStack(variableNodes[i]);
          workStackCount++;
        }
      }
    }

    int highestReplacedVariable = variableNodes.length - 1;
    // Optimise the replacement array
    for (int i = variableNodes.length - 1; i >= 0; i--) {
      if (variableNodes[i] != this.variableNodes[i]) {
        highestReplacedVariable = i;
        break;
      }
    }
    if (highestReplacedVariable == -1) {
      return node;
    }

    int hash;
    if (getConfiguration().useGlobalComposeCache()) {
      if (cache.lookupCompose(node, variableNodes)) {
        return cache.getLookupResult();
      }
      hash = cache.getLookupHash();
    } else {
      hash = -1;
    }

    cache.clearVolatileCache();
    int result = composeRecursive(node, variableNodes, highestReplacedVariable);
    popWorkStack(workStackCount);
    if (getConfiguration().useGlobalComposeCache()) {
      cache.putCompose(hash, node, variableNodes, result);
    }
    return result;
  }

  private int composeRecursive(int node, int[] variableNodes, int highestReplacedVariable) {
    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }

    long nodeStore = getNodeStore(node);
    int nodeVariable = (int) getVariableFromStore(nodeStore);
    // The tree is sorted (variable 0 on top), hence if the algorithm descended "far enough" there
    // will not be any replacements.
    if (nodeVariable > highestReplacedVariable) {
      return node;
    }

    if (cache.lookupVolatile(node)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();

    int variableReplacementNode = variableNodes[nodeVariable];
    int resultNode;
    // Short-circuit constant replacements.
    if (variableReplacementNode == TRUE_NODE) {
      resultNode = composeRecursive((int) getHighFromStore(nodeStore), variableNodes,
          highestReplacedVariable);
    } else if (variableReplacementNode == FALSE_NODE) {
      resultNode = composeRecursive((int) getLowFromStore(nodeStore), variableNodes,
          highestReplacedVariable);
    } else {
      int lowCompose = pushToWorkStack(composeRecursive((int) getLowFromStore(nodeStore),
          variableNodes, highestReplacedVariable));
      int highCompose = pushToWorkStack(composeRecursive((int) getHighFromStore(nodeStore),
          variableNodes, highestReplacedVariable));
      resultNode = ifThenElseRecursive(variableReplacementNode, highCompose, lowCompose);
      popWorkStack(2);
    }
    cache.putVolatile(hash, node, resultNode);
    return resultNode;
  }


  @Override
  public int equivalence(int node1, int node2) {
    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int ret = equivalenceRecursive(node1, node2);
    popWorkStack(2);
    return ret;
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

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node2var < node1var || (node2var == node1var && node2 < node1)) {
      int nodeSwap = node1;
      node1 = node2;
      node2 = nodeSwap;

      int varSwap = node1var;
      node1var = node2var;
      node2var = varSwap;

      long storeSwap = node1store;
      node1store = node2store;
      node2store = storeSwap;
    }

    if (cache.lookupEquivalence(node1, node2)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    int lowNode;
    int highNode;
    if (node1var == node2var) {
      lowNode = equivalenceRecursive((int) getLowFromStore(node1store),
          (int) getLowFromStore(node2store));
      pushToWorkStack(lowNode);
      highNode = equivalenceRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store));
      pushToWorkStack(highNode);
    } else { // v < getVariable(node2)
      lowNode = pushToWorkStack(equivalenceRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(equivalenceRecursive((int) getHighFromStore(node1store), node2));
    }
    int resultNode = makeNode(node1var, lowNode, highNode);
    popWorkStack(2);
    cache.putEquivalence(hash, node1, node2, resultNode);
    return resultNode;
  }


  @Override
  public int exists(int node, BitSet quantifiedVariables) {
    return getConfiguration().useShannonExists()
        ? existsShannon(node, quantifiedVariables)
        : existsSelfSubstitution(node, quantifiedVariables);
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
          pushToWorkStack(composeRecursive(quantifiedNode, replacementArray, i));
      cache.clearVolatileCache();
      // compute f(x, f(x, 1))
      quantifiedNode = composeRecursive(quantifiedNode, replacementArray, i);
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
    int result = existsShannonRecursive(node, quantifiedVariablesConjunction);
    popWorkStack(2);
    return result;
  }

  private int existsShannonRecursive(int node, int quantifiedVariableCube) {
    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }
    if (quantifiedVariableCube == TRUE_NODE) {
      return node;
    }

    long nodeStore = getNodeStore(node);
    int nodeVariable = (int) getVariableFromStore(nodeStore);

    int currentCubeNode = quantifiedVariableCube;
    long currentCubeNodeStore = getNodeStore(currentCubeNode);
    int currentCubeNodeVariable = (int) getVariableFromStore(currentCubeNodeStore);
    while (currentCubeNodeVariable < nodeVariable) {
      currentCubeNode = (int) getHighFromStore(currentCubeNodeStore);
      if (currentCubeNode == TRUE_NODE) {
        // No more variables to project
        return node;
      }
      currentCubeNodeStore = getNodeStore(currentCubeNode);
      currentCubeNodeVariable = (int) getVariableFromStore(currentCubeNodeStore);
    }

    if (isVariableOrNegatedStore(nodeStore)) {
      if (nodeVariable == currentCubeNodeVariable) {
        return TRUE_NODE;
      }
      return node;
    }

    if (cache.lookupExists(node, currentCubeNode)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();

    // The "root" of the cube is guarded in the main invocation - no need to guard its descendants
    int lowExists = pushToWorkStack(existsShannonRecursive((int) getLowFromStore(nodeStore),
        currentCubeNode));
    int highExists = pushToWorkStack(existsShannonRecursive(
        (int) getHighFromStore(nodeStore), currentCubeNode));
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
    assert isNodeValidOrRoot(ifNode) && isNodeValidOrRoot(thenNode) && isNodeValidOrRoot(elseNode);
    pushToWorkStack(ifNode);
    pushToWorkStack(thenNode);
    pushToWorkStack(elseNode);
    int result = ifThenElseRecursive(ifNode, thenNode, elseNode);
    popWorkStack(3);
    return result;
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
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    long ifStore = getNodeStore(ifNode);
    long thenStore = getNodeStore(thenNode);
    long elseStore = getNodeStore(elseNode);

    int ifVar = (int) getVariableFromStore(ifStore);
    int thenVar = (int) getVariableFromStore(thenStore);
    int elseVar = (int) getVariableFromStore(elseStore);

    int minVar = Math.min(ifVar, Math.min(thenVar, elseVar));
    int ifLowNode;
    int ifHighNode;

    if (ifVar == minVar) {
      ifLowNode = (int) getLowFromStore(ifStore);
      ifHighNode = (int) getHighFromStore(ifStore);
    } else {
      ifLowNode = ifNode;
      ifHighNode = ifNode;
    }

    int thenHighNode;
    int thenLowNode;
    if (thenVar == minVar) {
      thenLowNode = (int) getLowFromStore(thenStore);
      thenHighNode = (int) getHighFromStore(thenStore);
    } else {
      thenLowNode = thenNode;
      thenHighNode = thenNode;
    }

    int elseHighNode;
    int elseLowNode;
    if (elseVar == minVar) {
      elseLowNode = (int) getLowFromStore(elseStore);
      elseHighNode = (int) getHighFromStore(elseStore);
    } else {
      elseLowNode = elseNode;
      elseHighNode = elseNode;
    }

    int lowNode = pushToWorkStack(ifThenElseRecursive(ifLowNode, thenLowNode, elseLowNode));
    int highNode =
        pushToWorkStack(ifThenElseRecursive(ifHighNode, thenHighNode, elseHighNode));
    int result = makeNode(minVar, lowNode, highNode);
    popWorkStack(2);
    cache.putIfThenElse(hash, ifNode, thenNode, elseNode, result);
    return result;
  }


  @Override
  public int implication(int node1, int node2) {
    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int ret = implicationRecursive(node1, node2);
    popWorkStack(2);
    return ret;
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
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    int lowNode;
    int highNode;
    int decisionVar;
    if (node1var > node2var) {
      lowNode = pushToWorkStack(implicationRecursive(node1, (int) getLowFromStore(node2store)));
      highNode = pushToWorkStack(implicationRecursive(node1, (int) getHighFromStore(node2store)));
      decisionVar = node2var;
    } else if (node1var == node2var) {
      lowNode = pushToWorkStack(implicationRecursive((int) getLowFromStore(node1store),
          (int) getLowFromStore(node2store)));
      highNode = pushToWorkStack(implicationRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store)));
      decisionVar = node1var;
    } else {
      lowNode = pushToWorkStack(implicationRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(implicationRecursive((int) getHighFromStore(node1store), node2));
      decisionVar = node1var;
    }
    int resultNode = makeNode(decisionVar, lowNode, highNode);
    popWorkStack(2);
    cache.putImplication(hash, node1, node2, resultNode);
    return resultNode;
  }


  @Override
  public boolean implies(int node1, int node2) {
    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    return impliesRecursive(node1, node2);
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
      return cache.getLookupResult() == TRUE_NODE;
    }
    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node1var == node2var) {
      return impliesRecursive((int) getLowFromStore(node1store),
          (int) getLowFromStore(node2store))
          && impliesRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store));
    } else if (node1var < node2var) {
      return impliesRecursive((int) getLowFromStore(node1store), node2)
          && impliesRecursive((int) getHighFromStore(node1store), node2);
    } else {
      return impliesRecursive(node1, (int) getLowFromStore(node2store))
          && impliesRecursive(node1, (int) getHighFromStore(node2store));
    }
  }


  @Override
  public int not(int node) {
    assert isNodeValidOrRoot(node);
    pushToWorkStack(node);
    int ret = notRecursive(node);
    popWorkStack();
    return ret;
  }

  private int notRecursive(int node) {
    if (node == FALSE_NODE) {
      return TRUE_NODE;
    }
    if (node == TRUE_NODE) {
      return FALSE_NODE;
    }

    if (cache.lookupNot(node)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    long nodeStore = getNodeStore(node);

    int lowNode = pushToWorkStack(notRecursive((int) getLowFromStore(nodeStore)));
    int highNode = pushToWorkStack(notRecursive((int) getHighFromStore(nodeStore)));
    int resultNode = makeNode((int) getVariableFromStore(nodeStore), lowNode, highNode);
    popWorkStack(2);
    cache.putNot(hash, node, resultNode);
    return resultNode;
  }


  @Override
  public int notAnd(int node1, int node2) {
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int ret = notAndRecursive(node1, node2);
    popWorkStack(2);
    return ret;
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

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node2var < node1var || (node2var == node1var && node2 < node1)) {
      int nodeSwap = node1;
      node1 = node2;
      node2 = nodeSwap;

      int varSwap = node1var;
      node1var = node2var;
      node2var = varSwap;

      long storeSwap = node1store;
      node1store = node2store;
      node2store = storeSwap;
    }

    if (cache.lookupNAnd(node1, node2)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    int lowNode;
    int highNode;
    if (node1var == node2var) {
      lowNode = notAndRecursive((int) getLowFromStore(node1store),
          (int) getLowFromStore(node2store));
      pushToWorkStack(lowNode);
      highNode = notAndRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store));
      pushToWorkStack(highNode);
    } else { // v < getVariable(node2)
      lowNode = pushToWorkStack(notAndRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(notAndRecursive((int) getHighFromStore(node1store), node2));
    }
    int resultNode = makeNode(node1var, lowNode, highNode);
    popWorkStack(2);
    cache.putNAnd(hash, node1, node2, resultNode);
    return resultNode;
  }


  @Override
  public int or(int node1, int node2) {
    assert isNodeValidOrRoot(node1) && isNodeValidOrRoot(node2);
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int result = orRecursive(node1, node2);
    popWorkStack(2);
    return result;
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

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node2var < node1var || (node2var == node1var && node2 < node1)) {
      int nodeSwap = node1;
      node1 = node2;
      node2 = nodeSwap;

      int varSwap = node1var;
      node1var = node2var;
      node2var = varSwap;

      long storeSwap = node1store;
      node1store = node2store;
      node2store = storeSwap;
    }

    if (cache.lookupOr(node1, node2)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    int lowNode;
    int highNode;
    if (node1var == node2var) {
      lowNode = orRecursive((int) getLowFromStore(node1store), (int) getLowFromStore(node2store));
      pushToWorkStack(lowNode);
      highNode = pushToWorkStack(orRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store)));
    } else { // v < getVariable(node2)
      lowNode = pushToWorkStack(orRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(orRecursive((int) getHighFromStore(node1store), node2));
    }
    int resultNode = makeNode(node1var, lowNode, highNode);
    popWorkStack(2);
    cache.putOr(hash, node1, node2, resultNode);
    return resultNode;
  }


  @Override
  public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
    assert isNodeValidOrRoot(node);
    pushToWorkStack(node);
    cache.clearVolatileCache();
    int resultNode = restrictRecursive(node, restrictedVariables, restrictedVariableValues);
    popWorkStack();
    return resultNode;
  }

  private int restrictRecursive(int node, BitSet restrictedVariables,
      BitSet restrictedVariableValues) {
    if (node == TRUE_NODE || node == FALSE_NODE) {
      return node;
    }

    long nodeStore = getNodeStore(node);
    int nodeVariable = (int) getVariableFromStore(nodeStore);
    // The tree is sorted (variable 0 on top), hence if the algorithm descended far enough there
    // will not be any replacements.
    if (nodeVariable >= restrictedVariables.length()) {
      return node;
    }

    if (cache.lookupVolatile(node)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();

    int resultNode;
    if (restrictedVariables.get(nodeVariable)) {
      if (restrictedVariableValues.get(nodeVariable)) {
        resultNode = restrictRecursive((int) getHighFromStore(nodeStore), restrictedVariables,
            restrictedVariableValues);
      } else {
        resultNode = restrictRecursive((int) getLowFromStore(nodeStore), restrictedVariables,
            restrictedVariableValues);
      }
    } else {
      int lowRestrict = pushToWorkStack(restrictRecursive((int) getLowFromStore(nodeStore),
          restrictedVariables, restrictedVariableValues));
      int highRestrict = pushToWorkStack(restrictRecursive((int) getHighFromStore(nodeStore),
          restrictedVariables, restrictedVariableValues));
      resultNode = makeNode(nodeVariable, lowRestrict, highRestrict);
      popWorkStack(2);
    }
    cache.putVolatile(hash, node, resultNode);
    return resultNode;
  }


  @Override
  public int xor(int node1, int node2) {
    pushToWorkStack(node1);
    pushToWorkStack(node2);
    int ret = xorRecursive(node1, node2);
    popWorkStack(2);
    return ret;
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

    long node1store = getNodeStore(node1);
    long node2store = getNodeStore(node2);
    int node1var = (int) getVariableFromStore(node1store);
    int node2var = (int) getVariableFromStore(node2store);

    if (node2var < node1var || (node2var == node1var && node2 < node1)) {
      int nodeSwap = node1;
      node1 = node2;
      node2 = nodeSwap;

      int varSwap = node1var;
      node1var = node2var;
      node2var = varSwap;

      long storeSwap = node1store;
      node1store = node2store;
      node2store = storeSwap;
    }

    if (cache.lookupXor(node1, node2)) {
      return cache.getLookupResult();
    }
    int hash = cache.getLookupHash();
    int lowNode;
    int highNode;
    if (node1var == node2var) {
      lowNode = xorRecursive((int) getLowFromStore(node1store), (int) getLowFromStore(node2store));
      pushToWorkStack(lowNode);
      highNode = pushToWorkStack(xorRecursive((int) getHighFromStore(node1store),
          (int) getHighFromStore(node2store)));
    } else { // v < getVariable(node2)
      lowNode = pushToWorkStack(xorRecursive((int) getLowFromStore(node1store), node2));
      highNode = pushToWorkStack(xorRecursive((int) getHighFromStore(node1store), node2));
    }
    int resultNode = makeNode(node1var, lowNode, highNode);
    popWorkStack(2);
    cache.putXor(hash, node1, node2, resultNode);
    return resultNode;
  }
}
