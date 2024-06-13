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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/* Implementation notes:
 * - Due to the implementation of all operations, variable numbers increase while descending the
 *   tree of a particular node.
 */
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "PMD.TooManyFields",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "ValueOfIncrementOrDecrementUsed",
    "NestedAssignment",
    "SameParameterValue"
})
final class BddImpl extends BooleanBase<BitSet> implements Bdd, NodeBasedDecisionDiagram {
    private static final Logger logger = Logger.getLogger(BddImpl.class.getName());

    private final BooleanCache cache;
    private int numberOfVariables;
    private int[] variableNodes;
    private final BddConfiguration configuration;
    private final NodeTable.Binary table;

    BddImpl(BddConfiguration configuration) {
        this.configuration = configuration;
        this.table = new BddTable(this, configuration.initialSize());

        cache = new BooleanCache(this);
        variableNodes = new int[32];
        numberOfVariables = 0;
    }

    @Override
    public BddConfiguration configuration() {
        return configuration;
    }

    @Override
    protected NodeTable table() {
        return table;
    }

    @Override
    BooleanCache cache() {
        return cache;
    }

    // Nodes

    @Override
    public int highOf(int function) {
        assert isValidNonConstantFunction(function);
        int node = positive(function);
        return complementIf(table.high(node), node != function);
    }

    @Override
    public int lowOf(int function) {
        assert isValidNonConstantFunction(function);
        int node = positive(function);
        return complementIf(table.low(node), node != function);
    }

    // Variables and base nodes

    @Override
    public int numberOfVariables() {
        return numberOfVariables;
    }

    @Override
    public int variableFunction(int variableNumber) {
        assert 0 <= variableNumber && variableNumber < numberOfVariables;
        return variableNodes[variableNumber];
    }

    @Override
    public int createVariable() {
        int variableNode = table.saturateNode(makeFunction(numberOfVariables, FALSE, TRUE));

        if (numberOfVariables == variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, variableNodes.length * 2);
        }
        variableNodes[numberOfVariables] = variableNode;
        numberOfVariables++;

        cache.variablesChanged();

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
            variableNodes = Arrays.copyOf(variableNodes, Math.max(variableNodes.length * 2, newSize));
        }

        int[] newVariableNodes = new int[count];

        for (int i = 0; i < count; i++) {
            int variable = numberOfVariables + i;

            int variableNode = table.saturateNode(makeFunction(variable, FALSE, TRUE));
            newVariableNodes[i] = variableNode;
            this.variableNodes[variable] = variableNode;
        }
        numberOfVariables += count;

        cache.variablesChanged();
        // table.ensureWorkStackSize(numberOfVariables * 2);

        return newVariableNodes;
    }

    @Override
    public boolean isVariable(int function) {
        assert isValidFunction(function);
        return !isConstant(function)
                && isPositive(function)
                && table.low(function) == FALSE
                && table.high(function) == TRUE;
    }

    @Override
    public boolean isVariableNegated(int function) {
        assert isValidFunction(function);
        return isVariable(complement(function));
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        assert isValidFunction(function);
        return isVariable(positive(function));
    }

    private int makeFunction(int variable, int lowFunction, int highFunction) {
        if (lowFunction == highFunction) {
            return lowFunction;
        }
        int highEdge = positive(highFunction);
        boolean isHighComplement = highFunction < highEdge;
        int lowEdge = complementIf(lowFunction, isHighComplement);
        return complementIf(table.makeNode(variable, lowEdge, highEdge), isHighComplement);
    }

    // Reading

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidNode(currentNode);
            if (assignment[decisionVariable(currentNode)]) {
                currentNode = table.high(currentNode);
            } else {
                int low = table.low(currentNode);
                currentNode = positive(low);
                if (currentNode != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public boolean evaluate(int function, BitSet assignment) {
        assert isValidFunction(function);

        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidNode(currentNode);
            if (assignment.get(table.variable(currentNode))) {
                currentNode = table.high(currentNode);
            } else {
                int lowNode = table.low(currentNode);
                currentNode = positive(lowNode);
                if (currentNode != lowNode) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public BitSet satisfyingAssignment(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            throw new NoSuchElementException("False has no solution");
        }

        BitSet path = new BitSet(numberOfVariables);
        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;

        while (currentNode != TRUE) {
            int low = table.low(currentNode);
            if (isFalse(low, lookingFor)) {
                int highNode = table.high(currentNode);
                int variable = table.variable(currentNode);

                path.set(variable);
                currentNode = highNode;
            } else {
                currentNode = positive(low);
                if (currentNode != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        assert lookingFor;
        return path;
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            return BitSets.powerSetIterator(numberOfVariables);
        }

        BitSet support = new BitSet(numberOfVariables);
        support.set(0, numberOfVariables);
        return new BooleanFunctionSolutionIterator(this, function, support);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function, BitSet support) {
        assert isValidFunction(function);

        if (support.isEmpty() || function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            return BitSets.powerSetIterator(support);
        }

        return new BooleanFunctionSolutionIterator(this, function, support);
    }

    @Override
    public void forEachPath(int function, BiConsumer<BitSet, BitSet> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        if (function == TRUE) {
            action.accept(new BitSet(0), new BitSet(0));
            return;
        }

        int numberOfVariables = numberOfVariables();
        BitSet path = new BitSet(numberOfVariables);
        BitSet pathSupport = new BitSet(numberOfVariables);

        forEachPathRecursive(
                positive(function), null, numberOfVariables, path, pathSupport, action, isPositive(function));
    }

    @Override
    public void forEachPath(int function, BitSet relevantSet, BiConsumer<BitSet, BitSet> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        if (function == TRUE || relevantSet.isEmpty()) {
            action.accept(new BitSet(0), new BitSet(0));
            return;
        }

        int highestVariable = relevantSet.length() - 1;
        BitSet path = new BitSet(highestVariable + 1);
        BitSet pathSupport = new BitSet(highestVariable + 1);

        forEachPathRecursive(
                positive(function), relevantSet, highestVariable, path, pathSupport, action, isPositive(function));
    }

    private void forEachPathRecursive(
            int node,
            @Nullable BitSet support,
            int depthLimit,
            BitSet path,
            BitSet pathSupport,
            BiConsumer<BitSet, BitSet> action,
            boolean lookingFor) {

        if (node == TRUE) {
            assert lookingFor;
            action.accept(path, pathSupport);
            return;
        }
        assert table.isValidNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        if (variable > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path, pathSupport);
            return;
        }

        int lowEdge = table.low(node);
        int highNode = table.high(node);
        boolean relevant = support == null || support.get(variable);

        if (relevant) {
            pathSupport.set(variable);
        }

        if (!isFalse(lowEdge, lookingFor)) {
            forEachPathRecursive(
                    positive(lowEdge),
                    support,
                    depthLimit,
                    path,
                    pathSupport,
                    action,
                    isPositive(lowEdge) == lookingFor);
        }
        if (!isFalse(highNode, lookingFor)) {
            if (relevant) {
                path.set(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, pathSupport, action, lookingFor);
                assert path.get(variable);
                path.clear(variable);
            } else {
                assert !path.get(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, pathSupport, action, lookingFor);
            }
        }

        assert relevant == pathSupport.get(variable);
        if (relevant) {
            pathSupport.clear(variable);
        }
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return BigInteger.ZERO;
        }
        if (function == TRUE) {
            return TWO.pow(numberOfVariables);
        }

        int variable = decisionVariable(function);
        BigInteger satisfyingBelow = countSatisfyingAssignmentsRecursive(positive(function), isPositive(function));
        return TWO.pow(variable).multiply(satisfyingBelow);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        return countSatisfyingAssignments(function).divide(TWO.pow(numberOfVariables - support.cardinality()));
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node, boolean lookingFor) {
        assert isValidFunction(node);

        int nodeVar = table.variable(node);

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return lookingFor
                    ? cacheLookup
                    : TWO.pow(numberOfVariables - nodeVar).subtract(cacheLookup);
        }
        int hash = cache.lookupHash();

        int lowEdge = table.low(node);
        BigInteger lowCount =
                doCountSatisfyingAssignments(positive(lowEdge), nodeVar, isPositive(lowEdge) == lookingFor);
        BigInteger highCount = doCountSatisfyingAssignments(table.high(node), nodeVar, lookingFor);

        BigInteger result = lowCount.add(highCount);
        cache.putSatisfaction(
                hash,
                node,
                lookingFor ? result : TWO.pow(numberOfVariables - nodeVar).subtract(result));
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int node, int previousVar, boolean lookingFor) {
        if (isFalse(node, lookingFor)) {
            return BigInteger.ZERO;
        }
        if (isTrue(node, lookingFor)) {
            return TWO.pow(numberOfVariables - previousVar - 1);
        }
        BigInteger multiplier = TWO.pow(decisionVariable(node) - previousVar - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(node, lookingFor));
    }

    // General operations

    @Override
    public int compose(int function, int[] variableMapping) {
        assert isValidFunction(function);
        assert variableMapping.length <= numberOfVariables;

        if (isConstant(function)) {
            return function;
        }

        assert table.isWorkStackEmpty();
        // Guard the elements and replace placeholder by actual variable reference
        table.pushToWorkStack(function);
        int workStackCount = 1;
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] == placeholder()) {
                variableMapping[i] = this.variableNodes[i];
            } else {
                assert isValidFunction(variableMapping[i]);
                int node = positive(variableMapping[i]);
                if (node != TRUE && !table.isSaturatedNode(node)) {
                    table.pushToWorkStack(variableMapping[i]);
                    workStackCount++;
                }
            }
        }

        int highestReplacedVariable = variableMapping.length - 1;
        // Optimise the replacement array
        // Note: Could also detect the case that everything is identity or true / false; which would be "restrict"
        for (int i = variableMapping.length - 1; i >= 0; i--) {
            if (variableMapping[i] != this.variableNodes[i]) {
                highestReplacedVariable = i;
                break;
            }
        }
        if (highestReplacedVariable == -1) {
            table.popFromWorkStack(workStackCount);
            assert table.isWorkStackEmpty();
            return function;
        }

        cache.initCompose(variableMapping, highestReplacedVariable);
        int result = computeCompose(function, variableMapping, highestReplacedVariable);
        table.popFromWorkStack(workStackCount);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeCompose(int function, int[] variableNodes, int highestReplacedVariable) {
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

        if (cache.lookupCompose(node)) {
            return complementIf(cache.lookupResult(), isComplemented);
        }
        int hash = cache.lookupHash();

        int variableReplacementNode = variableNodes[variable];
        int resultNode;
        // Short-circuit constant replacements.
        if (variableReplacementNode == TRUE) {
            resultNode = computeCompose(table.high(node), variableNodes, highestReplacedVariable);
        } else if (variableReplacementNode == FALSE) {
            resultNode = computeCompose(table.low(node), variableNodes, highestReplacedVariable);
        } else {
            int lowCompose =
                    table.pushToWorkStack(computeCompose(table.low(node), variableNodes, highestReplacedVariable));
            int highCompose =
                    table.pushToWorkStack(computeCompose(table.high(node), variableNodes, highestReplacedVariable));
            resultNode = computeIfThenElse(variableReplacementNode, highCompose, lowCompose);
            table.popFromWorkStack(2);
        }
        cache.putCompose(hash, node, resultNode);
        return complementIf(resultNode, isComplemented);
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isValidFunction(function);

        if (restrictedVariables.isEmpty()) {
            return function;
        }
        if (isConstant(function)) {
            return function;
        }

        assert table.isWorkStackEmpty();
        int highestReplacement = restrictedVariables.length() - 1;
        int[] composeArray = new int[highestReplacement + 1];
        for (int variable = 0; variable <= highestReplacement; variable++) { // NOPMD
            if (restrictedVariables.get(variable)) {
                composeArray[variable] = restrictedVariableValues.get(variable) ? TRUE : FALSE;
            } else {
                composeArray[variable] = variableNodes[variable];
            }
        }

        table.pushToWorkStack(function);
        cache.initCompose(composeArray, highestReplacement);
        int result = computeCompose(function, composeArray, highestReplacement);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    // Bdd operations

    @Override
    public int conjunction(int... variables) {
        int node = TRUE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            node = computeAnd(table.pushToWorkStack(node), variableNodes[variable]);
            table.popFromWorkStack();
        }
        return node;
    }

    @Override
    public int conjunction(BitSet variables) {
        int node = TRUE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            node = computeAnd(table.pushToWorkStack(node), variableNodes[variable]);
            table.popFromWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(int... variables) {
        int node = FALSE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            int node1 = table.pushToWorkStack(node);
            node = computeOr(node1, variableNodes[variable]);
            table.popFromWorkStack();
        }
        return node;
    }

    @Override
    public int disjunction(BitSet variables) {
        int node = FALSE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            int node1 = table.pushToWorkStack(node);
            node = computeOr(node1, variableNodes[variable]);
            table.popFromWorkStack();
        }
        return node;
    }

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
        int fun1low = complementIf(table.low(node1), fun1c);
        int fun1high = complementIf(table.high(node1), fun1c);

        int lowNode;
        int highNode;
        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = complementIf(function2, fun2c);
            lowNode = table.pushToWorkStack(computeAnd(fun1low, complementIf(table.low(node2), fun2c)));
            highNode = table.pushToWorkStack(computeAnd(fun1high, complementIf(table.high(node2), fun2c)));
        } else { // fun1var < fun2var
            lowNode = table.pushToWorkStack(computeAnd(fun1low, function2));
            highNode = table.pushToWorkStack(computeAnd(fun1high, function2));
        }
        int resultNode = makeFunction(fun1var, lowNode, highNode);
        table.popFromWorkStack(2);
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

        int lowNode;
        int highNode;
        boolean node1c = function1 != node1;
        if (node1var == node2var) {
            boolean node2c = function2 != node2;
            lowNode = table.pushToWorkStack(
                    computeXor(complementIf(table.low(node1), node1c), complementIf(table.low(node2), node2c)));
            highNode = table.pushToWorkStack(
                    computeXor(complementIf(table.high(node1), node1c), complementIf(table.high(node2), node2c)));
        } else { // node1var < node2var
            lowNode = table.pushToWorkStack(computeXor(complementIf(table.low(node1), node1c), function2));
            highNode = table.pushToWorkStack(computeXor(complementIf(table.high(node1), node1c), function2));
        }
        int resultNode = makeFunction(node1var, lowNode, highNode);
        table.popFromWorkStack(2);
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
        if (isVariableOrNegated(function)) {
            if (variable == currentCubeNodeVariable) {
                return exists ? TRUE : FALSE;
            }
            return function;
        }

        if (cache.lookupQuantification(function, exists)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();

        boolean isComplement = node != function;
        int lowExists = table.pushToWorkStack(complementIf(
                quantifyRecursive(table.low(node), quantifiedVariables, isComplement != exists), isComplement));
        int highExists = table.pushToWorkStack(complementIf(
                quantifyRecursive(table.high(node), quantifiedVariables, isComplement != exists), isComplement));
        int resultNode;

        if (currentCubeNodeVariable > variable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            resultNode = makeFunction(variable, lowExists, highExists);
        } else {
            // variable == nextVariable, i.e. "quantify out" the current node.
            resultNode = exists ? computeOr(lowExists, highExists) : computeAnd(lowExists, highExists);
        }

        table.popFromWorkStack(2);
        cache.putQuantification(hash, function, exists, resultNode);
        return resultNode;
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
        int ifLowNode;
        int ifHighNode;

        if (ifVar == minVar) {
            ifLowNode = table.low(ifNormalized);
            ifHighNode = table.high(ifNormalized);
        } else {
            ifLowNode = ifNormalized;
            ifHighNode = ifNormalized;
        }

        int thenHighNode;
        int thenLowNode;
        if (thenVar == minVar) {
            thenLowNode = table.low(thenNormalized);
            thenHighNode = table.high(thenNormalized);
        } else {
            thenLowNode = thenNormalized;
            thenHighNode = thenNormalized;
        }

        int elseHighNode;
        int elseLowNode;
        if (elseVar == minVar) {
            boolean elsec = elseNode != elseNormalized;
            elseLowNode = complementIf(table.low(elseNode), elsec);
            elseHighNode = complementIf(table.high(elseNode), elsec);
        } else {
            elseLowNode = elseNormalized;
            elseHighNode = elseNormalized;
        }

        int lowNode = table.pushToWorkStack(computeIfThenElse(ifLowNode, thenLowNode, elseLowNode));
        int highNode = table.pushToWorkStack(computeIfThenElse(ifHighNode, thenHighNode, elseHighNode));
        int result = makeFunction(minVar, lowNode, highNode);
        table.popFromWorkStack(2);
        cache.putIfThenElse(hash, ifNormalized, thenNormalized, elseNormalized, result);
        return complementIf(result, complement);
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

        boolean result;
        if (node1var == node2var) {
            result = impliesRecursive(complementIf(table.low(node1), fun1c), complementIf(table.low(node2), fun2c))
                    && impliesRecursive(complementIf(table.high(node1), fun1c), complementIf(table.high(node2), fun2c));
        } else if (node1var < node2var) {
            result = impliesRecursive(complementIf(table.low(node1), fun1c), function2)
                    && impliesRecursive(complementIf(table.high(node1), fun1c), function2);
        } else {
            result = impliesRecursive(function1, complementIf(table.low(node2), fun2c))
                    && impliesRecursive(function1, complementIf(table.high(node2), fun2c));
        }
        cache.putImplies(hash, function1, function2, result);
        return result;
    }

    // MTBDD

    @Override
    public <V> MtBdd<V> createMtBdd(Class<V> clazz) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> MtBdd<List<V>> intersect(List<MtBdd<? extends V>> mtBddList, Class<V> clazz) {
        assert mtBddList.stream().allMatch(m -> clazz.isAssignableFrom(m.valueType()));
        throw new UnsupportedOperationException();
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("BDD@%d(%d)", table.size(), System.identityHashCode(this));
    }

    @Override
    public String statistics() {
        return table.getStatistics() + '\n' + cache.getStatistics();
    }

    // Utility

    static final class BooleanFunctionSolutionIterator implements Iterator<BitSet> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final BddImpl bdd;
        private final BitSet assignment;
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

        BooleanFunctionSolutionIterator(BddImpl bdd, int function, BitSet support) {
            // Require at least one possible solution to exist.
            assert bdd.isValidNonConstantFunction(function) || function == TRUE;
            variableCount = bdd.numberOfVariables();

            // Assignments don't make much sense otherwise
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(bdd.support(function), support);

            this.bdd = bdd;
            this.support = support;
            this.path = new int[variableCount];
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new BitSet(variableCount);
            rootVariable = bdd.decisionVariable(function);
            assert support.get(rootVariable);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = positive(function);
            pathLookingFor[rootVariable] = bdd.isPositive(function);

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
            boolean currentLookingFor;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
                currentLookingFor = pathLookingFor[rootVariable];
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
                                hasNextAssignment = false; // NOPMD

                                // TODO This should be constant time to determine?
                                // TODO This only needs to run if we set the first non-path variable to 1
                                for (int i = support.nextSetBit(var + 1); i >= 0; i = support.nextSetBit(i + 1)) {
                                    if (path[i] == NON_PATH_NODE && !assignment.get(i)) {
                                        hasNextAssignment = true;
                                        break;
                                    }
                                }
                            }
                            assert bdd.evaluate(
                                    complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);
                            return assignment;
                        }
                    }
                }

                // Situation: All non-path variables are set to zero, and we need to find a new path
                assert IntStream.range(0, variableCount)
                        .noneMatch(index -> path[index] == NON_PATH_NODE && assignment.get(index));
                assert hasNextPath
                        : "Expected another path after " + assignment + ", node:\n"
                                + bdd.table.treeToString(path[rootVariable]);

                // Backtrack on the current path until we find a node set to low and non-false high branch
                // to find a new path in the BDD
                // TODO Use highestLowVariableWithNonFalseHighBranch?
                currentNode = path[leafNodeVariable];
                currentLookingFor = pathLookingFor[leafNodeVariable];
                int branchVar = leafNodeVariable;

                while (assignment.get(branchVar) || isFalse(bdd.table.high(currentNode), currentLookingFor)) {
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
                assert !assignment.get(branchVar) && bdd.table.high(currentNode) != FALSE;
                assert leafNodeVariable >= highestSwitchableVariable;
                assert bdd.decisionVariable(currentNode) == branchVar;

                // currentNode is the lowest node we can switch high; set the value and descend the tree
                assignment.clear(branchVar + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);

                assignment.set(branchVar);
                assert path[branchVar] == currentNode;
                currentNode = bdd.table.high(currentNode);
                assert bdd.isPositive(currentNode);
                assert !isFalse(currentNode, currentLookingFor);
                leafNodeVariable = branchVar;

                // We flipped the candidate for low->high transition, clear this information
                if (highestSwitchableVariable == leafNodeVariable) {
                    highestSwitchableVariable = -1;
                }
            }

            // Situation: The currentNode valuation was just flipped to 1 or we are in initial state.
            // Descend the tree, searching for a solution and determine if there is a next assignment.

            // If there is a possible path higher up, there definitely are more solutions
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (!isTrue(currentNode, currentLookingFor)) {
                assert bdd.isPositive(currentNode) && !bdd.isConstant(currentNode);

                leafNodeVariable = bdd.decisionVariable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathLookingFor[leafNodeVariable] = currentLookingFor;
                assert support.get(leafNodeVariable);

                int low = bdd.table.low(currentNode);
                if (isFalse(low, currentLookingFor)) {
                    // Descend high path
                    assignment.set(leafNodeVariable);
                    currentNode = bdd.table.high(currentNode);
                } else {
                    // If there is a non-false high node, we will be able to swap this node later on, so we
                    // definitely have a next assignment. On the other hand, if there is no such node, the
                    // last possible assignment has been reached, as there are no more possible switches
                    // higher up in the tree.
                    if (!hasNextPath && !isFalse(bdd.table.high(currentNode), currentLookingFor)) {
                        hasNextPath = true;
                        highestSwitchableVariable = leafNodeVariable; // NOPMD
                    }
                    currentNode = positive(low);
                    if (currentNode != low) {
                        currentLookingFor = !currentLookingFor;
                    }
                }
            }
            assert bdd.evaluate(complementIf(path[rootVariable], !pathLookingFor[rootVariable]), assignment);

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

    @SuppressWarnings("PMD")
    abstract static class MtBddImpl<V> implements MtBdd<V> {
        private final BddImpl bdd;
        private final Class<V> valueType;
        private final List<V> values;
        private final Map<V, Integer> valueToNode = new HashMap<>();

        MtBddImpl(BddImpl bdd, Class<V> valueType) {
            this.bdd = bdd;
            this.valueType = valueType;
            this.values = new ArrayList<>();
        }

        @Override
        public Bdd bdd() {
            return bdd;
        }

        @Override
        public Class<V> valueType() {
            return valueType;
        }
    }

    private static final class BddTable extends NodeTable.Binary {
        private final BddImpl bdd;

        BddTable(BddImpl bdd, int initialSize) {
            super(initialSize);
            this.bdd = bdd;
        }

        @Override
        public boolean isConstantPointer(int pointer) {
            return bdd.isConstant(pointer);
        }

        @Override
        public boolean isValidPointer(int pointer) {
            return bdd.isValidFunction(pointer);
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsNoneMarkedBelow(low)) && (high == TRUE || doIsNoneMarkedBelow(high));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsAllMarkedBelow(low)) && (high == TRUE || doIsAllMarkedBelow(high));
        }

        @Override
        protected int recurseApproximateNodeCount(int node) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE ? 0 : doApproximateNodeCount(low)) + (high == TRUE ? 0 : doApproximateNodeCount(high));
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE ? 0 : doSetMarkBelow(low, mark)) + (high == TRUE ? 0 : doSetMarkBelow(high, mark));
        }

        @Override
        protected void recurseForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
            int low = positive(low(node));
            int high = high(node);
            if (low != TRUE) {
                doForEachVariable(low, action, filter, depthLimit);
            }
            if (high != TRUE) {
                doForEachVariable(high, action, filter, depthLimit);
            }
        }

        @Override
        public int nodeFor(int pointer) {
            return bdd.nodeFor(pointer);
        }

        @Override
        public boolean ensureCapacity() {
            if (freeNodeCount() > size() / 4) {
                return false;
            }

            NodeTable table = bdd.table;
            int currentSize = table.size();
            int approximateDeadNodeCount = table.approximateDeadNodeCount();
            if (bdd.configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
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
                    bdd.clearCacheAfterGC(reclaimedNodes);
                    assert bdd.check();
                    return false;
                }

                logger.log(Level.FINER, "Not enough free nodes");
                table.invalidateUnmarkedNodes();
            }
            table.grow((int) (currentSize * bdd.configuration.growthFactor()));
            bdd.cache.tableSizeChanged();
            assert bdd.check();
            return true;
        }

        @Override
        public String format(int pointer) {
            return bdd.format(pointer);
        }
    }
}
