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

import static de.tum.in.jbdd.NodeTable.PLACEHOLDER;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

@SuppressWarnings({"PMD.AvoidReassigningParameters", "AssignmentToMethodParameter", "DuplicatedCode"})
final class MddImpl extends BooleanBase<int[], int[]> implements Mdd {
    private static final Logger logger = Logger.getLogger(BddImpl.class.getName());

    private final BooleanCache cache;
    private int numberOfVariables;
    private int[] variableDomain;
    private final BddConfiguration configuration;
    private final NodeTable.Multi table;

    MddImpl(BddConfiguration configuration) {
        this.configuration = configuration;
        this.table = new MddTable(this, configuration.initialSize());

        cache = new BooleanCache(this);
        variableDomain = new int[32];
        numberOfVariables = 0;
    }

    @Override
    public BddConfiguration configuration() {
        return configuration;
    }

    @Override
    NodeTable table() {
        return table;
    }

    @Override
    BooleanCache cache() {
        return cache;
    }

    // Nodes

    @Override
    public int follow(int function, int value) {
        assert isValidNonConstantFunction(function) && isValidValue(function, value);
        int node = positive(function);
        return complementIf(table.follow(node, value), node != function);
    }

    private boolean isValidValue(int function, int value) {
        return 0 <= value && value < variableDomain[decisionVariable(function)];
    }

    // Variables and base nodes

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
        numberOfVariables++;

        cache.variablesChanged();

        return numberOfVariables - 1;
    }

    @Override
    public int makeVariableFunction(int variable, boolean[] values) {
        assert variable < numberOfVariables;
        int domain = variableDomain[variable];
        assert values.length == domain;
        int[] children = new int[domain];
        for (int val = 0; val < domain; val++) {
            children[val] = values[val] ? trueFunction() : falseFunction();
        }

        return makeFunction(variable, children);
    }

    private int makeFunction(int variable, int[] children) {
        int first = children[0];

        for (int val = 1; val < children.length; val++) {
            if (first != children[val]) {
                first = PLACEHOLDER;
                break;
            }
        }
        if (first != PLACEHOLDER) {
            // All elements are the same
            return first;
        }

        if (isComplementFunction(children[0])) {
            for (int i = 0; i < children.length; i++) {
                children[i] = complement(children[i]);
            }
            return complement(table.makeNode(variable, children));
        }
        return table.makeNode(variable, children);
    }

    // Reading

    @Override
    public boolean evaluate(int function, int[] assignment) {
        assert isValidFunction(function);
        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidNode(currentNode);
            int value = assignment[decisionVariable(currentNode)];
            assert isValidValue(currentNode, value);
            int child = table.follow(currentNode, value);
            currentNode = positive(child);
            if (currentNode != child) {
                lookingFor = !lookingFor;
            }
        }
        return lookingFor;
    }

    @Override
    public int[] satisfyingAssignment(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            throw new NoSuchElementException("False has no solution");
        }

        int[] path = new int[numberOfVariables];
        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;

        while (currentNode != TRUE) {
            int[] children = table.children(currentNode);
            for (int val = 0; val < children.length; val++) {
                int child = children[val];
                if (!isFalse(child, lookingFor)) {
                    int variable = table.variable(currentNode);

                    path[variable] = val;
                    currentNode = positive(child);
                    break;
                }
            }
        }
        assert lookingFor;
        return path;
    }

    @Override
    public Iterator<int[]> solutionIterator(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            return new PowerIteratorArray(Arrays.copyOf(variableDomain, numberOfVariables));
        }

        BitSet support = new BitSet(numberOfVariables);
        support.set(0, numberOfVariables);
        return new NodeSolutionIterator(this, function, support);
    }

    @Override
    public Iterator<int[]> solutionIterator(int function, BitSet support) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            return new PowerIteratorArray(variableDomain, support);
        }

        return new NodeSolutionIterator(this, function, support);
    }

    @Override
    public Iterator<int[]> pathIterator(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            int[] path = new int[numberOfVariables];
            Arrays.fill(path, -1);
            return Collections.singleton(path).iterator();
        }

        return new NodePathIterator(this, function);
    }

    @Override
    public void forEachPath(int function, Consumer<? super int[]> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);

        if (function == TRUE) {
            action.accept(path);
            return;
        }

        int numberOfVariables = numberOfVariables();
        forEachPathRecursive(positive(function), null, numberOfVariables, path, action, isPositive(function));
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super int[]> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);
        if (function == TRUE || relevantSet.isEmpty()) {
            action.accept(path);
            return;
        }

        int highestVariable = relevantSet.length() - 1;

        forEachPathRecursive(positive(function), relevantSet, highestVariable, path, action, isPositive(function));
    }

    private void forEachPathRecursive(
            int node,
            @Nullable BitSet support,
            int depthLimit,
            int[] path,
            Consumer<? super int[]> action,
            boolean lookingFor) {

        if (node == TRUE) {
            assert lookingFor;
            action.accept(path);
            return;
        }
        assert table.isValidNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        if (variable > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path);
            return;
        }

        boolean relevant = support == null || support.get(variable);

        int[] children = table.children(node);
        for (int val = 0; val < children.length; val++) {
            int child = children[val];
            if (!isFalse(child, lookingFor)) {
                if (relevant) {
                    path[variable] = val;
                }
                forEachPathRecursive(
                        positive(child), support, depthLimit, path, action, isPositive(child) == lookingFor);
            }
        }

        assert (path[variable] >= 0) == relevant;
        if (relevant) {
            path[variable] = -1;
        }
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super int[]> predicate) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return false;
        }
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);
        if (function == TRUE) {
            return predicate.test(path);
        }

        return anyPathMatchesRecursive(positive(function), path, predicate, isPositive(function));
    }

    private boolean anyPathMatchesRecursive(
            int node, int[] path, Predicate<? super int[]> predicate, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            return predicate.test(path);
        }
        assert table.isValidNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);

        int[] children = table.children(node);
        for (int val = 0; val < children.length; val++) {
            int child = children[val];
            if (!isFalse(child, lookingFor)) {
                path[variable] = val;
                if (anyPathMatchesRecursive(positive(child), path, predicate, isPositive(child) == lookingFor)) {
                    return true;
                }
            }
        }

        path[variable] = -1;
        return false;
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        if (function == FALSE) {
            return BigInteger.ZERO;
        }
        if (function == TRUE) {
            BigInteger base = BigInteger.ONE;
            for (int var = 0; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base;
        }

        int variable = decisionVariable(function);
        BigInteger base = BigInteger.ONE;
        for (int var = 0; var < variable; var++) {
            base = base.multiply(BigInteger.valueOf(variableDomain[var]));
        }
        return base.multiply(countSatisfyingAssignmentsRecursive(positive(function), isPositive(function)));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        if (function == FALSE) {
            return BigInteger.ZERO;
        }
        if (function == TRUE) {
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
        return countSatisfyingAssignments(function).divide(base);
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node, boolean lookingFor) {
        assert isValidFunction(node);

        int nodeVar = table.variable(node);

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            if (lookingFor) {
                return cacheLookup;
            }

            BigInteger base = BigInteger.ONE;
            for (int var = nodeVar; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base.subtract(cacheLookup);
        }
        int hash = cache.lookupHash();

        int[] children = table.children(node);
        BigInteger result = BigInteger.ZERO;
        for (int child : children) {
            result =
                    result.add(doCountSatisfyingAssignments(positive(child), nodeVar, isPositive(child) == lookingFor));
        }

        if (lookingFor) {
            cache.putSatisfaction(hash, node, result);
        } else {
            BigInteger base = BigInteger.ONE;
            for (int var = nodeVar; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            cache.putSatisfaction(hash, node, base.subtract(result));
        }
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int node, int previousVar, boolean lookingFor) {
        if (isFalse(node, lookingFor)) {
            return BigInteger.ZERO;
        }
        if (isTrue(node, lookingFor)) {
            BigInteger base = BigInteger.ONE;
            for (int var = previousVar + 1; var < numberOfVariables; var++) {
                base = base.multiply(BigInteger.valueOf(variableDomain[var]));
            }
            return base;
        }
        BigInteger base = BigInteger.ONE;
        for (int var = previousVar + 1; var < table.variable(node); var++) {
            base = base.multiply(BigInteger.valueOf(variableDomain[var]));
        }
        return base.multiply(countSatisfyingAssignmentsRecursive(node, lookingFor));
    }

    // Bdd operations

    @Override
    public int and(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeAnd(function1, function2);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeAnd(int function1, int function2) {
        if (function1 == TRUE) {
            return function2;
        }
        if (function2 == TRUE) {
            return function1;
        }
        if (function1 == FALSE || function2 == FALSE) {
            return FALSE;
        }
        if (function1 == function2) {
            return function1;
        }
        if (function1 == complement(function2)) {
            return FALSE;
        }

        assert !isConstant(function1) && !isConstant(function2);

        int fun1var = decisionVariable(function1);
        int fun2var = decisionVariable(function2);

        if (fun2var < fun1var || (fun2var == fun1var && function2 < function1)) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;

            int varSwap = fun1var;
            fun1var = fun2var;
            fun2var = varSwap;
        }

        if (cache.lookupAnd(function1, function2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        boolean fun1c = isComplementFunction(function1);
        int node1 = complementIf(function1, fun1c);
        int[] node1children = table.children(node1);
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = complementIf(function2, fun2c);
            int[] node2children = table.children(node2);
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = table.pushToWorkStack(
                        computeAnd(complementIf(node1children[val], fun1c), complementIf(node2children[val], fun2c)));
            }
        } else { // v < getVariable(node2)
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        table.pushToWorkStack(computeAnd(complementIf(node1children[val], fun1c), function2));
            }
        }
        int resultNode = makeFunction(fun1var, resultChildren);
        table.popFromWorkStack(domain);
        cache.putAnd(hash, function1, function2, resultNode);
        return resultNode;
    }

    private int computeOr(int function1, int function2) {
        return complement(computeAnd(complement(function1), complement(function2)));
    }

    @Override
    public int xor(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function1, function2);
        int ret = computeXor(function1, function2);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return ret;
    }

    private int computeXor(int function1, int function2) {
        if (function1 == function2) {
            return FALSE;
        }
        if (function1 == complement(function2)) {
            return TRUE;
        }

        if (isComplementFunction(function1)) {
            function1 = positive(function1);
            function2 = complement(function2);
        }
        // TODO Should be possible to exploit this knowledge a bit more
        assert isPositive(function1);

        if (function1 == TRUE) {
            return complement(function2);
        }
        if (function2 == TRUE) {
            return complement(function1);
        }
        if (function2 == FALSE) {
            return function1;
        }

        int node1 = function1;
        int node2 = positive(function2);
        int node1var = decisionVariable(node1);
        int node2var = decisionVariable(node2);

        if (node2var < node1var || (node2var == node1var && function2 < function1)) {
            int functionSwap = function1;
            function1 = function2;
            function2 = functionSwap;

            int nodeSwap = node1;
            node1 = node2;
            node2 = nodeSwap;

            int varSwap = node1var;
            node1var = node2var;
            node2var = varSwap;
        }

        if (cache.lookupXor(function1, function2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int[] node1children = table.children(node1);
        int domain = node1children.length;
        int[] resultChildren = new int[domain];
        boolean node1c = function1 != node1;
        if (node1var == node2var) {
            boolean node2c = function2 != node2;
            int[] node2children = table.children(node2);
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = table.pushToWorkStack(
                        computeXor(complementIf(node1children[val], node1c), complementIf(node2children[val], node2c)));
            }
        } else { // node1var < node2var
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        table.pushToWorkStack(computeXor(complementIf(node1children[val], node1c), function2));
            }
        }
        int resultNode = makeFunction(node1var, resultChildren);
        table.popFromWorkStack(domain);
        cache.putXor(hash, function1, function2, resultNode);
        return resultNode;
    }

    @Override
    public int exists(int function, BitSet quantifiedVariables) {
        assert isValidFunction(function);
        assert quantifiedVariables.length() - 1 <= numberOfVariables;

        if (isConstant(function)) {
            return function;
        }
        if (quantifiedVariables.cardinality() == numberOfVariables) {
            return TRUE;
        }

        assert table.isWorkStackEmpty();
        cache.initQuantification(quantifiedVariables);
        boolean complemented = isComplementFunction(function);
        table.pushToWorkStack(function);
        int result = quantifyRecursive(positive(function), quantifiedVariables, !complemented);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return complementIf(result, complemented);
    }

    private int quantifyRecursive(int function, BitSet quantifiedVariables, boolean exists) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        int node = positive(function);
        int variable = table.variable(node);
        int currentCubeNodeVariable = quantifiedVariables.nextSetBit(variable);
        if (currentCubeNodeVariable == -1) {
            return function;
        }

        boolean isComplement = node != function;
        int[] children = table.children(node);
        boolean currentVariableIsQuantified = variable == currentCubeNodeVariable;

        if (currentVariableIsQuantified) {
            for (int child : children) {
                if (isTrue(child, !isComplement == exists)) {
                    return exists ? TRUE : FALSE;
                }
            }
        }

        if (cache.lookupQuantification(function, exists)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        int domain = children.length;
        int resultNode;
        if (currentVariableIsQuantified) {
            if (exists) {
                resultNode = falseFunction();
                for (int child : children) {
                    table.pushToWorkStack(resultNode);
                    int quantifiedBranch = table.pushToWorkStack(
                            complementIf(quantifyRecursive(child, quantifiedVariables, !isComplement), isComplement));
                    resultNode = computeOr(resultNode, quantifiedBranch);
                    table.popFromWorkStack(2);
                }
            } else {
                resultNode = trueFunction();
                for (int child : children) {
                    table.pushToWorkStack(resultNode);
                    int quantifiedBranch = table.pushToWorkStack(
                            complementIf(quantifyRecursive(child, quantifiedVariables, isComplement), isComplement));
                    resultNode = computeAnd(resultNode, quantifiedBranch);
                    table.popFromWorkStack(2);
                }
            }
        } else {
            assert currentCubeNodeVariable > variable;
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward

            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] = table.pushToWorkStack(complementIf(
                        quantifyRecursive(children[val], quantifiedVariables, isComplement != exists), isComplement));
            }
            resultNode = makeFunction(variable, resultChildren);
            table.popFromWorkStack(domain);
        }
        cache.putQuantification(hash, function, exists, resultNode);
        return resultNode;
    }

    @Override
    public boolean implies(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert table.isWorkStackEmpty();
        boolean result = impliesRecursive(function1, function2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private boolean impliesRecursive(int function1, int function2) {
        if (function1 == FALSE) {
            // False implies anything
            return true;
        }
        if (function2 == FALSE) {
            // function1 != FALSE_NODE
            return false;
        }
        if (function2 == TRUE) {
            // function1 != FALSE_NODE
            return true;
        }
        if (function1 == TRUE) {
            // function2 != TRUE_NODE
            return false;
        }
        if (function1 == function2) {
            // Trivial implication
            return true;
        }
        if (function1 == complement(function2)) {
            return false;
        }

        if (cache.lookupImplies(function1, function2)) {
            return cache.lookupResult() == TRUE;
        }
        int hash = cache.lookupHash();

        int node1 = positive(function1);
        int node2 = positive(function2);
        boolean fun1c = function1 != node1;
        boolean fun2c = function2 != node2;
        int node1var = decisionVariable(node1);
        int node2var = decisionVariable(node2);

        boolean result = true;
        if (node1var == node2var) {
            int[] node1children = table.children(node1);
            int[] node2children = table.children(node2);
            for (int val = 0; val < node1children.length; val++) {
                if (!impliesRecursive(
                        complementIf(node1children[val], fun1c), complementIf(node2children[val], fun2c))) {
                    result = false;
                    break;
                }
            }
        } else if (node1var < node2var) {
            int[] node1children = table.children(node1);
            for (int node1child : node1children) {
                if (!impliesRecursive(complementIf(node1child, fun1c), function2)) {
                    result = false;
                    break;
                }
            }
        } else {
            int[] node2children = table.children(node2);
            for (int node2child : node2children) {
                if (!impliesRecursive(function1, complementIf(node2child, fun2c))) {
                    result = false;
                    break;
                }
            }
        }
        cache.putImplies(hash, function1, function2, result);
        return result;
    }

    @Override
    public boolean intersects(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert table.isWorkStackEmpty();
        boolean result = intersectsRecursive(function1, function2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private boolean intersectsRecursive(int function1, int function2) {
        if (function1 == FALSE || function2 == FALSE) {
            return false;
        }
        if (function1 == TRUE || function2 == TRUE) {
            return true;
        }
        if (function1 == function2) {
            return true;
        }
        if (function1 == complement(function2)) {
            return false;
        }

        assert !isConstant(function1) && !isConstant(function2);

        int fun1var = decisionVariable(function1);
        int fun2var = decisionVariable(function2);

        if (fun2var < fun1var || (fun2var == fun1var && function2 < function1)) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;

            int varSwap = fun1var;
            fun1var = fun2var;
            fun2var = varSwap;
        }

        if (cache.lookupIntersects(function1, function2)) {
            return cache.lookupResult() == TRUE;
        }
        int hash = cache.lookupHash();

        int node1 = positive(function1);
        boolean fun1c = function1 != node1;
        int[] node1children = table.children(node1);

        boolean result = false;
        if (fun1var == fun2var) {
            int node2 = positive(function2);
            boolean fun2c = function2 != node2;

            int[] node2children = table.children(node2);
            for (int val = 0; val < node1children.length; val++) {
                if (intersectsRecursive(
                        complementIf(node1children[val], fun1c), complementIf(node2children[val], fun2c))) {
                    result = true;
                    break;
                }
            }
        } else {
            for (int node1child : node1children) {
                if (intersectsRecursive(complementIf(node1child, fun1c), function2)) {
                    result = true;
                    break;
                }
            }
        }
        cache.putIntersects(hash, function1, function2, result);
        return result;
    }

    @Override
    public int restrict(int function, int[] values) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        assert table.isWorkStackEmpty();
        int highestReplacedVariable = values.length - 1;
        while (highestReplacedVariable >= 0 && values[highestReplacedVariable] == -1) {
            highestReplacedVariable -= 1;
        }
        if (highestReplacedVariable == -1) {
            return function;
        }
        assert values[highestReplacedVariable] != -1;

        table.pushToWorkStack(function);
        // cache.initRemapping(values, highestReplacedVariable);
        int result = computeRestrict(function, values, highestReplacedVariable);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeRestrict(int function, int[] values, int highestReplacedVariable) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        int variable = decisionVariable(function);
        if (variable > highestReplacedVariable) {
            return function;
        }

        int node = positive(function);
        boolean isComplemented = node != function;

        //        if (cache.lookupRemapping(node)) {
        //            return complementIf(cache.lookupResult(), isComplemented);
        //        }
        //        int hash = cache.lookupHash();

        int[] children = table.children(node);
        int domain = children.length;
        int resultNode;

        int variableReplacementValue = values[variable];
        if (variableReplacementValue == -1) {
            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        table.pushToWorkStack(computeRestrict(children[val], values, highestReplacedVariable));
            }
            resultNode = makeFunction(variable, resultChildren);
            table.popFromWorkStack(domain);
        } else {
            resultNode = computeRestrict(children[variableReplacementValue], values, highestReplacedVariable);
        }
        // cache.putRemapping(hash, node, resultNode);
        return complementIf(resultNode, isComplemented);
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        assert isValidFunction(ifFunction) && isValidFunction(thenFunction) && isValidFunction(elseFunction);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(ifFunction, thenFunction, elseFunction);
        int result = computeIfThenElse(ifFunction, thenFunction, elseFunction);
        table.popFromWorkStack(3);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeIfThenElse(int ifFunction, int thenFunction, int elseFunction) {
        if (ifFunction == TRUE) {
            return thenFunction;
        }
        if (ifFunction == FALSE) {
            return elseFunction;
        }

        if (thenFunction == TRUE || thenFunction == ifFunction) {
            return computeOr(ifFunction, elseFunction);
        }
        if (thenFunction == FALSE || thenFunction == complement(ifFunction)) {
            return computeAnd(complement(ifFunction), elseFunction);
        }

        if (elseFunction == TRUE || elseFunction == complement(ifFunction)) {
            return complement(computeAnd(ifFunction, complement(thenFunction)));
        }
        if (elseFunction == FALSE || ifFunction == elseFunction) {
            return computeAnd(ifFunction, thenFunction);
        }

        if (thenFunction == elseFunction) {
            return thenFunction;
        }
        if (thenFunction == complement(elseFunction)) {
            return computeXor(ifFunction, elseFunction);
        }

        // Normalize so that at most else is complemented
        int ifNormalized = positive(ifFunction);
        int thenSwap;
        int elseSwap;
        if (ifNormalized == ifFunction) {
            thenSwap = thenFunction;
            elseSwap = elseFunction;
        } else {
            thenSwap = elseFunction;
            elseSwap = thenFunction;
        }

        boolean complement = false;
        int thenNormalized = positive(thenSwap);
        int elseNormalized;
        if (thenNormalized == thenSwap) {
            elseNormalized = elseSwap;
        } else {
            elseNormalized = complement(elseSwap);
            complement = true;
        }
        assert isPositive(ifNormalized) && isPositive(thenNormalized);

        if (cache.lookupIfThenElse(ifNormalized, thenNormalized, elseNormalized)) {
            return complementIf(cache.lookupResult(), complement);
        }
        int hash = cache.lookupHash();
        int ifVar = table.variable(ifNormalized);
        int thenVar = table.variable(thenNormalized);
        int elseNode = positive(elseNormalized);
        int elseVar = table.variable(elseNode);

        int minVar = Math.min(ifVar, Math.min(thenVar, elseVar));
        int[] ifTree = ifVar == minVar ? table.children(ifNormalized) : null;
        int[] thenTree = thenVar == minVar ? table.children(thenNormalized) : null;
        int[] elseTree = elseVar == minVar ? table.children(elseNode) : null;
        boolean elsec = elseTree != null && elseNode != elseNormalized;
        int minVarDomain = variableDomain[minVar];
        int[] resultChildren = new int[minVarDomain];
        for (int val = 0; val < minVarDomain; val++) {
            int elseBranch = elseTree == null ? elseNormalized : complementIf(elseTree[val], elsec);
            resultChildren[val] = table.pushToWorkStack(computeIfThenElse(
                    ifTree == null ? ifNormalized : ifTree[val],
                    thenTree == null ? thenNormalized : thenTree[val],
                    elseBranch));
        }
        int result = makeFunction(minVar, resultChildren);
        table.popFromWorkStack(minVarDomain);
        cache.putIfThenElse(hash, ifNormalized, thenNormalized, elseNormalized, result);
        return complementIf(result, complement);
    }

    @Override
    public int simplify(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeConstrain(function, domain);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeConstrain(int function, int domain) {
        assert domain != FALSE;
        if (function == TRUE || function == FALSE || domain == TRUE) {
            return function;
        }
        if (domain == function) {
            return TRUE;
        }
        if (domain == complement(function)) {
            return FALSE;
        }

        int functionNode = positive(function);
        boolean func = functionNode != function;

        if (cache.lookupConstrain(functionNode, domain)) {
            return complementIf(cache.lookupResult(), func);
        }
        int hash = cache.lookupHash();

        int domainNode = positive(domain);
        boolean domc = domainNode != domain;
        int functionVar = decisionVariable(functionNode);
        int domainVar = decisionVariable(domainNode);

        int result;
        if (functionVar == domainVar) {
            int[] functionChildren = table.children(functionNode);
            int[] domainChildren = table.children(domainNode);
            int variableDomainSize = functionChildren.length;

            int[] resultChildren = new int[variableDomainSize];
            int firstDecision = -1;
            int workStack = 0;
            for (int val = 0; val < variableDomainSize; val++) {
                int domainChild = complementIf(domainChildren[val], domc);
                if (domainChild == FALSE) {
                    resultChildren[val] = FALSE;
                } else {
                    if (firstDecision == -1) {
                        firstDecision = val;
                    } else {
                        firstDecision = -2;
                    }
                    resultChildren[val] = table.pushToWorkStack(computeConstrain(functionChildren[val], domainChild));
                    workStack += 1;
                }
            }
            if (firstDecision >= 0) {
                result = resultChildren[firstDecision];
            } else {
                assert firstDecision == -2;
                result = makeFunction(functionVar, resultChildren);
            }
            table.popFromWorkStack(workStack);
        } else if (functionVar < domainVar) {
            int[] functionChildren = table.children(functionNode);
            int variableDomainSize = functionChildren.length;
            int[] resultChildren = new int[variableDomainSize];
            for (int i = 0; i < variableDomainSize; i++) {
                resultChildren[i] = table.pushToWorkStack(computeConstrain(functionChildren[i], domain));
            }
            result = makeFunction(functionVar, resultChildren);
            table.popFromWorkStack(variableDomainSize);
        } else {
            int[] domainChildren = table.children(domainNode);
            int disjunction = complementIf(domainChildren[0], domc);
            for (int i = 1; i < domainChildren.length; i++) {
                table.pushToWorkStack(disjunction);
                disjunction = computeOr(disjunction, complementIf(domainChildren[i], domc));
                table.popFromWorkStack();
            }
            result = computeConstrain(functionNode, table.pushToWorkStack(disjunction));
            table.popFromWorkStack();
        }
        cache.putConstrain(hash, functionNode, domain, result);
        return complementIf(result, func);
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("MDD@%d(%d)", table.size(), System.identityHashCode(this));
    }

    @Override
    public String statistics() {
        return table.getStatistics() + '\n' + cache.getStatistics();
    }

    // Utility

    static final class NodeSolutionIterator implements Iterator<int[]> {
        private static final int NON_PATH_NODE = PLACEHOLDER;

        private final MddImpl mdd;
        private final int[] assignment;
        private final BitSet support;
        private final int variableCount;
        private final int[] path;
        private final boolean[] pathLookingFor;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private boolean hasNextAssignment;
        private final int rootVariable;

        NodeSolutionIterator(MddImpl mdd, int function, BitSet support) {
            // Require at least one possible solution to exist.
            assert mdd.isValidNonConstantFunction(function) || function == TRUE;
            variableCount = mdd.numberOfVariables();

            // Assignments don't make much sense otherwise
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(mdd.support(function), support);

            this.mdd = mdd;
            this.support = support;
            this.path = new int[variableCount];
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new int[variableCount];
            rootVariable = mdd.decisionVariable(function);
            assert support.get(rootVariable);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = positive(function);
            pathLookingFor[rootVariable] = mdd.isPositive(function);

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
            boolean currentLookingFor;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
                currentLookingFor = pathLookingFor[rootVariable];
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
                                hasNextAssignment = false; // NOPMD

                                // TODO This should be constant time to determine?
                                // TODO This only needs to run if we set the first non-path variable to 1
                                for (int i = support.nextSetBit(var + 1); i >= 0; i = support.nextSetBit(i + 1)) {
                                    if (path[i] == NON_PATH_NODE && assignment[i] < mdd.variableDomain[i] - 1) {
                                        hasNextAssignment = true;
                                        break;
                                    }
                                }
                            }
                            assert mdd.evaluate(
                                    complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);
                            return assignment;
                        }
                    }
                }

                // Situation: All non-path variables are set to zero, and we need to find a new path
                assert IntStream.range(0, variableCount)
                        .noneMatch(var -> path[var] == NON_PATH_NODE && assignment[var] > 0);
                assert hasNextPath
                        : "Expected another path after " + Arrays.toString(assignment) + ", node:\n"
                                + mdd.table.treeToString(path[rootVariable]);

                // Backtrack on the current path until we find a node that we can increase to non-false branch
                // to find a new path
                // TODO Use highestLowVariableWithNonFalseHighBranch?
                currentNode = path[leafNodeVariable];
                currentLookingFor = pathLookingFor[leafNodeVariable];
                int branchVar = leafNodeVariable;

                //noinspection LabeledStatement
                outer:
                while (true) {
                    assert path[branchVar] != NON_PATH_NODE;

                    int[] children = mdd.table.children(currentNode);
                    int val = assignment[branchVar] + 1;
                    while (val < children.length) {
                        if (!isFalse(children[val], currentLookingFor)) {
                            assignment[branchVar] = val;
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
                    currentLookingFor = pathLookingFor[branchVar];
                }
                assert assignment[branchVar] < mdd.variableDomain[branchVar];
                assert leafNodeVariable >= highestSwitchableVariable;
                assert mdd.decisionVariable(currentNode) == branchVar;

                // currentNode is the deepest node we could increase; set the value and descend the tree
                Arrays.fill(assignment, branchVar + 1, leafNodeVariable + 1, 0);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);

                assert path[branchVar] == currentNode;
                int child = mdd.follow(currentNode, assignment[branchVar]);
                currentNode = positive(child);
                if (currentNode != child) {
                    currentLookingFor = !currentLookingFor;
                }
                assert mdd.isPositive(currentNode);
                assert !isFalse(currentNode, currentLookingFor);
                leafNodeVariable = branchVar;

                // We maxed out the candidate for increase, clear this information
                if (highestSwitchableVariable == branchVar) {
                    highestSwitchableVariable = -1;
                    /*for (int val = assignment[branchVar] + 1; val < children.length; val++) {
                        if (children[val] != FALSE_NODE) {
                            highestSwitchableVariable = branchVar;
                            break;
                        }
                    }*/
                }
            }

            // Situation: Either the currentNode valuation was just increased or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (!isTrue(currentNode, currentLookingFor)) {
                assert mdd.isPositive(currentNode) && !mdd.isConstant(currentNode);

                leafNodeVariable = mdd.table.variable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathLookingFor[leafNodeVariable] = currentLookingFor;
                assert support.get(leafNodeVariable);

                int[] children = mdd.table.children(currentNode);
                int domain = children.length;
                int val = 0;
                while (isFalse(children[val], currentLookingFor)) {
                    val += 1;
                }
                assignment[leafNodeVariable] = val;
                int child = mdd.follow(currentNode, val);

                if (!hasNextPath) {
                    val += 1;
                    while (val < domain) {
                        if (isFalse(children[val], currentLookingFor)) {
                            val += 1;
                        } else {
                            hasNextPath = true;
                            highestSwitchableVariable = leafNodeVariable; // NOPMD
                            break;
                        }
                    }
                }

                currentNode = positive(child);
                if (currentNode != child) {
                    currentLookingFor = !currentLookingFor;
                }
            }
            assert mdd.evaluate(complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);

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

    static final class NodePathIterator implements Iterator<int[]> {
        private static final int NON_PATH_NODE = PLACEHOLDER;

        private final MddImpl mdd;
        private final int variableCount;
        private final int[] assignment;
        private final BitSet pathSupport;
        private final int[] path;
        private final boolean[] pathLookingFor;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private final int rootVariable;

        NodePathIterator(MddImpl mdd, int function) {
            // Require at least one possible solution to exist.
            assert mdd.isValidNonConstantFunction(function);
            variableCount = mdd.numberOfVariables();

            this.mdd = mdd;
            this.path = new int[variableCount];
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new int[variableCount];
            this.pathSupport = new BitSet(variableCount);
            rootVariable = mdd.decisionVariable(function);

            Arrays.fill(assignment, -1);
            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = positive(function);
            pathSupport.set(rootVariable);
            pathLookingFor[rootVariable] = mdd.isPositive(function);

            leafNodeVariable = 0;
            hasNextPath = true;
        }

        @Override
        public boolean hasNext() {
            return hasNextPath;
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public int[] next() {
            assert IntStream.range(0, variableCount).allMatch(i -> pathSupport.get(i) != (path[i] == NON_PATH_NODE));

            int currentNode;
            boolean currentLookingFor;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
                currentLookingFor = pathLookingFor[rootVariable];
            } else {
                assert IntStream.range(0, variableCount)
                        .noneMatch(var -> path[var] == NON_PATH_NODE && assignment[var] > 0);
                assert hasNextPath
                        : "Expected another path after " + Arrays.toString(assignment) + ", node:\n"
                                + mdd.table.treeToString(path[rootVariable]);

                // Backtrack on the current path until we find a node that we can increase to non-false branch
                // to find a new path
                // TODO Use highestLowVariableWithNonFalseHighBranch?
                currentNode = path[leafNodeVariable];
                currentLookingFor = pathLookingFor[leafNodeVariable];
                int branchVar = leafNodeVariable;

                //noinspection LabeledStatement
                outer:
                while (true) {
                    assert path[branchVar] != NON_PATH_NODE;

                    int[] children = mdd.table.children(currentNode);
                    int val = assignment[branchVar] + 1;
                    while (val < children.length) {
                        if (!isFalse(children[val], currentLookingFor)) {
                            assignment[branchVar] = val;
                            //noinspection BreakStatementWithLabel
                            break outer;
                        }
                        val += 1;
                    }
                    assert val == children.length;

                    // This node does not give us another branch, backtrack over the path until we get to
                    // the next element of the path
                    branchVar = pathSupport.previousSetBit(branchVar - 1);
                    if (branchVar == -1) {
                        throw new NoSuchElementException("No next element");
                    }
                    currentNode = path[branchVar];
                    currentLookingFor = pathLookingFor[branchVar];
                }
                assert assignment[branchVar] < mdd.variableDomain[branchVar];
                assert leafNodeVariable >= highestSwitchableVariable;
                assert mdd.decisionVariable(currentNode) == branchVar;
                assert pathSupport.get(branchVar);

                // currentNode is the deepest node we could increase; set the value and descend the tree
                Arrays.fill(assignment, branchVar + 1, leafNodeVariable + 1, -1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                pathSupport.clear(branchVar + 1, leafNodeVariable + 1);

                assert path[branchVar] == currentNode;
                int child = mdd.follow(currentNode, assignment[branchVar]);
                currentNode = positive(child);
                if (currentNode != child) {
                    currentLookingFor = !currentLookingFor;
                }
                assert mdd.isPositive(currentNode);
                assert !isFalse(currentNode, currentLookingFor);
                leafNodeVariable = branchVar;

                // We maxed out the candidate for increase, clear this information
                if (highestSwitchableVariable == branchVar) {
                    highestSwitchableVariable = -1;
                    /*for (int val = assignment[branchVar] + 1; val < children.length; val++) {
                        if (children[val] != FALSE_NODE) {
                            highestSwitchableVariable = branchVar;
                            break;
                        }
                    }*/
                }
            }

            // Situation: Either the currentNode valuation was just increased or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (!isTrue(currentNode, currentLookingFor)) {
                assert mdd.isPositive(currentNode) && !mdd.isConstant(currentNode);

                leafNodeVariable = mdd.table.variable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathSupport.set(leafNodeVariable);
                pathLookingFor[leafNodeVariable] = currentLookingFor;

                int[] children = mdd.table.children(currentNode);
                int domain = children.length;
                int val = 0;
                while (isFalse(children[val], currentLookingFor)) {
                    val += 1;
                }
                assignment[leafNodeVariable] = val;
                int child = mdd.follow(currentNode, val);

                if (!hasNextPath) {
                    val += 1;
                    while (val < domain) {
                        if (isFalse(children[val], currentLookingFor)) {
                            val += 1;
                        } else {
                            hasNextPath = true;
                            highestSwitchableVariable = leafNodeVariable; // NOPMD
                            break;
                        }
                    }
                }

                currentNode = positive(child);
                if (currentNode != child) {
                    currentLookingFor = !currentLookingFor;
                }
            }
            assert mdd.evaluate(complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);

            return assignment;
        }
    }

    public static class MddTable extends NodeTable.Multi {
        private final MddImpl mdd;

        MddTable(MddImpl mdd, int initialSize) {
            super(initialSize);
            this.mdd = mdd;
        }

        @Override
        public boolean isConstantPointer(int pointer) {
            return mdd.isConstant(pointer);
        }

        @Override
        public boolean isValidPointer(int pointer) {
            return mdd.isValidFunction(pointer);
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int[] children = tree[node];
            boolean all = true;
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE && !doIsNoneMarkedBelow(childNode)) {
                    all = false;
                    break;
                }
            }
            return all;
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node) {
            int[] children = tree[node];
            boolean all = true;
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE && !doIsAllMarkedBelow(childNode)) {
                    all = false;
                    break;
                }
            }
            return all;
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark) {
            int[] children = tree[node];
            int sum = 0;
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE) {
                    sum += doSetMarkBelow(childNode, mark);
                }
            }
            return sum;
        }

        @Override
        protected void recurseForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
            int[] children = tree[node];
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE) {
                    doForEachVariable(childNode, action, filter, depthLimit);
                }
            }
        }

        @Override
        public int nodeFor(int pointer) {
            return mdd.nodeFor(pointer);
        }

        @Override
        public boolean ensureCapacity() {
            if (freeNodeCount() > size() / 4) {
                return false;
            }

            NodeTable table = mdd.table;
            int currentSize = table.size();
            int approximateDeadNodeCount = table.approximateDeadNodeCount();
            if (mdd.configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
                logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes", new Object[] {
                    this, currentSize, approximateDeadNodeCount
                });

                @SuppressWarnings("NumericCastThatLosesPrecision")
                // If we only can free few nodes, it is not worth the effort
                int maximumReferencedNodes = (int) (currentSize * 0.7);

                // Leaves all referenced nodes marked
                int referencedNodes = table.markAllReferencedNodes();
                if (referencedNodes <= maximumReferencedNodes) {
                    int reclaimedNodes = table.reclaimUnmarkedNodes();
                    logger.log(Level.FINE, "Collected {0} nodes", reclaimedNodes);
                    mdd.clearCacheAfterGC(reclaimedNodes);
                    assert mdd.check();
                    return false;
                }

                logger.log(Level.FINER, "Not enough free nodes");
                table.invalidateUnmarkedNodes();
            }
            //noinspection NumericCastThatLosesPrecision
            table.grow((int) (currentSize * mdd.configuration.growthFactor()));
            mdd.cache.tableSizeChanged();
            assert mdd.check();
            return true;
        }

        @Override
        public String format(int pointer) {
            return mdd.format(pointer);
        }
    }
}
