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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/* Implementation notes:
 * - Variable numbers increase while descending the tree of a particular node.
 */
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "SameParameterValue"
})
final class BddImpl extends BooleanBase<BitSet, BinaryPath> implements Bdd {
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
            assert table.isValidDecisionNode(currentNode);
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
            assert table.isValidDecisionNode(currentNode);
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
        satisfyingAssignment(function, path);
        return path;
    }

    @Override
    public Optional<BitSet> satisfyingAssignmentIn(int function, int domain) {
        assert isValidFunction(function);

        if (function == FALSE || domain == FALSE) {
            return Optional.empty();
        }

        BitSet path = new BitSet(numberOfVariables);
        return satisfyingAssignmentInRecursive(function, domain, path) ? Optional.of(path) : Optional.empty();
    }

    private boolean satisfyingAssignment(int function, BitSet path) {
        assert function != FALSE;

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
        return true;
    }

    private boolean satisfyingAssignmentInRecursive(int function1, int function2, BitSet path) {
        if (function1 == FALSE || function2 == FALSE) {
            return false;
        }
        if (function1 == TRUE) {
            if (function2 == TRUE) {
                return true;
            }
            path.clear(decisionVariable(function2), numberOfVariables);
            return satisfyingAssignment(function2, path);
        }
        if (function2 == TRUE) {
            path.clear(decisionVariable(function1), numberOfVariables);
            return satisfyingAssignment(function1, path);
        }
        if (function1 == function2) {
            path.clear(decisionVariable(function1), numberOfVariables);
            return satisfyingAssignment(function1, path);
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

        boolean fun1c = isComplementFunction(function1);
        int node1 = complementIf(function1, fun1c);

        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = positive(function2);
            if (satisfyingAssignmentInRecursive(
                    complementIf(table.low(node1), fun1c), complementIf(table.low(node2), fun2c), path)) {
                path.clear(fun1var);
                return true;
            }
            path.set(fun1var);
            return satisfyingAssignmentInRecursive(
                    complementIf(table.high(node1), fun1c), complementIf(table.high(node2), fun2c), path);
        }
        // fun1var < fun2var

        if (satisfyingAssignmentInRecursive(complementIf(table.low(node1), fun1c), function2, path)) {
            path.clear(fun1var);
            return true;
        }
        path.set(fun1var);
        return satisfyingAssignmentInRecursive(complementIf(table.high(node1), fun1c), function2, path);
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

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            return BitSets.powerSetIterator(support);
        }

        return new BooleanFunctionSolutionIterator(this, function, support);
    }

    @Override
    public Iterator<BitSet> solutionIteratorIn(int function, int domain) {
        // TODO Native
        return solutionIterator(and(function, domain));
    }

    @Override
    public Iterator<BitSet> solutionIteratorIn(int function, int domain, BitSet support) {
        // TODO Native
        return solutionIterator(and(function, domain), support);
    }

    @Override
    public Iterator<BinaryPath> pathIterator(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Collections.emptyIterator();
        }
        if (function == TRUE) {
            BitSet set = new BitSet();
            return Collections.singleton(new BinaryPath(set, set)).iterator();
        }

        return new BooleanFunctionPathIterator(this, function);
    }

    @Override
    public Iterator<BinaryPath> pathIteratorIn(int function, int domain) {
        // TODO Native
        return pathIterator(and(function, domain));
    }

    @Override
    public void forEachPath(int function, Consumer<? super BinaryPath> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        if (function == TRUE) {
            action.accept(new BinaryPath(new BitSet(0), new BitSet(0)));
            return;
        }

        int numberOfVariables = numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(numberOfVariables), new BitSet(numberOfVariables));
        forEachPathRecursive(positive(function), null, numberOfVariables, path, action, isPositive(function));
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super BinaryPath> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        if (function == TRUE || relevantSet.isEmpty()) {
            action.accept(new BinaryPath(new BitSet(0), new BitSet(0)));
            return;
        }

        int highestVariable = relevantSet.length() - 1;
        BinaryPath path = new BinaryPath(new BitSet(highestVariable + 1), new BitSet(highestVariable + 1));
        forEachPathRecursive(positive(function), relevantSet, highestVariable, path, action, isPositive(function));
    }

    private void forEachPathRecursive(
            int node,
            @Nullable BitSet support,
            int depthLimit,
            BinaryPath path,
            Consumer<? super BinaryPath> action,
            boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            action.accept(path);
            return;
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        if (variable > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path);
            return;
        }

        int lowEdge = table.low(node);
        int highNode = table.high(node);
        boolean relevant = support == null || support.get(variable);

        if (relevant) {
            path.support.set(variable);
        }

        if (!isFalse(lowEdge, lookingFor)) {
            forEachPathRecursive(
                    positive(lowEdge), support, depthLimit, path, action, isPositive(lowEdge) == lookingFor);
        }
        if (!isFalse(highNode, lookingFor)) {
            if (relevant) {
                path.assignment.set(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, action, lookingFor);
                assert path.assignment.get(variable);
                path.assignment.clear(variable);
            } else {
                assert !path.assignment.get(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, action, lookingFor);
            }
        }

        assert relevant == path.support.get(variable);
        if (relevant) {
            path.support.clear(variable);
        }
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super BinaryPath> predicate) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return false;
        }
        if (function == TRUE) {
            return predicate.test(new BinaryPath(new BitSet(0), new BitSet(0)));
        }

        int numberOfVariables = numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(numberOfVariables), new BitSet(numberOfVariables));
        return anyPathMatchesRecursive(positive(function), path, predicate, isPositive(function));
    }

    private boolean anyPathMatchesRecursive(
            int node, BinaryPath path, Predicate<? super BinaryPath> predicate, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            return predicate.test(path);
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        int lowEdge = table.low(node);

        path.support.set(variable);
        if (!isFalse(lowEdge, lookingFor)
                && anyPathMatchesRecursive(positive(lowEdge), path, predicate, isPositive(lowEdge) == lookingFor)) {
            return true;
        }

        int highNode = table.high(node);
        if (!isFalse(highNode, lookingFor)) {
            path.assignment.set(variable);
            if (anyPathMatchesRecursive(highNode, path, predicate, lookingFor)) {
                return true;
            }
            assert path.assignment.get(variable);
            path.assignment.clear(variable);
        }

        path.support.clear(variable);
        return false;
    }

    @Override
    public boolean anyPathMatchesIn(int function, int domain, Predicate<? super BinaryPath> predicate) {
        // TODO Native
        return anyPathMatches(and(function, domain), predicate);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        assert isValidFunction(function);

        return countSatisfyingAssignmentsRecursive(function, -1);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        return countSatisfyingAssignments(function).divide(TWO.pow(numberOfVariables - support.cardinality()));
    }

    @Override
    public BigInteger countSatisfyingAssignmentsIn(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        return countSatisfyingAssignmentsInRecursive(function, domain, -1);
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int function, int previousVar) {
        assert isValidFunction(function);

        if (function == TRUE) {
            return TWO.pow(numberOfVariables - previousVar - 1);
        }
        if (function == FALSE) {
            return BigInteger.ZERO;
        }

        int node = positive(function);
        int decisionVar = table.variable(node);
        boolean complement = function != node;

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return (complement ? TWO.pow(numberOfVariables - decisionVar).subtract(cacheLookup) : cacheLookup)
                    .shiftLeft(decisionVar - previousVar - 1);
        }
        int hash = cache.lookupHash();

        int low = complementIf(table.low(node), complement);
        int high = complementIf(table.high(node), complement);
        BigInteger result = countSatisfyingAssignmentsRecursive(low, decisionVar)
                .add(countSatisfyingAssignmentsRecursive(high, decisionVar));

        cache.putSatisfaction(
                hash,
                node,
                complement ? TWO.pow(numberOfVariables - decisionVar).subtract(result) : result);
        return result.shiftLeft(decisionVar - previousVar - 1);
    }

    private BigInteger countSatisfyingAssignmentsInRecursive(int function1, int function2, int previousVar) {
        if (function1 == TRUE) {
            return countSatisfyingAssignmentsRecursive(function2, previousVar);
        }
        if (function2 == TRUE) {
            return countSatisfyingAssignmentsRecursive(function1, previousVar);
        }
        if (function1 == FALSE || function2 == FALSE) {
            return BigInteger.ZERO;
        }
        if (function1 == function2) {
            return countSatisfyingAssignmentsRecursive(function1, previousVar);
        }
        if (function1 == complement(function2)) {
            return BigInteger.ZERO;
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

        boolean fun1c = isComplementFunction(function1);
        int node1 = complementIf(function1, fun1c);
        int fun1low = complementIf(table.low(node1), fun1c);
        int fun1high = complementIf(table.high(node1), fun1c);

        // TODO Cache
        BigInteger result;
        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = positive(function2);
            result = countSatisfyingAssignmentsInRecursive(fun1low, complementIf(table.low(node2), fun2c), fun1var)
                    .add(countSatisfyingAssignmentsInRecursive(
                            fun1high, complementIf(table.high(node2), fun2c), fun1var));
        } else { // fun1var < fun2var
            result = countSatisfyingAssignmentsInRecursive(fun1low, function2, fun1var)
                    .add(countSatisfyingAssignmentsInRecursive(fun1high, function2, fun1var));
        }
        result = result.shiftLeft(fun1var - previousVar - 1);
        // cache.putAnd(hash, function1, function2, result);
        assert result.compareTo(BigInteger.ZERO) >= 0;
        return result;
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

        int highestReplacedVariable = -1;

        // Canonicalize the replacement array and find the largest changed variable
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] == placeholder()) {
                variableMapping[i] = this.variableNodes[i];
            } else if (variableMapping[i] != this.variableNodes[i]) {
                highestReplacedVariable = i;
            }
        }
        // The mapping is identity
        if (highestReplacedVariable == -1) {
            return function;
        }

        // Detect simple cases where everything is identity or true / false
        // Primary advantage: Delegate to simpler caches, the effective code paths are pretty similar
        boolean isRestrict = true;
        boolean isConstant = true;
        for (int i = 0; i < variableMapping.length; i++) {
            if (!isConstant(variableMapping[i])) {
                isConstant = false;
                if (variableMapping[i] != this.variableNodes[i]) {
                    isRestrict = false;
                    break;
                }
            }
        }
        if (isConstant) {
            BitSet constantValues = new BitSet(variableMapping.length + 1);
            for (int i = 0; i < variableMapping.length; i++) {
                assert isConstant(variableMapping[i]);
                constantValues.set(i, variableMapping[i] == TRUE);
            }
            return evaluate(function, constantValues) ? TRUE : FALSE;
        }
        if (isRestrict) {
            BitSet restrictValues = new BitSet(variableMapping.length + 1);
            BitSet restrictSupport = new BitSet(variableMapping.length + 1);
            for (int i = 0; i < variableMapping.length; i++) {
                if (isConstant(variableMapping[i])) {
                    restrictSupport.set(i);
                    restrictValues.set(i, variableMapping[i] == TRUE);
                }
            }
            return restrict(function, restrictSupport, restrictValues);
        }

        // Guard the elements
        table.pushToWorkStack(function);
        int workStackCount = 1;
        for (int j : variableMapping) {
            assert isValidFunction(j);
            int node = positive(j);
            if (node != TRUE && !table.isSaturatedNode(node)) {
                table.pushToWorkStack(j);
                workStackCount++;
            }
        }

        // Main recursion
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

        int lookup = cache.lookupCompose(node);
        if (lookup != placeholder()) {
            return complementIf(lookup, isComplemented);
        }
        int hash = cache.lookupHash();

        int variableReplacementNode = variableNodes[variable];
        int result;
        // Short-circuit constant replacements.
        if (variableReplacementNode == TRUE) {
            result = computeCompose(table.high(node), variableNodes, highestReplacedVariable);
        } else if (variableReplacementNode == FALSE) {
            result = computeCompose(table.low(node), variableNodes, highestReplacedVariable);
        } else {
            int lowCompose =
                    table.pushToWorkStack(computeCompose(table.low(node), variableNodes, highestReplacedVariable));
            int highCompose =
                    table.pushToWorkStack(computeCompose(table.high(node), variableNodes, highestReplacedVariable));
            result = computeIfThenElse(variableReplacementNode, highCompose, lowCompose);
            table.popFromWorkStack(2);
        }
        cache.putCompose(hash, node, result);
        return complementIf(result, isComplemented);
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

    @Override
    public int andSimplify(int function1, int function2, int domain) {
        assert isValidFunction(function1) && isValidFunction(function2) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function1, function2, domain);
        int result = computeAndSimplify(function1, function2, domain);
        table.popFromWorkStack(3);
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

        int lookup = cache.lookupAnd(function1, function2);
        if (lookup != placeholder()) {
            return lookup;
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
            int node2 = positive(function2);
            lowNode = table.pushToWorkStack(computeAnd(fun1low, complementIf(table.low(node2), fun2c)));
            highNode = table.pushToWorkStack(computeAnd(fun1high, complementIf(table.high(node2), fun2c)));
        } else { // fun1var < fun2var
            lowNode = table.pushToWorkStack(computeAnd(fun1low, function2));
            highNode = table.pushToWorkStack(computeAnd(fun1high, function2));
        }
        int result = makeFunction(fun1var, lowNode, highNode);
        table.popFromWorkStack(2);
        cache.putAnd(hash, function1, function2, result);
        return result;
    }

    private int computeAndSimplify(int function1, int function2, int domain) {
        assert domain != FALSE;
        if (domain == TRUE) {
            return computeAnd(function1, function2);
        }
        if (domain == function1) {
            assert computeSimplify(computeAnd(function1, function2), domain) == computeSimplify(function2, domain);
            return computeSimplify(function2, domain);
        }
        if (domain == function2) {
            assert computeSimplify(computeAnd(function1, function2), domain) == computeSimplify(function1, domain);
            return computeSimplify(function1, domain);
        }
        if (domain == complement(function1) || domain == complement(function2)) {
            return FALSE;
        }

        if (function1 == TRUE) {
            return computeSimplify(function2, domain);
        }
        if (function2 == TRUE) {
            return computeSimplify(function1, domain);
        }
        if (function1 == FALSE || function2 == FALSE) {
            return FALSE;
        }
        if (function1 == function2) {
            return computeSimplify(function1, domain);
        }
        if (function1 == complement(function2)) {
            assert computeSimplify(computeAnd(function1, function2), domain) == FALSE;
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

        /*
        if (cache.lookupAnd(function1, function2)) {
            return cache.lookupResult();
        }
        int hash = cache.lookupHash();
         */

        boolean fun1c = isComplementFunction(function1);
        int node1 = complementIf(function1, fun1c);
        int fun1low = complementIf(table.low(node1), fun1c);
        int fun1high = complementIf(table.high(node1), fun1c);

        boolean domc = isComplementFunction(domain);
        int domainNode = complementIf(domain, domc);
        int domainVar = decisionVariable(domainNode);

        int result;
        if (domainVar < fun1var) {
            int domainLow = complementIf(table.low(domainNode), domc);
            int domainHigh = complementIf(table.high(domainNode), domc);
            if (domainLow == FALSE) {
                result = computeAndSimplify(function1, function2, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeAndSimplify(function1, function2, domainLow);
            } else {
                int lowNode = table.pushToWorkStack(computeAndSimplify(function1, function2, domainLow));
                int highNode = table.pushToWorkStack(computeAndSimplify(function1, function2, domainHigh));
                result = makeFunction(domainVar, lowNode, highNode);
                table.popFromWorkStack(2);
            }
        } else if (domainVar == fun1var) {
            int domainLow = complementIf(table.low(domainNode), domc);
            int domainHigh = complementIf(table.high(domainNode), domc);

            if (domainLow == FALSE) {
                result = computeAndSimplify(fun1high, fun2var == fun1var ? highOf(function2) : function2, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeAndSimplify(fun1low, fun2var == fun1var ? lowOf(function2) : function2, domainLow);
            } else {
                result = computeAndSimplifyInner(fun1var, fun2var, fun1low, fun1high, function2, domainLow, domainHigh);
            }
        } else {
            result = computeAndSimplifyInner(fun1var, fun2var, fun1low, fun1high, function2, domain, domain);
        }

        // cache.putAnd(hash, function1, function2, result);
        //        table.pushToWorkStack(result);
        //        table.popFromWorkStack();
        return result;
    }

    private int computeAndSimplifyInner(
            int fun1var, int fun2var, int fun1low, int fun1high, int function2, int lowDomain, int highDomain) {
        assert fun1var <= fun2var;
        int lowNode;
        int highNode;
        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = positive(function2);
            lowNode = table.pushToWorkStack(
                    computeAndSimplify(fun1low, complementIf(table.low(node2), fun2c), lowDomain));
            highNode = table.pushToWorkStack(
                    computeAndSimplify(fun1high, complementIf(table.high(node2), fun2c), highDomain));
        } else { // fun1var < fun2var
            lowNode = table.pushToWorkStack(computeAndSimplify(fun1low, function2, lowDomain));
            highNode = table.pushToWorkStack(computeAndSimplify(fun1high, function2, highDomain));
        }
        int result = makeFunction(fun1var, lowNode, highNode);
        table.popFromWorkStack(2);
        return result;
    }

    private int computeOr(int function1, int function2) {
        return complement(computeAnd(complement(function1), complement(function2)));
    }

    @Override
    public int xor(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeXor(function1, function2);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
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

        int lookup = cache.lookupXor(function1, function2);
        if (lookup != placeholder()) {
            return lookup;
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
        int result = makeFunction(node1var, lowNode, highNode);
        table.popFromWorkStack(2);
        cache.putXor(hash, function1, function2, result);
        return result;
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
        cache.initExists(quantifiedVariables);
        table.pushToWorkStack(function);
        int result = existsRecursive(function, quantifiedVariables);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int existsRecursive(int function, BitSet quantifiedVariables) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        boolean func = isComplementFunction(function);
        int node = complementIf(function, func);
        int variable = table.variable(node);
        int nextQuantifiedVariable = quantifiedVariables.nextSetBit(variable);
        if (nextQuantifiedVariable == -1) {
            return function;
        }
        if (isVariableOrNegated(function)) {
            if (variable == nextQuantifiedVariable) {
                return TRUE;
            }
            return function;
        }

        int lookup = cache.lookupExists(function);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int lowExists =
                table.pushToWorkStack(existsRecursive(complementIf(table.low(node), func), quantifiedVariables));
        int highExists =
                table.pushToWorkStack(existsRecursive(complementIf(table.high(node), func), quantifiedVariables));
        int result;
        if (nextQuantifiedVariable > variable) {
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward
            result = makeFunction(variable, lowExists, highExists);
        } else {
            // variable == nextVariable, i.e. "quantify out" the current node.
            result = computeOr(lowExists, highExists);
        }

        table.popFromWorkStack(2);
        cache.putExists(hash, function, result);
        return result;
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

        int lookup = cache.lookupIfThenElse(ifNormalized, thenNormalized, elseNormalized);
        if (lookup != placeholder()) {
            return complementIf(lookup, complement);
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

        int lookup = cache.lookupImplies(function1, function2);
        if (lookup != placeholder()) {
            return lookup == TRUE;
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

        int lookup = cache.lookupIntersects(function1, function2);
        if (lookup != placeholder()) {
            return lookup == TRUE;
        }
        int hash = cache.lookupHash();

        boolean fun1c = isComplementFunction(function1);
        int node1 = complementIf(function1, fun1c);
        int fun1low = complementIf(table.low(node1), fun1c);
        int fun1high = complementIf(table.high(node1), fun1c);

        boolean result;
        if (fun1var == fun2var) {
            boolean fun2c = isComplementFunction(function2);
            int node2 = positive(function2);
            result = intersectsRecursive(fun1low, complementIf(table.low(node2), fun2c))
                    || intersectsRecursive(fun1high, complementIf(table.high(node2), fun2c));
        } else { // fun1var < fun2var
            result = intersectsRecursive(fun1low, function2) || intersectsRecursive(fun1high, function2);
        }
        cache.putIntersects(hash, function1, function2, result);
        return result;
    }

    @Override
    public int simplify(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }
        if (domain == TRUE) {
            return function;
        }

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeSimplify(function, domain);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeSimplify(int function, int domain) {
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

        boolean func = isComplementFunction(function);
        int node = complementIf(function, func);

        int lookup = cache.lookupSimplify(node, domain);
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }
        int hash = cache.lookupHash();

        int domainNode = positive(domain);
        boolean domc = domainNode != domain;
        int functionVar = decisionVariable(node);
        int domainVar = decisionVariable(domainNode);

        int result;
        if (functionVar == domainVar) {
            int domainLow = complementIf(table.low(domainNode), domc);
            int domainHigh = complementIf(table.high(domainNode), domc);
            if (domainLow == FALSE) {
                result = computeSimplify(table.high(node), domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeSimplify(table.low(node), domainLow);
            } else {
                int lowNode = table.pushToWorkStack(computeSimplify(table.low(node), domainLow));
                int highNode = table.pushToWorkStack(computeSimplify(table.high(node), domainHigh));
                result = makeFunction(functionVar, lowNode, highNode);
                table.popFromWorkStack(2);
            }
        } else if (functionVar < domainVar) {
            int lowNode = table.pushToWorkStack(computeSimplify(table.low(node), domain));
            int highNode = table.pushToWorkStack(computeSimplify(table.high(node), domain));
            result = makeFunction(functionVar, lowNode, highNode);
            table.popFromWorkStack(2);
        } else {
            int domainLow = complementIf(table.low(domainNode), domc);
            int domainHigh = complementIf(table.high(domainNode), domc);
            if (domainLow == FALSE) {
                result = computeSimplify(node, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeSimplify(node, domainLow);
            } else {
                int lowNode = table.pushToWorkStack(computeSimplify(node, domainLow));
                int highNode = table.pushToWorkStack(computeSimplify(node, domainHigh));
                result = makeFunction(domainVar, lowNode, highNode);
                table.popFromWorkStack(2);
            }
        }
        cache.putSimplify(hash, node, domain, result);
        return complementIf(result, func);
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

    static final class BooleanFunctionPathIterator implements Iterator<BinaryPath> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final BddImpl bdd;
        private final int variableCount;
        private final BitSet assignment;
        private final int[] path;
        private final BitSet pathSupport;
        private final boolean[] pathLookingFor;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private final int rootVariable;

        BooleanFunctionPathIterator(BddImpl bdd, int function) {
            assert bdd.isValidNonConstantFunction(function);
            variableCount = bdd.numberOfVariables();

            this.bdd = bdd;
            this.path = new int[variableCount];
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new BitSet(variableCount);
            this.pathSupport = new BitSet(variableCount);
            rootVariable = bdd.decisionVariable(function);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = positive(function);
            pathSupport.set(rootVariable);
            pathLookingFor[rootVariable] = bdd.isPositive(function);

            leafNodeVariable = 0;
            hasNextPath = true;
        }

        @Override
        public boolean hasNext() {
            return hasNextPath;
        }

        @Override
        public BinaryPath next() {
            assert IntStream.range(0, variableCount).allMatch(i -> pathSupport.get(i) || path[i] == NON_PATH_NODE);

            int currentNode;
            boolean currentLookingFor;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
                currentLookingFor = pathLookingFor[rootVariable];
            } else {
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
                    branchVar = pathSupport.previousSetBit(branchVar - 1);
                    if (branchVar == -1) {
                        throw new NoSuchElementException("No next element");
                    }
                    currentNode = path[branchVar];
                    currentLookingFor = pathLookingFor[branchVar];
                }
                assert !assignment.get(branchVar) && bdd.table.high(currentNode) != FALSE;
                assert leafNodeVariable >= highestSwitchableVariable;
                assert bdd.decisionVariable(currentNode) == branchVar;
                assert pathSupport.get(branchVar);

                // currentNode is the lowest node we can switch high; set the value and descend the tree
                assignment.clear(branchVar + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                pathSupport.clear(branchVar + 1, leafNodeVariable + 1);

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
                pathSupport.set(leafNodeVariable);
                pathLookingFor[leafNodeVariable] = currentLookingFor;

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

            return new BinaryPath(assignment, pathSupport);
        }
    }

    private static final class BddTable extends NodeTable.Binary {
        private final BddImpl bdd;

        BddTable(BddImpl bdd, int initialSize) {
            super(initialSize);
            this.bdd = bdd;
        }

        @Override
        public boolean isValidConstant(int pointer) {
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
        protected void markLeafNodeIfManaged(int node, boolean mark) {
            // Nothing to do
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
        public int treeNodeFor(int pointer) {
            return bdd.nodeFor(pointer);
        }

        @Override
        protected boolean isLeafNode(int node) {
            return node == TRUE;
        }

        @Override
        protected boolean isValidLeafNode(int node) {
            return node == TRUE;
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
            //noinspection NumericCastThatLosesPrecision
            table.grow((int) (currentSize * bdd.configuration.growthFactor()));
            bdd.cache.tableSizeChanged();
            assert bdd.check();
            return true;
        }

        @Override
        protected boolean anyManagedLeafMarked() {
            return false;
        }

        @Override
        protected void unmarkAllManagedLeafs() {
            // Nothing to do
        }

        @Override
        protected boolean isLeafNodeMarkedOrUnmanaged(int leaf) {
            return true;
        }

        @Override
        protected boolean isLeafUnmarkedOrUnmanaged(int leaf) {
            return true;
        }

        @Override
        public String format(int pointer) {
            return bdd.format(pointer);
        }
    }
}
