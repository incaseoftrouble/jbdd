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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

@SuppressWarnings({"AssignmentToMethodParameter", "DuplicatedCode"})
final class MddImpl extends NodeTable implements Mdd {
    private static final int TRUE_NODE = -1;
    private static final int FALSE_NODE = -2;

    private int numberOfVariables;
    private int[] variableDomain;
    private int domainSizeSum = 0;
    private final BddCache cache;

    private int[][] tree;

    @Nullable
    private int[] hashLookup = null;

    MddImpl(BddConfiguration configuration) {
        super(
                configuration.initialSize(),
                configuration.useGarbageCollection() ? configuration.minimumFreeNodePercentageAfterGc() : 1.0,
                configuration.growthFactor());
        tree = new int[tableSize()][];
        cache = new BddCache(this, configuration);
        variableDomain = new int[32];
        numberOfVariables = 0;
    }

    @Override
    protected boolean checkLookupChildrenMatch(int lookup) {
        return Arrays.equals(hashLookup, tree[lookup]);
    }

    private int makeNode(int variable, int[] children) {
        assert 0 <= variable;
        assert Arrays.stream(children).allMatch(child -> isLeaf(child) || variable < variableOf(child));

        int first = children[0];
        for (int val = 1; val < children.length; val++) {
            if (first != children[val]) {
                first = NOT_A_NODE;
                break;
            }
        }
        if (first != NOT_A_NODE) {
            return first;
        }

        hashLookup = children;
        int freeNode = findOrCreateNode(variable, hashCode(variable, children));

        this.tree[freeNode] = children;
        assert hashCode(variable, children) == hashCode(freeNode, variable);
        return freeNode;
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
    public int declareVariable(int domain) {
        assert domain >= 2;
        if (numberOfVariables == variableDomain.length) {
            variableDomain = Arrays.copyOf(variableDomain, variableDomain.length * 2);
        }
        variableDomain[numberOfVariables] = domain;
        domainSizeSum += domain;
        numberOfVariables++;

        cache.variablesChanged();
        ensureWorkStackSize(domainSizeSum + 2);

        return numberOfVariables - 1;
    }

    @Override
    public int makeNode(int variable, boolean[] values) {
        assert variable < numberOfVariables;
        int domain = variableDomain[variable];
        assert values.length == domain;
        int[] children = new int[domain];
        for (int val = 0; val < domain; val++) {
            children[val] = values[val] ? TRUE_NODE : FALSE_NODE;
        }
        return makeNode(variable, children);
    }

    // Reading

    @Override
    public int follow(int node, int value) {
        assert isNodeValid(node);
        return tree[node][value];
    }

    @Override
    public boolean evaluate(int node, int[] assignment) {
        int current = node;
        while (current >= FIRST_NODE) {
            assert isNodeValid(current);
            int variable = variableOf(current);
            current = tree[current][assignment[variable]];
        }
        assert isLeaf(current);
        return current == TRUE_NODE;
    }

    @Override
    public int[] getSatisfyingAssignment(int node) {
        assert isNodeValidOrLeaf(node);

        if (node == FALSE_NODE) {
            throw new NoSuchElementException("False has no solution");
        }

        int[] path = new int[numberOfVariables];
        int currentNode = node;
        while (currentNode != TRUE_NODE) {
            int variable = variableOf(currentNode);
            int[] children = tree[currentNode];
            for (int val = 0; val < children.length; val++) {
                int child = children[val];
                if (child != FALSE_NODE) {
                    path[variable] = val;
                    currentNode = child;
                    break;
                }
            }
        }
        return path;
    }

    @Override
    public void forEachPath(int node, Consumer<int[]> action) {
        assert isNodeValidOrLeaf(node);
        if (node == FALSE_NODE) {
            return;
        }
        int[] path = new int[numberOfVariables()];
        Arrays.fill(path, -1);
        if (node == TRUE_NODE) {
            action.accept(path);
            return;
        }

        forEachPathRecursive(node, path, action);
    }

    private void forEachPathRecursive(int node, int[] path, Consumer<int[]> action) {
        assert isNodeValid(node) || node == TRUE_NODE;

        if (node == TRUE_NODE) {
            action.accept(path);
            return;
        }

        int variable = variableOf(node);
        int[] children = tree[node];
        int domain = children.length;
        for (int val = 0; val < domain; val++) {
            int child = children[val];
            if (child != FALSE_NODE) {
                path[variable] = val;
                forEachPathRecursive(child, path, action);
            }
        }
        path[variable] = -1;
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node) {
        if (node == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (node == TRUE_NODE) {
            BigInteger base = BigInteger.ONE;
            for (int var = 0; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base;
        }

        int variable = variableOf(node);
        BigInteger base = BigInteger.ONE;
        for (int var = 0; var < variable; var++) {
            base = base.multiply(BigInteger.valueOf(variableDomain[var]));
        }
        return base.multiply(countSatisfyingAssignmentsRecursive(node));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node, BitSet support) {
        assert BitSets.isSubset(support(node), support);
        if (node == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (node == TRUE_NODE) {
            BigInteger base = BigInteger.ONE;
            for (int var = support.nextSetBit(0); var >= 0; var = support.nextSetBit(var + 1)) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base;
        }

        BigInteger base = BigInteger.ONE;
        for (int var = support.nextClearBit(0); var < numberOfVariables; var = support.nextClearBit(var + 1)) {
            base = base.multiply(BigInteger.valueOf(variableDomain[var]));
        }
        return countSatisfyingAssignments(node).divide(base);
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node) {
        assert isNodeValid(node);

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return cacheLookup;
        }
        int hash = cache.lookupHash();

        int nodeVar = variableOf(node);
        int[] children = tree[node];
        BigInteger result = doCountSatisfyingAssignments(children[0], nodeVar);
        for (int val = 1; val < children.length; val++) {
            result = result.add(doCountSatisfyingAssignments(children[val], nodeVar));
        }

        cache.putSatisfaction(hash, node, result);
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int child, int currentVar) {
        if (child == FALSE_NODE) {
            return BigInteger.ZERO;
        }
        if (child == TRUE_NODE) {
            BigInteger base = BigInteger.ONE;
            for (int var = currentVar + 1; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base;
        }
        BigInteger base = BigInteger.ONE;
        for (int var = currentVar + 1; var < variableOf(child); var++) {
            base = base.multiply(BigInteger.valueOf(variableDomain[var]));
        }
        return base.multiply(countSatisfyingAssignmentsRecursive(child));
    }

    @Override
    public Iterator<int[]> solutionIterator(int node) {
        assert isNodeValidOrLeaf(node);
        if (node == FALSE_NODE) {
            return Collections.emptyIterator();
        }
        if (node == TRUE_NODE) {
            return new PowerIteratorArray(Arrays.copyOf(variableDomain, numberOfVariables));
        }

        BitSet support = new BitSet(numberOfVariables);
        support.set(0, numberOfVariables);
        return new NodeSolutionIterator(this, node, support);
    }

    @Override
    public Iterator<int[]> solutionIterator(int node, BitSet support) {
        assert isNodeValidOrLeaf(node);
        if (support.isEmpty() || node == FALSE_NODE) {
            return Collections.emptyIterator();
        }
        if (node == TRUE_NODE) {
            return new PowerIteratorArray(variableDomain, support);
        }

        return new NodeSolutionIterator(this, node, support);
    }

    // Bdd operations

    @Override
    public int and(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = andRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
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

        int[] node1children = tree[node1];
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (node1var == node2var) {
            int[] node2children = tree[node2];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(andRecursive(node1children[val], node2children[val]));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(andRecursive(node1children[val], node2));
            }
        }
        int resultNode = makeNode(node1var, resultChildren);
        popWorkStack(domain);
        cache.putAnd(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int equivalence(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = equivalenceRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
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

        int[] node1children = tree[node1];
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (node1var == node2var) {
            int[] node2children = tree[node2];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(equivalenceRecursive(node1children[val], node2children[val]));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(equivalenceRecursive(node1children[val], node2));
            }
        }
        int resultNode = makeNode(node1var, resultChildren);
        popWorkStack(domain);
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
        int cardinality = quantifiedVariables.cardinality();
        if (cardinality == numberOfVariables) {
            return TRUE_NODE;
        }

        cache.initExists(quantifiedVariables);
        int[] quantifiedVariableArray = BitSets.toArray(quantifiedVariables);
        pushToWorkStack(node);
        int result = existsRecursive(node, 0, quantifiedVariableArray);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
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

        int[] children = tree[node];
        if (nodeVariable == quantifiedVariable) {
            for (int child : children) {
                if (child == TRUE_NODE) {
                    return TRUE_NODE;
                }
            }
        }

        if (cache.lookupExists(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int domain = children.length;
        int resultNode;
        if (quantifiedVariable > nodeVariable) {
            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        pushToWorkStack(existsRecursive(children[val], currentIndex, quantifiedVariables));
            }
            resultNode = makeNode(nodeVariable, resultChildren);
            popWorkStack(domain);
        } else {
            resultNode = existsRecursive(children[0], currentIndex, quantifiedVariables);
            for (int val = 1; val < domain; val++) {
                resultNode = orRecursive(
                        pushToWorkStack(resultNode),
                        pushToWorkStack(existsRecursive(children[val], currentIndex, quantifiedVariables)));
                popWorkStack(2);
            }
        }

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
        int cardinality = quantifiedVariables.cardinality();
        if (cardinality == numberOfVariables) {
            assert node != TRUE_NODE;
            return FALSE_NODE;
        }

        cache.initForall(quantifiedVariables);
        int[] quantifiedVariableArray = BitSets.toArray(quantifiedVariables);
        pushToWorkStack(node);
        int result = forallRecursive(node, 0, quantifiedVariableArray);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
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

        int[] children = tree[node];
        if (nodeVariable == quantifiedVariable) {
            for (int child : children) {
                if (child == FALSE_NODE) {
                    return FALSE_NODE;
                }
            }
        }

        if (cache.lookupForall(node)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int domain = children.length;
        int resultNode;
        if (quantifiedVariable > nodeVariable) {
            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        pushToWorkStack(forallRecursive(children[val], currentIndex, quantifiedVariables));
            }
            resultNode = makeNode(nodeVariable, resultChildren);
            popWorkStack(domain);
        } else {
            resultNode = forallRecursive(children[0], currentIndex, quantifiedVariables);
            for (int val = 1; val < domain; val++) {
                resultNode = andRecursive(
                        pushToWorkStack(resultNode),
                        pushToWorkStack(forallRecursive(children[val], currentIndex, quantifiedVariables)));
                popWorkStack(2);
            }
        }

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
        int[] ifTree = ifVar == minVar ? tree[ifNode] : null;
        int[] thenTree = thenVar == minVar ? tree[thenNode] : null;
        int[] elseTree = elseVar == minVar ? tree[elseNode] : null;
        int minVarDomain = variableDomain[minVar];
        int[] resultChildren = new int[minVarDomain];
        for (int val = 0; val < minVarDomain; val++) {
            resultChildren[val] = pushToWorkStack(ifThenElseRecursive(
                    ifTree == null ? ifNode : ifTree[val],
                    thenTree == null ? thenNode : thenTree[val],
                    elseTree == null ? elseNode : elseTree[val]));
        }
        int result = makeNode(minVar, resultChildren);
        popWorkStack(minVarDomain);
        cache.putIfThenElse(hash, ifNode, thenNode, elseNode, result);
        return result;
    }

    @Override
    public int implication(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = implicationRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
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

        int[] node1children = tree[node1];
        int[] node2children = tree[node2];

        int[] resultChildren;
        int decisionVar;
        if (node1var > node2var) {
            int domain = node2children.length;
            resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(implicationRecursive(node1, node2children[val]));
            }
            decisionVar = node2var;
        } else if (node1var == node2var) {
            int domain = node1children.length;
            resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(implicationRecursive(node1children[val], node2children[val]));
            }
            decisionVar = node1var;
        } else {
            int domain = node1children.length;
            resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(implicationRecursive(node1children[val], node2));
            }
            decisionVar = node1var;
        }
        int resultNode = makeNode(decisionVar, resultChildren);
        popWorkStack(2);
        cache.putImplication(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public boolean implies(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
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

        if (cache.lookupImplication(node1, node2)) {
            return cache.lookupResult() == TRUE_NODE;
        }
        int node1var = variableOf(node1);
        int node2var = variableOf(node2);

        int[] node1children = tree[node1];
        int[] node2children = tree[node2];

        if (node1var == node2var) {
            int domain = node1children.length;
            for (int val = 0; val < domain; val++) {
                if (!impliesRecursive(node1children[val], node2children[val])) {
                    return false;
                }
            }
        } else if (node1var < node2var) {
            for (int node1child : node1children) {
                if (!impliesRecursive(node1child, node2)) {
                    return false;
                }
            }
        } else {
            for (int node2child : node2children) {
                if (!impliesRecursive(node1, node2child)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int not(int node) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node);
        pushToWorkStack(node);
        int result = notRecursive(node);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
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

        int[] nodeChildren = tree[node];
        int domain = nodeChildren.length;
        int[] resultChildren = new int[domain];
        for (int val = 0; val < domain; val++) {
            resultChildren[val] = pushToWorkStack(notRecursive(nodeChildren[val]));
        }
        int resultNode = makeNode(variableOf(node), resultChildren);
        popWorkStack(domain);
        cache.putNot(hash, node, resultNode);
        return resultNode;
    }

    @Override
    public int notAnd(int node1, int node2) {
        assert isWorkStackEmpty();
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = notAndRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
        return result;
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

        int[] node1children = tree[node1];
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (node1var == node2var) {
            int[] node2children = tree[node2];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(notAndRecursive(node1children[val], node2children[val]));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(notAndRecursive(node1children[val], node2));
            }
        }
        int resultNode = makeNode(node1var, resultChildren);
        popWorkStack(domain);
        cache.putNAnd(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int or(int node1, int node2) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node1) && isNodeValidOrLeaf(node2);
        pushToWorkStack(node1);
        pushToWorkStack(node2);
        int result = orRecursive(node1, node2);
        popWorkStack(2);
        assert isWorkStackEmpty();
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

        int[] node1children = tree[node1];
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (node1var == node2var) {
            int[] node2children = tree[node2];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(orRecursive(node1children[val], node2children[val]));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(orRecursive(node1children[val], node2));
            }
        }
        int resultNode = makeNode(node1var, resultChildren);
        popWorkStack(domain);
        cache.putOr(hash, node1, node2, resultNode);
        return resultNode;
    }

    @Override
    public int restrict(int node, int[] values) {
        assert isWorkStackEmpty();
        assert isNodeValidOrLeaf(node);

        if (isLeaf(node)) {
            return node;
        }
        int highestReplacedVariable = values.length - 1;
        while (highestReplacedVariable >= 0 && values[highestReplacedVariable] == -1) {
            highestReplacedVariable -= 1;
        }
        if (highestReplacedVariable == -1) {
            return node;
        }
        assert values[highestReplacedVariable] != -1;

        pushToWorkStack(node);

        cache.initCompose(values, highestReplacedVariable);
        int result = restrictRecursive(node, values, highestReplacedVariable);
        popWorkStack();
        assert isWorkStackEmpty();
        return result;
    }

    private int restrictRecursive(int node, int[] values, int highestReplacedVariable) {
        if (isLeaf(node)) {
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

        int[] children = tree[node];
        int domain = children.length;
        int resultNode;

        int variableReplacementValue = values[nodeVariable];
        if (variableReplacementValue == -1) {
            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        pushToWorkStack(restrictRecursive(children[val], values, highestReplacedVariable));
            }
            resultNode = makeNode(nodeVariable, resultChildren);
            popWorkStack(domain);
        } else {
            resultNode = restrictRecursive(children[variableReplacementValue], values, highestReplacedVariable);
        }

        cache.putCompose(hash, node, resultNode);
        return resultNode;
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

        int[] node1children = tree[node1];
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (node1var == node2var) {
            int[] node2children = tree[node2];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(xorRecursive(node1children[val], node2children[val]));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = pushToWorkStack(xorRecursive(node1children[val], node2));
            }
        }
        int resultNode = makeNode(node1var, resultChildren);
        popWorkStack(domain);
        cache.putXor(hash, node1, node2, resultNode);
        return resultNode;
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("MDD@%d(%d)", tableSize(), System.identityHashCode(this));
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
        return hashCode(variable, tree[node]);
    }

    private static int hashCode(int variable, int[] children) {
        return variable + Arrays.hashCode(children);
    }

    @Override
    protected void forEachChild(int node, IntConsumer action) {
        for (int child : tree[node]) {
            action.accept(child);
        }
    }

    @Override
    protected int sumEachBelow(int node, IntUnaryOperator operator) {
        int sum = 0;
        for (int child : tree[node]) {
            sum += operator.applyAsInt(child);
        }
        return sum;
    }

    @Override
    protected boolean allMatchBelow(int node, IntPredicate predicate) {
        for (int child : tree[node]) {
            if (!predicate.test(child)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected Node node(int node) {
        return new MultiNode(variableOf(node), tree[node]);
    }

    public void invalidateCache() {
        cache.invalidate();
    }

    // Utility classes

    static final class NodeSolutionIterator implements Iterator<int[]> {
        private static final int NON_PATH_NODE = NOT_A_NODE;

        private final MddImpl mdd;
        private final int[] assignment;
        private final BitSet support;
        private final int variableCount;
        private final int[] path;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private boolean hasNextAssignment;
        private final int rootVariable;

        NodeSolutionIterator(MddImpl mdd, int node, BitSet support) {
            // Require at least one possible solution to exist.
            assert mdd.isNodeValid(node) || node == TRUE_NODE;
            variableCount = mdd.numberOfVariables();

            // Assignments don't make much sense otherwise
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(mdd.support(node), support);

            this.mdd = mdd;
            this.support = support;
            this.path = new int[variableCount];
            this.assignment = new int[variableCount];
            rootVariable = mdd.variableOf(node);
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

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public int[] next() {
            assert IntStream.range(0, variableCount).allMatch(i -> support.get(i) || path[i] == NON_PATH_NODE);

            int currentNode;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
            } else {
                // Check if we can flip any non-path variable in the support
                boolean clearedAny = false;
                for (int var = support.nextSetBit(0); var >= 0; var = support.nextSetBit(var + 1)) {
                    // Strategy: Perform "addition" on the NON_PATH_NODEs over the support
                    // The tricky bit is to determine whether there is a "next element": Either there is
                    // another real path in the BDD or there is some variable which we still can increase

                    if (path[var] == NON_PATH_NODE) {
                        assert assignment[var] < mdd.variableDomain[var];
                        if (assignment[var] == mdd.variableDomain[var] - 1) {
                            assignment[var] = 0;
                            clearedAny = true;
                        } else {
                            assignment[var] += 1;
                            if (hasNextPath || clearedAny || assignment[var] < mdd.variableDomain[var] - 1) {
                                hasNextAssignment = true;
                            } else {
                                hasNextAssignment = false;

                                // TODO This should be constant time to determine?
                                // TODO This only needs to run if we set the first non-path variable to 1
                                for (int i = support.nextSetBit(var + 1); i >= 0; i = support.nextSetBit(i + 1)) {
                                    if (path[i] == NON_PATH_NODE && assignment[i] < mdd.variableDomain[i] - 1) {
                                        hasNextAssignment = true;
                                        break;
                                    }
                                }
                            }
                            assert mdd.evaluate(path[rootVariable], assignment);
                            return assignment;
                        }
                    }
                }

                // Situation: All non-path variables are set to zero, and we need to find a new path
                assert IntStream.range(0, variableCount)
                        .noneMatch(var -> path[var] == NON_PATH_NODE && assignment[var] > 0);
                assert hasNextPath
                        : "Expected another path after " + Arrays.toString(assignment) + ", node:\n"
                                + mdd.treeToString(path[rootVariable]);

                // Backtrack on the current path until we find a node that we can increase to non-false branch
                // to find a new path
                // TODO Use highestLowVariableWithNonFalseHighBranch?
                currentNode = path[leafNodeVariable];
                int branchVar = leafNodeVariable;

                int[] children;
                //noinspection LabeledStatement
                outer:
                while (true) {
                    assert path[branchVar] != NON_PATH_NODE;

                    children = mdd.tree[currentNode];
                    int val = this.assignment[branchVar] + 1;
                    while (val < children.length) {
                        if (children[val] != FALSE_NODE) {
                            this.assignment[branchVar] = val;
                            //noinspection BreakStatementWithLabel
                            break outer;
                        }
                        val += 1;
                    }
                    assert val == children.length;

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
                assert assignment[branchVar] < mdd.variableDomain[branchVar];
                assert leafNodeVariable >= highestSwitchableVariable;
                assert mdd.variableOf(currentNode) == branchVar;

                // currentNode is the deepest node we could increase; set the value and descend the tree
                Arrays.fill(assignment, branchVar + 1, leafNodeVariable + 1, 0);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                leafNodeVariable = branchVar;

                assert path[branchVar] == currentNode;
                currentNode = mdd.follow(currentNode, assignment[branchVar]);
                assert currentNode != FALSE_NODE;

                // We maxed out the candidate for increase, clear this information
                if (highestSwitchableVariable == branchVar) {
                    highestSwitchableVariable = -1;
                    for (int val = assignment[branchVar] + 1; val < children.length; val++) {
                        if (children[val] != FALSE_NODE) {
                            highestSwitchableVariable = branchVar;
                            break;
                        }
                    }
                }
            }

            // Situation: Either the currentNode valuation was just increased or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (currentNode != TRUE_NODE) {
                assert currentNode != FALSE_NODE;
                leafNodeVariable = mdd.variableOf(currentNode);
                path[leafNodeVariable] = currentNode;
                assert support.get(leafNodeVariable);

                int[] children = mdd.tree[currentNode];
                int domain = children.length;
                int val = 0;
                while (children[val] == FALSE_NODE) {
                    val += 1;
                }
                assignment[leafNodeVariable] = val;
                currentNode = mdd.follow(currentNode, val);

                if (!hasNextPath) {
                    val += 1;
                    while (val < domain) {
                        if (children[val] == FALSE_NODE) {
                            val += 1;
                        } else {
                            hasNextPath = true;
                            highestSwitchableVariable = leafNodeVariable;
                            break;
                        }
                    }
                }
            }
            assert mdd.evaluate(path[rootVariable], assignment);

            // If this is a unique path, there won't be any trivial assignments
            // TODO We can make this faster!
            for (int var = support.nextSetBit(0); var >= 0; var = support.nextSetBit(var + 1)) {
                if (path[var] == NON_PATH_NODE) {
                    // We switched path so every non-path variable is low
                    assert path[var] != NON_PATH_NODE || assignment[var] == 0;
                    hasNextAssignment = true;
                    return assignment;
                }
            }
            hasNextAssignment = hasNextPath;
            return assignment;
        }
    }

    private static final class MultiNode implements Node {
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
            return HashUtil.hash(var, children);
        }

        @Override
        public String childrenString() {
            return Arrays.stream(children)
                    .mapToObj(i -> String.format("%5d", i))
                    .collect(Collectors.joining(" "));
        }
    }
}
