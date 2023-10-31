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

import static de.tum.in.jbdd.Util.min;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
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
final class BddImpl extends NodeTable implements BddWithTestInterface {
    private static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private static final int TRUE_NODE = -1;
    private static final int FALSE_NODE = -2;

    private final BddCache cache;
    private int numberOfVariables;
    private int[] variableNodes;

    /* Low and high successors of each node */
    private int[] tree;

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
    // private int[] markStack = EMPTY_INT_ARRAY;

    private int hashLookupLow = NOT_A_NODE;
    private int hashLookupHigh = NOT_A_NODE;

    BddImpl(boolean iterative, BddConfiguration configuration) {
        super(
                configuration.initialSize(),
                configuration.useGarbageCollection() ? configuration.minimumFreeNodePercentageAfterGc() : 1.0,
                configuration.growthFactor());
        this.iterative = iterative;

        tree = new int[2 * tableSize()];
        cache = new BddCache(this, configuration);
        variableNodes = new int[32];
        numberOfVariables = 0;
    }

    //
    //    private int unMarkAllBelowIterative(int node) {
    //        int[] nodes = this.nodes;
    //        int[] tree = this.tree;
    //        int[] stack = this.markStack;
    //
    //        int stackIndex = 0;
    //        int unmarkedCount = 0;
    //        int current = node;
    //        while (true) {
    //            while (!isLeaf(current)) {
    //                int metadata = nodes[current];
    //                int unmarkedData = dataClearMark(metadata);
    //
    //                if (metadata == unmarkedData) {
    //                    // Node was not marked
    //                    break;
    //                }
    //
    //                nodes[current] = unmarkedData;
    //                unmarkedCount++;
    //
    //                stack[stackIndex] = tree[2 * current + 1];
    //                stackIndex += 1;
    //                current = tree[2 * current];
    //            }
    //
    //            if (stackIndex == 0) {
    //                break;
    //            }
    //            stackIndex -= 1;
    //            current = stack[stackIndex];
    //        }
    //        return unmarkedCount;
    //    }
    //
    //
    //    private int markAllBelowIterative(int node) {
    //        int[] tree = this.tree;
    //        int[] nodes = this.nodes;
    //        int[] stack = this.markStack;
    //
    //        int stackIndex = 0;
    //        int unmarkedCount = 0;
    //        int current = node;
    //        while (true) {
    //            while (!isLeaf(current)) {
    //                int metadata = nodes[current];
    //                int markedData = dataSetMark(metadata);
    //
    //                if (metadata == markedData) {
    //                    // Node was marked
    //                    break;
    //                }
    //
    //                nodes[current] = markedData;
    //                unmarkedCount++;
    //
    //                stack[stackIndex] = tree[2 * current + 1];
    //                stackIndex += 1;
    //
    //                current = tree[2 * current];
    //            }
    //
    //            if (stackIndex == 0) {
    //                break;
    //            }
    //            stackIndex -= 1;
    //            current = stack[stackIndex];
    //        }
    //        return unmarkedCount;
    //    }

    // Nodes

    @Override
    protected boolean checkLookupChildrenMatch(int lookup) {
        int[] tree = this.tree;
        return tree[lookup * 2] == hashLookupLow && tree[lookup * 2 + 1] == hashLookupHigh;
    }

    private int makeNode(int variable, int low, int high) {
        assert 0 <= variable;
        assert (isLeaf(low) || variable < variableOf(low));
        assert (isLeaf(high) || variable < variableOf(high));

        if (low == high) {
            return low;
        }

        hashLookupLow = low;
        hashLookupHigh = high;
        int freeNode = findOrCreateNode(variable, hashCode(variable, low, high));

        this.tree[2 * freeNode] = low;
        this.tree[2 * freeNode + 1] = high;
        assert hashCode(variable, low, high) == hashCode(freeNode, variable);
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
    public boolean isLeaf(int node) {
        assert -2 <= node && node < tableSize();
        return node < 0;
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
            int growth = Math.max(variableNodes.length * 2, newSize);
            variableNodes = Arrays.copyOf(variableNodes, growth);
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

    @Override
    public boolean evaluate(int node, boolean[] assignment) {
        int current = node;
        while (current >= FIRST_NODE) {
            assert isNodeValid(current);
            current = assignment[variableOf(current)] ? high(current) : low(current);
        }
        assert isLeaf(current);
        return current == TRUE_NODE;
    }

    @Override
    public boolean evaluate(int node, BitSet assignment) {
        int current = node;
        while (current >= FIRST_NODE) {
            assert isNodeValid(current);
            current = assignment.get(variableOf(current)) ? high(current) : low(current);
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
                int variable = variableOf(currentNode);

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
            return new PowerIteratorBitSet(numberOfVariables);
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
            return new PowerIteratorBitSet(support);
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
                int variable = variableOf(current);
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

        int variable = variableOf(node);
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

    /*
    private void supportIterative(int node, BitSet bitSet, BitSet filter, int depthLimit, int baseStackIndex) {
        int[] branchStackNode = this.branchStackFirstArg;

        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            while (!isLeaf(current)) {
                int metadata = nodes[current];
                assert dataIsValid(metadata);
                int variable = dataGetVariable(metadata);
                if (variable >= depthLimit) {
                    break;
                }
                int markedData = dataSetMark(metadata);
                if (metadata == markedData) {
                    break;
                }
                nodes[current] = markedData;

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
    } */

    @Override
    public BigInteger countSatisfyingAssignments(int node) {
        if (node == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (node == TRUE_NODE) {
            return TWO.pow(numberOfVariables);
        }
        int variable = variableOf(node);
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
                    nodeVar = variableOf(current);

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

        int nodeVar = variableOf(node);
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
        BigInteger multiplier = TWO.pow(variableOf(subNode) - currentVar - 1);
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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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

        // Guard the elements and replace NOT_A_NODE by actual variable reference
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

    private int composeIterative(int node, int[] replacement, int highestReplacedVariable) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheArgStack = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchTaskStack = this.branchStackFirstArg;

        int stackIndex = 0;
        int current = node;
        while (true) {
            assert stackIndex >= 0;

            int result = NOT_A_NODE;
            do {
                if (current == TRUE_NODE || current == FALSE_NODE) {
                    result = current;
                } else {
                    int nodeVariable = variableOf(current);

                    if (nodeVariable > highestReplacedVariable) {
                        result = current;
                    } else {
                        int replacementNode = replacement[nodeVariable];

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
                int replacementNode = replacement[variable];
                int currentHash = cacheStackHash[stackIndex];
                int currentNode = cacheArgStack[stackIndex];
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

    private int composeRecursive(int node, int[] replacement, int highestReplacedVariable) {
        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }

        int nodeVariable = variableOf(node);
        if (nodeVariable > highestReplacedVariable) {
            return node;
        }

        if (cache.lookupCompose(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int variableReplacementNode = replacement[nodeVariable];
        int resultNode;
        // Short-circuit constant replacements.
        if (variableReplacementNode == TRUE_NODE) {
            resultNode = composeRecursive(high(node), replacement, highestReplacedVariable);
        } else if (variableReplacementNode == FALSE_NODE) {
            resultNode = composeRecursive(low(node), replacement, highestReplacedVariable);
        } else {
            int lowCompose = pushToWorkStack(composeRecursive(low(node), replacement, highestReplacedVariable));
            int highCompose = pushToWorkStack(composeRecursive(high(node), replacement, highestReplacedVariable));
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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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
        assert quantifiedVariables.length() <= numberOfVariables;
        if (isLeaf(node) || quantifiedVariables.isEmpty()) {
            return node;
        }
        if (isVariableOrNegated(node)) {
            return quantifiedVariables.get(variableOf(node)) ? TRUE_NODE : node;
        }
        int cardinality = quantifiedVariables.cardinality();
        if (cardinality == numberOfVariables) {
            return TRUE_NODE;
        }

        cache.initExists(quantifiedVariables);
        int[] quantifiedVariableArray = BitSets.toArray(quantifiedVariables);
        pushToWorkStack(node);
        int result = iterative
                ? existsIterative(node, quantifiedVariableArray, 0)
                : existsRecursive(node, 0, quantifiedVariableArray);
        popWorkStack(1);
        assert isWorkStackEmpty();
        return result;
    }

    private int existsIterative(int node, int[] quantifiedVariables, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackArg = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackArg = this.branchStackFirstArg;
        int[] branchStackVariableIndex = this.branchStackSecondArg;

        int currentIndex = 0;
        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            //noinspection LabeledStatement
            loop:
            do {
                if (current == TRUE_NODE || current == FALSE_NODE) {
                    result = current;
                } else {
                    int nodeVariable = variableOf(current);
                    int currentVariable = quantifiedVariables[currentIndex];
                    while (currentVariable < nodeVariable) {
                        currentIndex += 1;
                        if (currentIndex == quantifiedVariables.length) {
                            // No more variables to project
                            result = current;
                            //noinspection BreakStatementWithLabel
                            break loop;
                        }
                        currentVariable = quantifiedVariables[currentIndex];
                    }

                    if (isVariableOrNegated(current)) {
                        result = nodeVariable == currentVariable ? TRUE_NODE : current;
                    } else if (cache.lookupExists(current)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackArg[stackIndex] = current;

                        branchStackParentVar[stackIndex] = nodeVariable;
                        branchStackArg[stackIndex] = high(current);
                        branchStackVariableIndex[stackIndex] = currentIndex;

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

                int quantifiedVariable = quantifiedVariables[branchStackVariableIndex[stackIndex]];
                if (quantifiedVariable > variable) {
                    // The variable of this node is smaller than the variable looked for - only propagate the
                    // quantification downward
                    result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                } else {
                    // nodeVariable == nextVariable, i.e. "quantify out" the current node.
                    result = orIterative(peekWorkStack(), pushToWorkStack(result), stackIndex);
                }
                popWorkStack(2);
                cache.putExists(currentHash, currentNode, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            currentIndex = branchStackVariableIndex[stackIndex];
            current = branchStackArg[stackIndex];
            stackIndex += 1;
        }
    }

    private int existsRecursive(int node, int currentIndex, int[] quantifiedVariables) {
        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }
        if (currentIndex == quantifiedVariables.length) {
            return node;
        }

        int nodeVariable = variableOf(node);
        int quantifiedVariable = quantifiedVariables[currentIndex];

        while (quantifiedVariable < nodeVariable) {
            currentIndex += 1;
            if (currentIndex == quantifiedVariables.length) {
                return node;
            }
            quantifiedVariable = quantifiedVariables[currentIndex];
        }

        if (isVariableOrNegated(node)) {
            return nodeVariable == quantifiedVariable ? TRUE_NODE : node;
        }

        if (cache.lookupExists(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int lowExists = pushToWorkStack(existsRecursive(low(node), currentIndex, quantifiedVariables));
        int highExists = pushToWorkStack(existsRecursive(high(node), currentIndex, quantifiedVariables));
        int resultNode;
        if (quantifiedVariable > nodeVariable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            resultNode = makeNode(nodeVariable, lowExists, highExists);
        } else {
            // nodeVariable == nextVariable, i.e. "quantify out" the current node.
            resultNode = orRecursive(lowExists, highExists);
        }
        popWorkStack(2);
        cache.putExists(hash, node, resultNode);
        return resultNode;
    }

    @Override
    public int forall(int node, BitSet quantifiedVariables) {
        assert isWorkStackEmpty();
        assert quantifiedVariables.length() <= numberOfVariables;
        if (isLeaf(node) || quantifiedVariables.isEmpty()) {
            return node;
        }
        if (isVariableOrNegated(node)) {
            return quantifiedVariables.get(variableOf(node)) ? FALSE_NODE : node;
        }
        int cardinality = quantifiedVariables.cardinality();
        if (cardinality == numberOfVariables) {
            assert node != TRUE_NODE;
            return FALSE_NODE;
        }

        cache.initForall(quantifiedVariables);
        int[] quantifiedVariableArray = BitSets.toArray(quantifiedVariables);
        pushToWorkStack(node);
        int result = iterative
                ? forallIterative(node, quantifiedVariableArray, 0)
                : forallRecursive(node, 0, quantifiedVariableArray);
        popWorkStack(1);
        assert isWorkStackEmpty();
        return result;
    }

    private int forallIterative(int node, int[] quantifiedVariables, int baseStackIndex) {
        int[] cacheStackHash = this.cacheStackHash;
        int[] cacheStackArg = this.cacheStackFirstArg;
        int[] branchStackParentVar = this.branchStackParentVar;
        int[] branchStackArg = this.branchStackFirstArg;
        int[] branchStackVariableIndex = this.branchStackSecondArg;

        int currentIndex = 0;
        int stackIndex = baseStackIndex;
        int current = node;
        while (true) {
            assert stackIndex >= baseStackIndex;

            int result = NOT_A_NODE;
            //noinspection LabeledStatement
            loop:
            do {
                if (current == TRUE_NODE || current == FALSE_NODE) {
                    result = current;
                } else {
                    int nodeVariable = variableOf(current);
                    int currentVariable = quantifiedVariables[currentIndex];
                    while (currentVariable < nodeVariable) {
                        currentIndex += 1;
                        if (currentIndex == quantifiedVariables.length) {
                            // No more variables to project
                            result = current;
                            //noinspection BreakStatementWithLabel
                            break loop;
                        }
                        currentVariable = quantifiedVariables[currentIndex];
                    }

                    if (isVariableOrNegated(current)) {
                        result = nodeVariable == currentVariable ? FALSE_NODE : current;
                    } else if (cache.lookupForall(current)) {
                        result = cache.lookupResult();
                    } else {
                        cacheStackHash[stackIndex] = cache.lookupHash();
                        cacheStackArg[stackIndex] = current;

                        branchStackParentVar[stackIndex] = nodeVariable;
                        branchStackArg[stackIndex] = high(current);
                        branchStackVariableIndex[stackIndex] = currentIndex;

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

                int quantifiedVariable = quantifiedVariables[branchStackVariableIndex[stackIndex]];
                if (quantifiedVariable > variable) {
                    // The variable of this node is smaller than the variable looked for - only propagate the
                    // quantification downward
                    result = makeNode(variable, peekWorkStack(), pushToWorkStack(result));
                } else {
                    // nodeVariable == nextVariable, i.e. "quantify out" the current node.
                    result = andIterative(peekWorkStack(), pushToWorkStack(result), stackIndex);
                }
                popWorkStack(2);
                cache.putForall(currentHash, currentNode, result);

                if (stackIndex == baseStackIndex) {
                    return result;
                }
            }
            branchStackParentVar[stackIndex] = -(parentVar + 1);
            pushToWorkStack(result);

            currentIndex = branchStackVariableIndex[stackIndex];
            current = branchStackArg[stackIndex];
            stackIndex += 1;
        }
    }

    private int forallRecursive(int node, int currentIndex, int[] quantifiedVariables) {
        if (node == TRUE_NODE || node == FALSE_NODE) {
            return node;
        }
        if (currentIndex == quantifiedVariables.length) {
            return node;
        }

        int nodeVariable = variableOf(node);
        int quantifiedVariable = quantifiedVariables[currentIndex];

        while (quantifiedVariable < nodeVariable) {
            currentIndex += 1;
            if (currentIndex == quantifiedVariables.length) {
                return node;
            }
            quantifiedVariable = quantifiedVariables[currentIndex];
        }

        if (isVariableOrNegated(node)) {
            return nodeVariable == quantifiedVariable ? FALSE_NODE : node;
        }

        if (cache.lookupForall(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int lowForall = pushToWorkStack(forallRecursive(low(node), currentIndex, quantifiedVariables));
        int highForall = pushToWorkStack(forallRecursive(high(node), currentIndex, quantifiedVariables));
        int resultNode;
        if (quantifiedVariable > nodeVariable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            resultNode = makeNode(nodeVariable, lowForall, highForall);
        } else {
            // nodeVariable == nextVariable, i.e. "quantify out" the current node.
            resultNode = andRecursive(lowForall, highForall);
        }
        popWorkStack(2);
        cache.putForall(hash, node, resultNode);
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
                    int ifVar = variableOf(currentIf);
                    int thenVar = variableOf(currentThen);
                    int elseVar = variableOf(currentElse);

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
        int ifVar = variableOf(ifNode);
        int thenVar = variableOf(thenNode);
        int elseVar = variableOf(elseNode);

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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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

                int node1var = variableOf(current1);
                int node2var = variableOf(current2);

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
        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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
                    branchStackParentVar[stackIndex] = variableOf(current);
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
        int resultNode = makeNode(variableOf(node), lowNode, highNode);
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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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
                    int node1var = variableOf(current1);
                    int node2var = variableOf(current2);

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

        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

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

        // markStack = new int[minimumSize];
    }

    @Override
    public boolean check() {
        return super.check();
    }

    @Override
    public void invalidateCache() {
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

    @Override
    protected void onGarbageCollection() {
        cache.invalidate();
    }

    @Override
    protected void onTableResize(int newSize) {
        tree = Arrays.copyOf(tree, newSize * 2);
    }

    @Override
    protected int hashCode(int node, int variable) {
        return hashCode(variable, tree[2 * node], tree[2 * node + 1]);
    }

    private static int hashCode(int variable, int low, int high) {
        return variable + low + high;
    }

    @Override
    protected void forEachChild(int node, IntConsumer action) {
        action.accept(tree[2 * node]);
        action.accept(tree[2 * node + 1]);
    }

    @Override
    protected int sumEachBelow(int node, IntUnaryOperator operator) {
        return operator.applyAsInt(tree[2 * node]) + operator.applyAsInt(tree[2 * node + 1]);
    }

    @Override
    protected boolean allMatchBelow(int node, IntPredicate predicate) {
        return predicate.test(tree[2 * node]) && predicate.test(tree[2 * node + 1]);
    }

    @Override
    protected Node node(int node) {
        return new BinaryNode(variableOf(node), tree[2 * node], tree[2 * node + 1]);
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
        private int highestSwitchableVariable = 0;
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
            rootVariable = bdd.variableOf(node);
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
                for (int var = support.nextSetBit(0); var >= 0; var = support.nextSetBit(var + 1)) {
                    // Strategy: Perform binary addition on the NON_PATH_NODEs over the support
                    // The tricky bit is to determine whether there is a "next element": Either there is
                    // another real path in the BDD or there is some variable which we still can flip to 1

                    if (path[var] == NON_PATH_NODE) {
                        if (assignment.get(var)) {
                            assignment.clear(var);
                            clearedAny = true;
                        } else {
                            assignment.set(var);
                            if (hasNextPath || clearedAny) {
                                hasNextAssignment = true;
                            } else {
                                hasNextAssignment = false;

                                // TODO This should be constant time to determine?
                                // TODO This only needs to run if we set the first non-path variable to 1
                                for (int i = support.nextSetBit(var + 1); i >= 0; i = support.nextSetBit(i + 1)) {
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
                int branchVar = leafNodeVariable;
                while (assignment.get(branchVar) || bdd.high(currentNode) == FALSE_NODE) {
                    assert path[branchVar] != NON_PATH_NODE;

                    // This node does not give us another branch, backtrack over the path until we get to
                    // the next element of the path
                    // TODO Could track the previous path element in int[]
                    do {
                        branchVar = support.previousSetBit(branchVar - 1);
                        if (branchVar == -1) {
                            throw new NoSuchElementException("No next element");
                        }
                    } while (path[branchVar] == NON_PATH_NODE);
                    currentNode = path[branchVar];
                }
                assert !assignment.get(branchVar);
                assert leafNodeVariable >= highestSwitchableVariable;
                assert bdd.variableOf(currentNode) == branchVar;

                // currentNode is the lowest node we can switch high; set the value and descend the tree
                assignment.clear(branchVar + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                leafNodeVariable = branchVar;

                assignment.set(branchVar);
                assert path[branchVar] == currentNode;
                currentNode = bdd.high(currentNode);
                assert currentNode != FALSE_NODE;

                // We flipped the candidate for low->high transition, clear this information
                if (highestSwitchableVariable == branchVar) {
                    highestSwitchableVariable = -1;
                }
            }

            // Situation: The currentNode valuation was just flipped to one or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (currentNode != TRUE_NODE) {
                assert currentNode != FALSE_NODE;
                leafNodeVariable = bdd.variableOf(currentNode);
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
                        highestSwitchableVariable = leafNodeVariable;
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

    private static final class BinaryNode implements NodeTable.Node {
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

        @Override
        public String childrenString() {
            return String.format("%5d %5d", low, high);
        }
    }
}
