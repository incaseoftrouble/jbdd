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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "AssignmentToMethodParameter",
    "DuplicatedCode",
    "AssertWithSideEffects"
})
public class MddImpl extends BooleanBase<int[], int[]> implements Mdd {

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
        return complementIf(table.followUnchecked(node, value), node != function);
    }

    private boolean isValidValue(int function, int value) {
        return 0 <= value && value < variableDomain[decisionVariable(function)];
    }

    int @Nullable [] childrenIf(int function, boolean decides) {
        return decides ? table.childrenUnchecked(positive(function)) : null;
    }

    static int childAt(int function, int @Nullable [] children, int value) {
        return children == null ? function : complementIf(children[value], isComplementFunction(function));
    }

    // Variables and base nodes

    @Override
    public int numberOfVariables() {
        return numberOfVariables;
    }

    @Override
    public int declareVariable(int domain) {
        assert domain >= 2;
        assert accessGuard.acquire();
        if (numberOfVariables == variableDomain.length) {
            variableDomain = Arrays.copyOf(variableDomain, variableDomain.length * 2);
        }
        variableDomain[numberOfVariables] = domain;
        numberOfVariables++;

        cache.variablesChanged();

        assert accessGuard.release();
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

        assert accessGuard.acquire();
        int result = makeFunction(variable, children);
        assert accessGuard.release();
        return result;
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
            assert table.isValidDecisionNode(currentNode);
            int value = assignment[decisionVariable(currentNode)];
            assert isValidValue(currentNode, value);
            int child = table.followUnchecked(currentNode, value);
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
        boolean exists = satisfyingAssignment(function, path);
        assert exists;
        return path;
    }

    private boolean satisfyingAssignment(int function, int[] path) {
        assert function != FALSE;

        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;

        while (currentNode != TRUE) {
            int[] children = table.childrenUnchecked(currentNode);
            for (int val = 0; val < children.length; val++) {
                int child = children[val];
                if (!isFalse(child, lookingFor)) {
                    int variable = table.variable(currentNode);

                    path[variable] = val;
                    currentNode = positive(child);
                    if (currentNode != child) {
                        lookingFor = !lookingFor;
                    }
                    break;
                }
            }
        }
        assert lookingFor;
        return true;
    }

    @SuppressWarnings("OptionalContainsCollection")
    @Override
    public Optional<int[]> satisfyingAssignmentIn(int function, int domain) {
        // TODO Native
        int and = and(function, domain);
        return and == FALSE ? Optional.empty() : Optional.of(satisfyingAssignment(and));
    }

    @Override
    public void forEachSolutionIn(int function, int domain, Consumer<? super int[]> action) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (function == FALSE || domain == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        forEachSolutionInRecursive(function, domain, null, 0, new int[numberOfVariables], action);
        assert accessGuard.release();
    }

    @Override
    public void forEachSolutionIn(int function, int domain, BitSet support, Consumer<? super int[]> action) {
        assert isValidFunction(function) && isValidFunction(domain);
        assert BitSets.isSubset(support(function), support) && BitSets.isSubset(support(domain), support);

        if (function == FALSE || domain == FALSE) {
            return;
        }

        assert accessGuard.acquire();
        int[] variables = support.stream().toArray();
        forEachSolutionInRecursive(function, domain, variables, 0, new int[numberOfVariables], action);
        assert accessGuard.release();
    }

    private void forEachSolutionInRecursive(
            int function1,
            int function2,
            int @Nullable [] support,
            int index,
            int[] assignment,
            Consumer<? super int[]> action) {
        if (function1 == FALSE || function2 == FALSE) {
            return;
        }
        if (index == (support == null ? numberOfVariables : support.length)) {
            assert function1 == TRUE && function2 == TRUE;
            action.accept(assignment);
            return;
        }

        int variable = support == null ? index : support[index];

        int[] children1 = childrenIf(function1, !isConstant(function1) && decisionVariable(function1) == variable);
        int[] children2 = childrenIf(function2, !isConstant(function2) && decisionVariable(function2) == variable);

        int domain = variableDomain[variable];
        for (int value = 0; value < domain; value++) {
            assignment[variable] = value;
            forEachSolutionInRecursive(
                    childAt(function1, children1, value),
                    childAt(function2, children2, value),
                    support,
                    index + 1,
                    assignment,
                    action);
        }
        assignment[variable] = 0;
    }

    @Override
    public Cursor<int[]> solutionCursor(int function) {
        BitSet support = new BitSet(numberOfVariables);
        support.set(0, numberOfVariables);
        return solutionCursor(function, support);
    }

    @Override
    public Cursor<int[]> solutionCursor(int function, BitSet support) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE) {
            return Cursors.powerSet(variableDomain, support);
        }
        return new SolutionCursor(this, function, support);
    }

    @Override
    public Cursor<int[]> solutionCursorIn(int function, int domain) {
        // TODO Native - see BddImpl, which walks the two together instead of conjoining them
        return solutionCursor(and(function, domain));
    }

    @Override
    public Cursor<int[]> solutionCursorIn(int function, int domain, BitSet support) {
        // TODO Native - see BddImpl, which walks the two together instead of conjoining them
        return solutionCursor(and(function, domain), support);
    }

    @Override
    public Cursor<int[]> pathCursor(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE) {
            int[] path = new int[numberOfVariables];
            Arrays.fill(path, -1);
            return Cursors.singleton(path);
        }
        return new PathCursor(this, function);
    }

    @Override
    public void forEachPath(int function, Consumer<? super int[]> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);

        if (function == TRUE) {
            action.accept(path);
            assert accessGuard.release();
            return;
        }

        int numberOfVariables = numberOfVariables();
        forEachPathRecursive(positive(function), null, numberOfVariables, path, action, isPositive(function));
        assert accessGuard.release();
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super int[]> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);
        if (function == TRUE || relevantSet.isEmpty()) {
            action.accept(path);
            assert accessGuard.release();
            return;
        }

        int maxVariable = relevantSet.length() - 1;

        forEachPathRecursive(positive(function), relevantSet, maxVariable, path, action, isPositive(function));
        assert accessGuard.release();
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
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        if (variable > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path);
            return;
        }

        boolean relevant = support == null || support.get(variable);

        int[] children = table.childrenUnchecked(node);
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
        assert accessGuard.acquire();
        int[] path = new int[numberOfVariables];
        Arrays.fill(path, -1);
        if (function == TRUE) {
            boolean result = predicate.test(path);
            assert accessGuard.release();
            return result;
        }

        boolean result = anyPathMatchesRecursive(positive(function), path, predicate, isPositive(function));
        assert accessGuard.release();
        return result;
    }

    private boolean anyPathMatchesRecursive(
            int node, int[] path, Predicate<? super int[]> predicate, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            return predicate.test(path);
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);

        int[] children = table.childrenUnchecked(node);
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
        assert accessGuard.acquire();
        BigInteger result = countSatisfyingAssignmentsRecursive(positive(function), isPositive(function));
        assert accessGuard.release();
        return base.multiply(result);
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

    @Override
    public BigInteger countSatisfyingAssignmentsIn(int function, int domain) {
        // TODO Native
        return countSatisfyingAssignments(and(function, domain));
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

        int[] children = table.childrenUnchecked(node);
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

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeAnd(function1, function2);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
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

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int lookup = cache.lookupAnd(function1, function2);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int fun1var = decisionVariable(function1);
        int fun2var = decisionVariable(function2);
        int variable = Math.min(fun1var, fun2var);
        int domain = variableDomain[variable];

        int[] children1 = childrenIf(function1, fun1var == variable);
        int[] children2 = childrenIf(function2, fun2var == variable);

        int[] resultChildren = new int[domain];
        for (int val = 0; val < domain; val++) {
            resultChildren[val] = table.pushToWorkStack(
                    computeAnd(childAt(function1, children1, val), childAt(function2, children2, val)));
        }
        int resultNode = makeFunction(variable, resultChildren);
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
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2);
        int ret = computeXor(function1, function2);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return ret;
    }

    private int computeXor(int function1, int function2) {
        boolean negate = isComplementFunction(function1) ^ isComplementFunction(function2);
        function1 = positive(function1);
        function2 = positive(function2);

        if (function1 == function2) {
            return complementIf(FALSE, negate);
        }
        if (function1 == TRUE) {
            return complementIf(complement(function2), negate);
        }
        if (function2 == TRUE) {
            return complementIf(complement(function1), negate);
        }

        if (function1 > function2) {
            int functionSwap = function1;
            function1 = function2;
            function2 = functionSwap;
        }

        int lookup = cache.lookupXor(function1, function2);
        if (lookup != placeholder()) {
            return complementIf(lookup, negate);
        }
        int hash = cache.lookupHash();

        int fun1var = decisionVariable(function1);
        int fun2var = decisionVariable(function2);
        int variable = Math.min(fun1var, fun2var);
        int domain = variableDomain[variable];

        // Both operands are positive here, so childAt's parity fix-up is a no-op on the way down.
        int[] children1 = childrenIf(function1, fun1var == variable);
        int[] children2 = childrenIf(function2, fun2var == variable);

        int[] resultChildren = new int[domain];
        for (int val = 0; val < domain; val++) {
            resultChildren[val] = table.pushToWorkStack(
                    computeXor(childAt(function1, children1, val), childAt(function2, children2, val)));
        }
        int resultNode = makeFunction(variable, resultChildren);
        table.popFromWorkStack(domain);
        cache.putXor(hash, function1, function2, resultNode);
        return complementIf(resultNode, negate);
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

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        cache.initExists(quantifiedVariables);
        table.pushToWorkStack(function);
        int result = existsRecursive(function, quantifiedVariables);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
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
        int currentCubeNodeVariable = quantifiedVariables.nextSetBit(variable);
        if (currentCubeNodeVariable == -1) {
            return function;
        }

        int[] children = table.childrenUnchecked(node);
        boolean currentVariableIsQuantified = variable == currentCubeNodeVariable;

        if (currentVariableIsQuantified) {
            for (int child : children) {
                if (isTrue(child, !func)) {
                    return TRUE;
                }
            }
        }

        int lookup = cache.lookupExists(function);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int domain = children.length;
        int resultNode;
        if (currentVariableIsQuantified) {
            resultNode = falseFunction();
            for (int child : children) {
                table.pushToWorkStack(resultNode);
                int quantifiedBranch =
                        table.pushToWorkStack(existsRecursive(complementIf(child, func), quantifiedVariables));
                resultNode = computeOr(resultNode, quantifiedBranch);
                table.popFromWorkStack(2);
            }
        } else {
            assert currentCubeNodeVariable > variable;
            // The variable of this node is smaller than the variable looked for - only propagate the
            // quantification downward

            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        table.pushToWorkStack(existsRecursive(complementIf(children[val], func), quantifiedVariables));
            }
            resultNode = makeFunction(variable, resultChildren);
            table.popFromWorkStack(domain);
        }
        cache.putExists(hash, function, resultNode);
        return resultNode;
    }

    @Override
    public boolean implies(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        boolean result = !intersectsRecursive(function1, complement(function2));
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public boolean intersects(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        boolean result = intersectsRecursive(function1, function2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
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

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int lookup = cache.lookupIntersects(function1, function2);
        if (lookup != placeholder()) {
            return lookup == TRUE;
        }
        int hash = cache.lookupHash();

        int fun1var = decisionVariable(function1);
        int fun2var = decisionVariable(function2);
        int variable = Math.min(fun1var, fun2var);
        int domain = variableDomain[variable];

        int[] children1 = childrenIf(function1, fun1var == variable);
        int[] children2 = childrenIf(function2, fun2var == variable);

        boolean result = false;
        for (int val = 0; val < domain; val++) {
            if (intersectsRecursive(childAt(function1, children1, val), childAt(function2, children2, val))) {
                result = true;
                break;
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

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        int maxReplacedVariable = values.length - 1;
        while (maxReplacedVariable >= 0 && values[maxReplacedVariable] == -1) {
            maxReplacedVariable -= 1;
        }
        if (maxReplacedVariable == -1) {
            assert accessGuard.release();
            return function;
        }
        assert values[maxReplacedVariable] != -1;

        table.pushToWorkStack(function);
        // cache.initRemapping(values, maxReplacedVariable);
        int result = computeRestrict(function, values, maxReplacedVariable);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeRestrict(int function, int[] values, int maxReplacedVariable) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        int variable = decisionVariable(function);
        if (variable > maxReplacedVariable) {
            return function;
        }

        int node = positive(function);
        boolean isComplemented = node != function;

        //        if (cache.lookupRemapping(node)) {
        //            return complementIf(cache.lookupResult(), isComplemented);
        //        }
        //        int hash = cache.lookupHash();

        int[] children = table.childrenUnchecked(node);
        int domain = children.length;
        int resultNode;

        int variableReplacementValue = values[variable];
        if (variableReplacementValue == -1) {
            int[] resultChildren = new int[domain];
            for (int val = 0; val < domain; val++) {
                resultChildren[val] =
                        table.pushToWorkStack(computeRestrict(children[val], values, maxReplacedVariable));
            }
            resultNode = makeFunction(variable, resultChildren);
            table.popFromWorkStack(domain);
        } else {
            resultNode = computeRestrict(children[variableReplacementValue], values, maxReplacedVariable);
        }
        // cache.putRemapping(hash, node, resultNode);
        return complementIf(resultNode, isComplemented);
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        assert isValidFunction(ifFunction) && isValidFunction(thenFunction) && isValidFunction(elseFunction);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(ifFunction, thenFunction, elseFunction);
        int result = computeIfThenElse(ifFunction, thenFunction, elseFunction);
        table.popFromWorkStack(3);
        assert table.workStacksEmpty();
        assert accessGuard.release();
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
        int elseVar = table.variable(positive(elseNormalized));

        int minVar = Math.min(ifVar, Math.min(thenVar, elseVar));
        int[] ifTree = childrenIf(ifNormalized, ifVar == minVar);
        int[] thenTree = childrenIf(thenNormalized, thenVar == minVar);
        int[] elseTree = childrenIf(elseNormalized, elseVar == minVar);
        int minVarDomain = variableDomain[minVar];
        int[] resultChildren = new int[minVarDomain];
        for (int val = 0; val < minVarDomain; val++) {
            resultChildren[val] = table.pushToWorkStack(computeIfThenElse(
                    childAt(ifNormalized, ifTree, val),
                    childAt(thenNormalized, thenTree, val),
                    childAt(elseNormalized, elseTree, val)));
        }
        int result = makeFunction(minVar, resultChildren);
        table.popFromWorkStack(minVarDomain);
        cache.putIfThenElse(hash, ifNormalized, thenNormalized, elseNormalized, result);
        return complementIf(result, complement);
    }

    @Override
    public int constrain(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeConstrainSimplify(function, domain, true);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public int simplify(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeConstrainSimplify(function, domain, false);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeConstrainSimplify(int function, int domain, boolean constrain) {
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

        int node = positive(function);
        boolean func = node != function;

        int lookup = constrain ? cache.lookupConstrain(node, domain) : cache.lookupSimplify(node, domain);
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
            int[] functionChildren = table.childrenUnchecked(node);
            int[] domainChildren = table.childrenUnchecked(domainNode);
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
                    resultChildren[val] = table.pushToWorkStack(
                            computeConstrainSimplify(functionChildren[val], domainChild, constrain));
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
            int[] functionChildren = table.childrenUnchecked(node);
            int variableDomainSize = functionChildren.length;
            int[] resultChildren = new int[variableDomainSize];
            for (int i = 0; i < variableDomainSize; i++) {
                resultChildren[i] =
                        table.pushToWorkStack(computeConstrainSimplify(functionChildren[i], domain, constrain));
            }
            result = makeFunction(functionVar, resultChildren);
            table.popFromWorkStack(variableDomainSize);
        } else {
            int[] domainChildren = table.childrenUnchecked(domainNode);
            int disjunction = complementIf(domainChildren[0], domc);
            for (int i = 1; i < domainChildren.length; i++) {
                table.pushToWorkStack(disjunction);
                disjunction = computeOr(disjunction, complementIf(domainChildren[i], domc));
                table.popFromWorkStack();
            }
            result = computeConstrainSimplify(node, table.pushToWorkStack(disjunction), true);
            table.popFromWorkStack();
        }
        cache.putSimplify(hash, node, domain, result);
        return complementIf(result, func);
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("MDD@%d(%d)", table.size(), System.identityHashCode(this));
    }

    // Utility

    /**
     * The traversal both iterators run on: it walks the paths to {@code TRUE} of a function, one at a
     * time, in the order the diagram is laid out in.
     *
     * <p>Not a {@link Cursor} itself: it hands nothing out, it only moves. {@link #advance()} steps it on,
     * and the state it exposes describes where it now is. The cursors below differ only in what they make
     * of that state, which is why the descent and the backtracking live here and nowhere else. It is a
     * final class held in fields of its own type, so nothing here is dispatched virtually.
     */
    static final class PathWalk {
        private static final int NON_PATH_NODE = PLACEHOLDER;
        /** What {@link #assignment} holds for a variable the current path does not decide. */
        static final int UNDECIDED = -1;

        private final MddImpl mdd;
        private final int[] path;
        private final boolean[] pathLookingFor;
        private final int[] assignment;
        private final BitSet pathSupport;
        private final int rootVariable;
        private boolean onPath;
        private int leafNodeVariable;

        PathWalk(MddImpl mdd, int function) {
            assert mdd.isValidNonConstantFunction(function);

            int variableCount = mdd.numberOfVariables();
            this.mdd = mdd;
            this.path = new int[variableCount];
            this.pathLookingFor = new boolean[variableCount];
            this.assignment = new int[variableCount];
            this.pathSupport = new BitSet(variableCount);
            this.rootVariable = mdd.decisionVariable(function);

            Arrays.fill(assignment, UNDECIDED);
            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = positive(function);
            pathSupport.set(rootVariable);
            pathLookingFor[rootVariable] = mdd.isPositive(function);
            this.leafNodeVariable = 0;
            /* Positioned on the first path right away, so there is no "have we started yet" state to
             * carry: whoever holds the cursor asks onPath(), and advance() only ever means "the next
             * one". A single diagram cannot dead end, so the first descent always lands somewhere. */
            descend(path[rootVariable], pathLookingFor[rootVariable]);
            this.onPath = true;
        }

        /** The variables the current path decides. */
        BitSet pathSupport() {
            return pathSupport;
        }

        /** The values it decides them to, {@link #UNDECIDED} for every variable it leaves free. */
        int[] assignment() {
            return assignment;
        }

        /** The function being enumerated, as the assertions want it: signed, at the root. */
        int rootFunction() {
            return complementIf(path[rootVariable], !pathLookingFor[rootVariable]);
        }

        /** Whether the cursor is on a path: false once the enumeration is over. */
        boolean onPath() {
            return onPath;
        }

        /** Moves to the next path. Returns {@code false} when there are none left. */
        boolean advance() {
            assert IntStream.range(0, path.length).allMatch(i -> pathSupport.get(i) == (path[i] != NON_PATH_NODE));

            /* Backtrack to the deepest node on the path that has a value left to take which does not fall
             * into false, take it, and retract everything the old path had below it. */
            int currentNode = path[leafNodeVariable];
            boolean currentLookingFor = pathLookingFor[leafNodeVariable];
            int branchVar = leafNodeVariable;

            while (true) {
                assert path[branchVar] != NON_PATH_NODE;

                int[] children = mdd.table.children(currentNode);
                int value = assignment[branchVar] + 1;
                while (value < children.length) {
                    if (!isFalse(children[value], currentLookingFor)) {
                        assert mdd.decisionVariable(currentNode) == branchVar;
                        assignment[branchVar] = value;
                        assert assignment[branchVar] < mdd.variableDomain[branchVar];

                        Arrays.fill(assignment, branchVar + 1, leafNodeVariable + 1, UNDECIDED);
                        Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                        pathSupport.clear(branchVar + 1, leafNodeVariable + 1);
                        leafNodeVariable = branchVar;

                        int child = mdd.follow(currentNode, value);
                        assert !isFalse(child, currentLookingFor);
                        descend(child, currentLookingFor);
                        return true;
                    }
                    value += 1;
                }

                branchVar = pathSupport.previousSetBit(branchVar - 1);
                if (branchVar == -1) {
                    onPath = false;
                    return false;
                }
                currentNode = path[branchVar];
                currentLookingFor = pathLookingFor[branchVar];
            }
        }

        /** Walks down to a leaf, taking the lowest value at each node that does not fall into false. */
        private void descend(int startNode, boolean startLookingFor) {
            int currentNode = positive(startNode);
            boolean currentLookingFor = startNode == currentNode ? startLookingFor : !startLookingFor;

            while (!isTrue(currentNode, currentLookingFor)) {
                assert mdd.isPositive(currentNode) && !mdd.isConstant(currentNode);

                leafNodeVariable = mdd.table.variable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathSupport.set(leafNodeVariable);
                pathLookingFor[leafNodeVariable] = currentLookingFor;

                int[] children = mdd.table.children(currentNode);
                int value = 0;
                while (isFalse(children[value], currentLookingFor)) {
                    value += 1;
                }
                assignment[leafNodeVariable] = value;

                int child = mdd.follow(currentNode, value);
                currentNode = positive(child);
                if (currentNode != child) {
                    currentLookingFor = !currentLookingFor;
                }
            }
        }
    }

    /**
     * Walks the solutions of a function: every path, and for each of them every way of filling in the
     * support variables that path leaves free.
     *
     * <p>Hands out its own working array, updated in place - see {@link Cursor}. A path change rewrites it;
     * a step of the free-variable counter touches only the entries that changed.
     */
    static final class SolutionCursor implements Cursor<int[]> {
        private final MddImpl mdd;
        private final PathWalk path;
        private final BitSet support;
        /* The support variables the current path leaves free, recomputed whenever the path moves. Worked
         * out once per path rather than rediscovered per solution: there are far more solutions. */
        private final BitSet freeVariables;
        /** Path values where the path decides, the counter's own where it does not. */
        private final int[] solution;

        private boolean valid;

        SolutionCursor(MddImpl mdd, int function, BitSet support) {
            int variableCount = mdd.numberOfVariables();
            // Assignments don't make much sense otherwise
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(mdd.support(function), support);

            this.mdd = mdd;
            this.path = new PathWalk(mdd, function);
            this.support = support;
            this.freeVariables = new BitSet(variableCount);
            this.solution = new int[variableCount];
            this.valid = path.onPath();
            if (valid) {
                refreshFreeVariables();
                syncPath();
            }
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public int[] current() {
            assert valid : "current() is only defined while the cursor is valid";
            return solution;
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }

            /* Addition over the support variables the current path does not decide: those are free, so
             * every combination of their values extends this path to a solution. Carrying past the last
             * one leaves them all at zero and means the path itself has to move on. */
            for (int var = freeVariables.nextSetBit(0); var >= 0; var = freeVariables.nextSetBit(var + 1)) {
                assert solution[var] < mdd.variableDomain[var];
                if (solution[var] == mdd.variableDomain[var] - 1) {
                    solution[var] = 0;
                } else {
                    solution[var] += 1;
                    assert mdd.evaluate(path.rootFunction(), solution);
                    return true;
                }
            }
            if (!path.advance()) {
                valid = false;
                return false;
            }
            refreshFreeVariables();
            syncPath();
            return true;
        }

        private void refreshFreeVariables() {
            BitSets.difference(freeVariables, support, path.pathSupport());
        }

        /** Takes the new path's values over, and puts every variable it leaves free back to zero. */
        private void syncPath() {
            assert BitSets.isSubset(path.pathSupport(), support);
            int[] assignment = path.assignment();
            for (int var = 0; var < solution.length; var++) {
                int decided = assignment[var];
                solution[var] = decided == PathWalk.UNDECIDED ? 0 : decided;
            }
            assert mdd.evaluate(path.rootFunction(), solution);
        }
    }

    /**
     * Walks the paths to {@code true} of a function - the same walk as {@link SolutionCursor},
     * stopping at each path instead of filling in the variables it leaves free, which stay at
     * {@link PathWalk#UNDECIDED}.
     */
    static final class PathCursor implements Cursor<int[]> {
        private final MddImpl mdd;
        private final PathWalk path;
        private boolean valid;

        PathCursor(MddImpl mdd, int function) {
            this.mdd = mdd;
            this.path = new PathWalk(mdd, function);
            this.valid = path.onPath();
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public int[] current() {
            assert valid : "current() is only defined while the cursor is valid";
            assert mdd.evaluate(path.rootFunction(), path.assignment());
            return path.assignment();
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }
            valid = path.advance();
            return valid;
        }
    }

    public static class MddTable extends NodeTable.Multi {
        private final MddImpl mdd;

        MddTable(MddImpl mdd, int initialSize) {
            super(initialSize);
            this.mdd = mdd;
        }

        @Override
        protected int level(int variable) {
            // MDDs do not reorder, so a variable is its own level.
            return variable;
        }

        @Override
        public boolean isValidConstant(int pointer) {
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
        protected boolean recurseIsAllMarkedBelow(int node, boolean includeLeaves) {
            int[] children = tree[node];
            boolean all = true;
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE && !doIsAllMarkedBelow(childNode, includeLeaves)) {
                    all = false;
                    break;
                }
            }
            return all;
        }

        @Override
        protected void markLeafNodeIfManaged(int node, boolean mark) {
            // Nothing to do
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark, boolean includeLeaves) {
            int[] children = tree[node];
            int sum = 0;
            for (int child : children) {
                int childNode = positive(child);
                if (childNode != TRUE) {
                    sum += doSetMarkBelow(childNode, mark, includeLeaves);
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
        int treeNodeFor(int pointer) {
            return mdd.nodeFor(pointer);
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
        protected BddConfiguration configuration() {
            return mdd.configuration;
        }

        @Override
        protected void notifyBeforeGc() {
            mdd.notifyBeforeGc();
        }

        @Override
        protected void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
            mdd.notifyAfterGc(reclaimedNodes);
        }

        @Override
        protected void notifyAfterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
            mdd.notifyAfterTableGrow(invalidatedNodes);
        }

        @Override
        protected BitSet sweepManagedLeaves() {
            return BitSets.of();
        }

        @Override
        protected boolean checkOwner() {
            return mdd.check();
        }

        @Override
        protected boolean anyManagedLeafMarked() {
            return false;
        }

        @Override
        protected void unmarkAllManagedLeaves() {
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
        String format(int pointer) {
            return mdd.format(pointer);
        }
    }
}
