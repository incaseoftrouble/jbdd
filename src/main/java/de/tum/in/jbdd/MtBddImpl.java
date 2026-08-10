/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2025 Tobias Meggendorfer.
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

import static de.tum.in.jbdd.BooleanBase.TWO;
import static de.tum.in.jbdd.Preconditions.checkState;
import static java.math.BigInteger.ZERO;

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
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/*
 * Important differences to BDDs:
 *  - In a generic MTBDD we have no commutativity and neutral elements, hence much more "base case" branching is required
 */
@SuppressWarnings("PMD")
class MtBddImpl implements MtBdd, NodeBasedDecisionDiagram {
    private static final int INVERT_ARRAY_DOMAIN_THRESHOLD = 32;
    private static final Logger logger = Logger.getLogger(MtBddImpl.class.getName());

    private final BddImpl bdd;
    private final MtBddTable table;
    private byte[] valueReferenceCounts;
    private static final byte MAXIMUM_REFERENCE_COUNT = Byte.MAX_VALUE;
    // Convert to sparse bit set?
    private final BitSet allocatedValues = new BitSet();

    MtBddImpl(BddImpl bdd) {
        this.bdd = bdd;
        this.table = new MtBddTable(this, 1024);
        this.valueReferenceCounts = new byte[1024];
    }

    @Override
    public Bdd bdd() {
        return bdd;
    }

    @Override
    public int numberOfVariables() {
        return bdd.numberOfVariables();
    }

    private static int valueToConstantFunction(int value) {
        assert value >= 0;
        return -value - 1;
    }

    private static int constantFunctionToValue(int function) {
        assert function < 0;
        return -function - 1;
    }

    // Reference counting

    @SuppressWarnings("NarrowingCompoundAssignment")
    @Override
    public int reference(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            int value = constantFunctionToValue(function);
            ensureValueCapacity(value);
            if (valueReferenceCounts[value] < MAXIMUM_REFERENCE_COUNT) {
                //noinspection ImplicitNumericConversion
                valueReferenceCounts[value] += 1;
            }
        } else {
            table.referenceNode(function);
        }
        return function;
    }

    @SuppressWarnings("NarrowingCompoundAssignment")
    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            int value = constantFunctionToValue(function);
            assert valueReferenceCounts[value] > 0;
            if (valueReferenceCounts[value] < MAXIMUM_REFERENCE_COUNT) {
                //noinspection ImplicitNumericConversion
                valueReferenceCounts[value] -= 1;
            }
        } else {
            table.dereferenceNode(function);
        }
        return function;
    }

    private void ensureValueCapacity(int value) {
        if (value < valueReferenceCounts.length) {
            return;
        }
        int newSize = valueReferenceCounts.length * 2;
        while (newSize <= value) {
            newSize *= 2;
        }
        valueReferenceCounts = Arrays.copyOf(valueReferenceCounts, newSize);
    }

    @Override
    public int nodeReferenceCount(int node) {
        if (isConstant(node)) {
            int value = constantFunctionToValue(node);
            if (value >= valueReferenceCounts.length) {
                return 0;
            }
            int referenceCount = valueReferenceCounts[value];
            return referenceCount == MAXIMUM_REFERENCE_COUNT ? -1 : referenceCount;
        }
        return table.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        if (isConstant(node)) {
            int value = constantFunctionToValue(node);
            return value < valueReferenceCounts.length && valueReferenceCounts[value] == MAXIMUM_REFERENCE_COUNT;
        }
        return table.isSaturatedNode(node);
    }

    @Override
    public void forEachSupportVariable(int function, IntConsumer action) {
        table.forEachVariable(function, action);
    }

    @Override
    public void forEachSupportVariableFiltered(int function, BitSet filter, IntConsumer action) {
        table.forEachVariable(function, filter, action);
    }

    @Override
    public int referencedNodeCount() {
        int referencedValues = 0;
        for (int i = allocatedValues.nextSetBit(0);
                i < valueReferenceCounts.length && i >= 0;
                i = allocatedValues.nextSetBit(i + 1)) {
            if (valueReferenceCounts[i] > 0) {
                referencedValues++;
            }
        }
        return table.referencedNodeCount() + referencedValues;
    }

    @Override
    public int nodeCount() {
        return table.nodeCount() + allocatedValues.cardinality();
    }

    @Override
    public int nodeFor(int function) {
        assert function != placeholder();
        return function;
    }

    @Override
    public int decisionVariable(int function) {
        assert isValidNonConstantFunction(function);
        return table.variable(function);
    }

    @Override
    public int size(int function) {
        assert isValidFunction(function);
        return table.nodeCountBelow(function);
    }

    public boolean isValidFunction(int function) {
        return (function < 0 && isValidConstant(function)) || table.isValidDecisionNode(function);
    }

    boolean isValidNonConstantFunction(int function) {
        return table.isValidDecisionNode(function);
    }

    NodeTable table() {
        return table;
    }

    private boolean isValidConstant(int function) {
        return function < 0 && allocatedValues.get(constantFunctionToValue(function));
    }

    @Override
    public boolean isConstant(int function) {
        assert isValidFunction(function);
        return function < 0;
    }

    @Override
    public int placeholder() {
        return NodeTable.PLACEHOLDER;
    }

    @Override
    public int highOf(int function) {
        assert isValidNonConstantFunction(function);
        return table.high(function);
    }

    @Override
    public int lowOf(int function) {
        assert isValidNonConstantFunction(function);
        return table.low(function);
    }

    @Override
    public int evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidDecisionNode(currentNode);
            currentNode = assignment[decisionVariable(currentNode)] ? table.high(currentNode) : table.low(currentNode);
        }
        return constantFunctionToValue(currentNode);
    }

    @Override
    public int evaluate(int function, BitSet assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidDecisionNode(currentNode);
            currentNode =
                    assignment.get(decisionVariable(currentNode)) ? table.high(currentNode) : table.low(currentNode);
        }
        return constantFunctionToValue(currentNode);
    }

    @Override
    public int of(int value) {
        assert value >= 0;
        allocatedValues.set(value);
        return valueToConstantFunction(value);
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        assert isValidFunction(trueChild) && isValidFunction(falseChild);
        table.pushToWorkStack(trueChild, falseChild);
        int result = makeFunction(variable, falseChild, trueChild);
        table.popFromWorkStack(2);
        return result;
    }

    int makeFunction(int variable, int lowFunction, int highFunction) {
        return lowFunction == highFunction ? lowFunction : table.makeNode(variable, lowFunction, highFunction);
    }

    @Override
    public boolean allValuesMatch(int function, IntPredicate predicate) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return predicate.test(constantFunctionToValue(function));
        }

        assert table.isNoneMarkedBelowNode(function);
        boolean result = allValuesMatchRecursive(function, predicate);
        table.doSetMarkBelow(function, false, false);
        assert table.isNoneMarkedBelowNode(function);
        return result;
    }

    private boolean allValuesMatchRecursive(int node, IntPredicate predicate) {
        if (isConstant(node)) {
            return predicate.test(constantFunctionToValue(node));
        }
        return !table.markNodeIfUnmarked(node)
                || (allValuesMatchRecursive(table.low(node), predicate)
                        && allValuesMatchRecursive(table.high(node), predicate));
    }

    @Override
    public boolean anyValueMatches(int function, IntPredicate predicate) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return predicate.test(constantFunctionToValue(function));
        }

        assert table.isNoneMarkedBelowNode(function);
        boolean result = anyValueMatchesRecursive(function, predicate);
        table.doSetMarkBelow(function, false, false);
        assert table.isNoneMarkedBelowNode(function);
        return result;
    }

    private boolean anyValueMatchesRecursive(int node, IntPredicate predicate) {
        if (isConstant(node)) {
            return predicate.test(constantFunctionToValue(node));
        }
        return table.markNodeIfUnmarked(node)
                && (anyValueMatchesRecursive(table.low(node), predicate)
                        || anyValueMatchesRecursive(table.high(node), predicate));
    }

    @Override
    public void forEachValue(int function, IntConsumer action) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            action.accept(constantFunctionToValue(function));
            return;
        }

        assert table.isNoneMarkedBelowNode(function);
        table.markAllBelowNode(function, true);
        BitSets.forEach(table.markedValues, action);
        table.unMarkAllBelowNode(function, true);
        assert table.isNoneMarkedBelowNode(function);
    }

    @Override
    public void forEachPath(int function, PathConsumer action) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            BitSet empty = BitSets.of();
            action.accept(new BinaryPath(empty, empty), constantFunctionToValue(function));
            return;
        }

        PathIterator iterator = new PathIterator(this, function);
        while (iterator.hasNext()) {
            BinaryPath path = iterator.next();
            action.accept(path, iterator.currentValue());
        }
    }

    @Override
    public Optional<BitSet> anyAssignment(int function, IntPredicate values) {
        assert isValidFunction(function);

        BitSet assigment = new BitSet(numberOfVariables());
        boolean found = anyAssigmentRecursive(function, values, assigment);
        return found ? Optional.of(assigment) : Optional.empty();
    }

    private boolean anyAssigmentRecursive(int function, IntPredicate values, BitSet assignment) {
        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function));
        }
        if (anyAssigmentRecursive(table.low(function), values, assignment)) {
            return true;
        }
        if (anyAssigmentRecursive(table.high(function), values, assignment)) {
            assignment.set(decisionVariable(function));
            return true;
        }
        return false;
    }

    @Override
    public BigInteger countAssignments(int function, IntPredicate values) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function)) ? TWO.pow(numberOfVariables()) : ZERO;
        }

        int variable = decisionVariable(function);
        BigInteger satisfyingBelow = countSatisfyingAssignmentsRecursive(function, values);
        return TWO.pow(variable).multiply(satisfyingBelow);
    }

    @Override
    public BigInteger countAssignments(int function, IntPredicate values, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        return countAssignments(function, values).divide(TWO.pow(numberOfVariables() - support.cardinality()));
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node, IntPredicate values) {
        int nodeVar = table.variable(node);

        /*
        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return lookingFor
                ? cacheLookup
                : TWO.pow(numberOfVariables() - nodeVar).subtract(cacheLookup);
        }
        int hash = cache.lookupHash();
        */

        BigInteger lowCount = doCountSatisfyingAssignments(table.low(node), nodeVar, values);
        BigInteger highCount = doCountSatisfyingAssignments(table.high(node), nodeVar, values);
        BigInteger result = lowCount.add(highCount);
        /* cache.putSatisfaction(
        hash,
        node,
        lookingFor ? result : TWO.pow(numberOfVariables() - nodeVar).subtract(result)); */
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int function, int previousVar, IntPredicate values) {
        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function))
                    ? TWO.pow(numberOfVariables() - previousVar - 1)
                    : ZERO;
        }
        BigInteger multiplier = TWO.pow(decisionVariable(function) - previousVar - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(function, values));
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, IntPredicate values) {
        assert isValidFunction(function);

        BitSet support = new BitSet(numberOfVariables());
        support.set(0, numberOfVariables());
        return assignmentIterator(function, values, support);
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, IntPredicate values, BitSet support) {
        assert isValidFunction(function);
        assert BitSets.isSubset(support(function), support);

        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function))
                    ? BitSets.powerSetIterator(support)
                    : Collections.emptyIterator();
        }
        return new AssignmentIterator(this, function, values, support);
    }

    @Override
    public int apply(int function1, int function2, IntBinaryOperator map) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeApply(function1, function2, map);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeApply(int function1, int function2, IntBinaryOperator map) {
        boolean constant1 = isConstant(function1);
        boolean constant2 = isConstant(function2);
        if (constant1 && constant2) {
            return of(map.applyAsInt(constantFunctionToValue(function1), constantFunctionToValue(function2)));
        }

        int variable;
        int low1;
        int high1;
        int low2;
        int high2;
        if (constant1) {
            variable = decisionVariable(function2);
            low1 = function1;
            high1 = function1;
            low2 = table.low(function2);
            high2 = table.high(function2);
        } else if (constant2) {
            variable = decisionVariable(function1);
            low1 = table.low(function1);
            high1 = table.high(function1);
            low2 = function2;
            high2 = function2;
        } else {
            int variable1 = decisionVariable(function1);
            int variable2 = decisionVariable(function2);
            variable = Math.min(variable1, variable2);
            low1 = variable1 == variable ? table.low(function1) : function1;
            high1 = variable1 == variable ? table.high(function1) : function1;
            low2 = variable2 == variable ? table.low(function2) : function2;
            high2 = variable2 == variable ? table.high(function2) : function2;
        }
        int low = table.pushToWorkStack(computeApply(low1, low2, map));
        int high = table.pushToWorkStack(computeApply(high1, high2, map));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public int map(int function, IntUnaryOperator map) {
        assert isValidFunction(function);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(function);
        int result = computeMap(function, map);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeMap(int function, IntUnaryOperator map) {
        if (isConstant(function)) {
            return of(map.applyAsInt(constantFunctionToValue(function)));
        }

        int variable = decisionVariable(function);
        int low = table.pushToWorkStack(computeMap(table.low(function), map));
        int high = table.pushToWorkStack(computeMap(table.high(function), map));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public int apply(int[] functions, ToIntFunction<int[]> map) {
        if (functions.length == 0) {
            return placeholder();
        }
        if (functions.length == 1) {
            int[] array = new int[1];
            return this.map(functions[0], i -> {
                array[0] = i;
                return map.applyAsInt(array);
            });
        }
        if (functions.length == 2) {
            int[] array = new int[2];
            return this.apply(functions[0], functions[1], (a, b) -> {
                array[0] = a;
                array[1] = b;
                return map.applyAsInt(array);
            });
        }
        for (int function : functions) {
            assert isValidFunction(function);
        }

        assert table.isWorkStackEmpty();
        for (int function : functions) {
            table.pushToWorkStack(function);
        }
        int[] values = new int[functions.length];
        int result = computeNaryApply(
                Arrays.copyOf(functions, functions.length),
                values,
                map,
                0,
                new DepthPool<>(() -> new int[functions.length]));
        table.popFromWorkStack(functions.length);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int computeNaryApply(
            int[] functions, int[] values, ToIntFunction<int[]> map, int depth, DepthPool<int[]> highPool) {
        int variable = minVariable(functions);
        if (variable == Integer.MAX_VALUE) {
            for (int i = 0; i < functions.length; i++) {
                values[i] = constantFunctionToValue(functions[i]);
            }
            return of(map.applyAsInt(values));
        }

        int[] highFunctions = highPool.get(depth);
        splitByVariable(functions, highFunctions, variable);

        int low = table.pushToWorkStack(computeNaryApply(functions, values, map, depth + 1, highPool));
        int high = table.pushToWorkStack(computeNaryApply(highFunctions, values, map, depth + 1, highPool));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    // Shared by computeNaryApply and cartesianProductRecursive: both cofactor an array of operands on
    // their smallest current decision variable, treating constant operands as not participating.
    private int minVariable(int[] functions) {
        int variable = Integer.MAX_VALUE;
        for (int function : functions) {
            if (!isConstant(function)) {
                int functionVariable = decisionVariable(function);
                if (functionVariable < variable) {
                    variable = functionVariable;
                }
            }
        }
        return variable;
    }

    private void splitByVariable(int[] functions, int[] highFunctions, int variable) {
        for (int i = 0; i < functions.length; i++) {
            int function = functions[i];
            if (!isConstant(function) && decisionVariable(function) == variable) {
                functions[i] = table.low(function);
                highFunctions[i] = table.high(function);
            } else {
                highFunctions[i] = function;
            }
        }
    }

    @Override
    public int agreement(int mtbddFunction1, int mtbddFunction2) {
        assert isValidFunction(mtbddFunction1) && isValidFunction(mtbddFunction2);
        assert bdd.table().isWorkStackEmpty();
        int result = agreementRecursive(mtbddFunction1, mtbddFunction2);
        assert bdd.table().isWorkStackEmpty();
        return result;
    }

    private int agreementRecursive(int mtbddNode1, int mtbddNode2) {
        boolean constant1 = isConstant(mtbddNode1);
        boolean constant2 = isConstant(mtbddNode2);
        if (constant1 && constant2) {
            boolean agree = constantFunctionToValue(mtbddNode1) == constantFunctionToValue(mtbddNode2);
            return agree ? bdd.trueFunction() : bdd.falseFunction();
        }

        int variable;
        int mtbddLow1;
        int mtbddHigh1;
        int mtbddLow2;
        int mtbddHigh2;
        if (constant1) {
            variable = decisionVariable(mtbddNode2);
            mtbddLow1 = mtbddNode1;
            mtbddHigh1 = mtbddNode1;
            mtbddLow2 = table.low(mtbddNode2);
            mtbddHigh2 = table.high(mtbddNode2);
        } else if (constant2) {
            variable = decisionVariable(mtbddNode1);
            mtbddLow1 = table.low(mtbddNode1);
            mtbddHigh1 = table.high(mtbddNode1);
            mtbddLow2 = mtbddNode2;
            mtbddHigh2 = mtbddNode2;
        } else {
            int variable1 = decisionVariable(mtbddNode1);
            int variable2 = decisionVariable(mtbddNode2);
            variable = Math.min(variable1, variable2);
            mtbddLow1 = variable1 == variable ? table.low(mtbddNode1) : mtbddNode1;
            mtbddHigh1 = variable1 == variable ? table.high(mtbddNode1) : mtbddNode1;
            mtbddLow2 = variable2 == variable ? table.low(mtbddNode2) : mtbddNode2;
            mtbddHigh2 = variable2 == variable ? table.high(mtbddNode2) : mtbddNode2;
        }

        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(agreementRecursive(mtbddLow1, mtbddLow2));
        int bddHigh = bddTable.pushToWorkStack(agreementRecursive(mtbddHigh1, mtbddHigh2));
        int bddResult = bdd.makeFunction(variable, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        return bddResult;
    }

    @Override
    public int mapBoolean(int mtbddFunction, IntPredicate values) {
        assert isValidFunction(mtbddFunction);
        assert bdd.table().isWorkStackEmpty();
        int result = mapBooleanRecursive(mtbddFunction, values);
        assert bdd.table().isWorkStackEmpty();
        return result;
    }

    private int mapBooleanRecursive(int mtbddNode, IntPredicate values) {
        if (isConstant(mtbddNode)) {
            return values.test(constantFunctionToValue(mtbddNode)) ? bdd.trueFunction() : bdd.falseFunction();
        }

        int variable = decisionVariable(mtbddNode);
        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(mapBooleanRecursive(table.low(mtbddNode), values));
        int bddHigh = bddTable.pushToWorkStack(mapBooleanRecursive(table.high(mtbddNode), values));
        int bddResult = bdd.makeFunction(variable, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        return bddResult;
    }

    @Override
    public int update(int mtbddFunction, int bddAssignments, int value) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddAssignments);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result = updateRecursive(mtbddFunction, bddAssignments, value);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int updateRecursive(int mtbddNode, int bddNode, int value) {
        if (bddNode == bdd.falseFunction()) {
            return mtbddNode;
        }
        if (bddNode == bdd.trueFunction()) {
            return of(value);
        }

        int bddVariable = bdd.decisionVariable(bddNode);
        int variable = isConstant(mtbddNode) ? bddVariable : Math.min(bddVariable, decisionVariable(mtbddNode));

        int mtbddLow;
        int mtbddHigh;
        if (!isConstant(mtbddNode) && decisionVariable(mtbddNode) == variable) {
            mtbddLow = table.low(mtbddNode);
            mtbddHigh = table.high(mtbddNode);
        } else {
            mtbddLow = mtbddNode;
            mtbddHigh = mtbddNode;
        }

        int bddLow;
        int bddHigh;
        if (bddVariable == variable) {
            bddLow = bdd.lowOf(bddNode);
            bddHigh = bdd.highOf(bddNode);
        } else {
            bddLow = bddNode;
            bddHigh = bddNode;
        }

        int low = table.pushToWorkStack(updateRecursive(mtbddLow, bddLow, value));
        int high = table.pushToWorkStack(updateRecursive(mtbddHigh, bddHigh, value));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public int compose(int mtbddFunction, int[] bddVariableMapping) {
        assert isValidFunction(mtbddFunction);
        assert bddVariableMapping.length <= numberOfVariables();

        if (isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        assert table.isWorkStackEmpty();

        int highestReplacedVariable = -1;
        for (int variable = 0; variable < bddVariableMapping.length; variable++) {
            if (bddVariableMapping[variable] == placeholder()) {
                bddVariableMapping[variable] = bdd.variableFunction(variable);
            } else {
                assert bdd.isValidFunction(bddVariableMapping[variable]);
                if (bddVariableMapping[variable] != bdd.variableFunction(variable)) {
                    highestReplacedVariable = variable;
                }
            }
        }
        if (highestReplacedVariable == -1) {
            return mtbddFunction;
        }

        table.pushToWorkStack(mtbddFunction);
        int result = composeRecursive(mtbddFunction, bddVariableMapping, highestReplacedVariable);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int composeRecursive(int mtbddNode, int[] bddVariableMapping, int highestReplacedVariable) {
        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        int variable = decisionVariable(mtbddNode);
        if (variable > highestReplacedVariable) {
            return mtbddNode;
        }

        int bddReplacement = bddVariableMapping[variable];
        if (bddReplacement == bdd.trueFunction()) {
            return composeRecursive(table.high(mtbddNode), bddVariableMapping, highestReplacedVariable);
        }
        if (bddReplacement == bdd.falseFunction()) {
            return composeRecursive(table.low(mtbddNode), bddVariableMapping, highestReplacedVariable);
        }

        int low = table.pushToWorkStack(
                composeRecursive(table.low(mtbddNode), bddVariableMapping, highestReplacedVariable));
        int high = table.pushToWorkStack(
                composeRecursive(table.high(mtbddNode), bddVariableMapping, highestReplacedVariable));
        int result = ifThenElseRecursive(bddReplacement, high, low);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public int restrict(int mtbddFunction, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isValidFunction(mtbddFunction);

        if (restrictedVariables.isEmpty() || isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        int highestRestrictedVariable = restrictedVariables.length() - 1;
        assert table.isWorkStackEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result = restrictRecursive(
                mtbddFunction, restrictedVariables, restrictedVariableValues, highestRestrictedVariable);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int restrictRecursive(
            int mtbddNode, BitSet restrictedVariables, BitSet restrictedVariableValues, int highestRestrictedVariable) {
        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        int variable = decisionVariable(mtbddNode);
        if (variable > highestRestrictedVariable) {
            return mtbddNode;
        }

        if (restrictedVariables.get(variable)) {
            int child = restrictedVariableValues.get(variable) ? table.high(mtbddNode) : table.low(mtbddNode);
            return restrictRecursive(child, restrictedVariables, restrictedVariableValues, highestRestrictedVariable);
        }

        int low = table.pushToWorkStack(restrictRecursive(
                table.low(mtbddNode), restrictedVariables, restrictedVariableValues, highestRestrictedVariable));
        int high = table.pushToWorkStack(restrictRecursive(
                table.high(mtbddNode), restrictedVariables, restrictedVariableValues, highestRestrictedVariable));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public int ifThenElse(int bddIfFunction, int mtbddThenFunction, int mtbddElseFunction) {
        assert isValidFunction(mtbddThenFunction) && isValidFunction(mtbddElseFunction);
        assert bdd.isValidFunction(bddIfFunction);

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(mtbddThenFunction, mtbddElseFunction);
        int result = ifThenElseRecursive(bddIfFunction, mtbddThenFunction, mtbddElseFunction);
        table.popFromWorkStack(2);
        assert table.isWorkStackEmpty();
        return result;
    }

    private int ifThenElseRecursive(int bddNode, int mtbddThenNode, int mtbddElseNode) {
        if (bddNode == bdd.falseFunction()) {
            return mtbddElseNode;
        }
        if (bddNode == bdd.trueFunction()) {
            return mtbddThenNode;
        }
        if (mtbddThenNode == mtbddElseNode) {
            return mtbddThenNode;
        }

        // To simply branching, use MAX_VALUE as a sentinel
        int bddVariable = bdd.decisionVariable(bddNode);
        int thenVariable = isConstant(mtbddThenNode) ? Integer.MAX_VALUE : decisionVariable(mtbddThenNode);
        int elseVariable = isConstant(mtbddElseNode) ? Integer.MAX_VALUE : decisionVariable(mtbddElseNode);
        int variable = Math.min(bddVariable, Math.min(thenVariable, elseVariable));

        int bddLow = bddVariable == variable ? bdd.lowOf(bddNode) : bddNode;
        int bddHigh = bddVariable == variable ? bdd.highOf(bddNode) : bddNode;
        int mtbddThenLow = thenVariable == variable ? table.low(mtbddThenNode) : mtbddThenNode;
        int mtbddThenHigh = thenVariable == variable ? table.high(mtbddThenNode) : mtbddThenNode;
        int mtbddElseLow = elseVariable == variable ? table.low(mtbddElseNode) : mtbddElseNode;
        int mtbddElseHigh = elseVariable == variable ? table.high(mtbddElseNode) : mtbddElseNode;

        int low = table.pushToWorkStack(ifThenElseRecursive(bddLow, mtbddThenLow, mtbddElseLow));
        int high = table.pushToWorkStack(ifThenElseRecursive(bddHigh, mtbddThenHigh, mtbddElseHigh));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    @Override
    public MtBdd.Inverse invert(int mtbddFunction) {
        assert isValidFunction(mtbddFunction);
        assert bdd.table().isWorkStackEmpty();

        int domainSize = allocatedValues.length();
        MtBdd.Inverse result;
        int falseFunction = bdd.falseFunction();
        if (domainSize <= INVERT_ARRAY_DOMAIN_THRESHOLD) {
            BitSet values = new BitSet(domainSize);
            int[] bddFunctions = invertRecursiveArray(
                    mtbddFunction, domainSize, values, 0, new DepthPool<>(() -> new int[domainSize]));
            assert values.stream().allMatch(i -> bddFunctions[i] != NodeTable.PLACEHOLDER);
            bdd.table().popFromWorkStack(values.cardinality());
            result = new FunctionInverse(
                    mtbddFunction,
                    value -> value >= 0 && value < bddFunctions.length && bddFunctions[value] != NodeTable.PLACEHOLDER
                            ? bddFunctions[value]
                            : falseFunction,
                    values);
        } else {
            Map<Integer, Integer> bddFunctions = invertRecursive(mtbddFunction, 0, new DepthPool<>(HashMap::new));
            bdd.table().popFromWorkStack(bddFunctions.size());
            BitSet values = new BitSet();
            bddFunctions.keySet().forEach(values::set);
            result = new FunctionInverse(mtbddFunction, v -> bddFunctions.getOrDefault(v, falseFunction), values);
        }

        assert bdd.table().isWorkStackEmpty();
        return result;
    }

    // Both invertRecursive and invertRecursiveArray follow the same "low survives, high is disposable"
    // shape: the low-side result is mutated in place and returned (its identity travels all the way up
    // to whichever ancestor eventually absorbs it as a combined result, so it must be a genuinely owned,
    // not pooled, instance), while the high-side result is read exactly once, during this call's own
    // combine step below, then discarded - safe to source from a per-depth DepthPool instead of
    // allocating fresh, exactly like computeNaryApply/cartesianProductRecursive's highPool, but only for
    // the specific (extremely common, since there's no memoization - see MTBDD_NOTES.md) case where the
    // high child is directly a constant, since only then is the disposable value fully known without any
    // further recursion of its own that could need a fresh, escaping accumulator.

    private Map<Integer, Integer> invertRecursive(
            int mtbddNode, int depth, DepthPool<Map<Integer, Integer>> highLeafPool) {
        if (isConstant(mtbddNode)) {
            Map<Integer, Integer> result = new HashMap<>();
            result.put(constantFunctionToValue(mtbddNode), bdd.table().pushToWorkStack(bdd.trueFunction()));
            return result;
        }

        int variable = decisionVariable(mtbddNode);
        Map<Integer, Integer> mtbddLowMap = invertRecursive(table.low(mtbddNode), depth + 1, highLeafPool);

        int highChild = table.high(mtbddNode);
        Map<Integer, Integer> mtbddHighMap;
        if (isConstant(highChild)) {
            mtbddHighMap = highLeafPool.get(depth);
            mtbddHighMap.clear();
            mtbddHighMap.put(constantFunctionToValue(highChild), bdd.table().pushToWorkStack(bdd.trueFunction()));
        } else {
            mtbddHighMap = invertRecursive(highChild, depth + 1, highLeafPool);
        }

        int lowCount = mtbddLowMap.size();
        int highCount = mtbddHighMap.size();

        for (Map.Entry<Integer, Integer> entry : mtbddLowMap.entrySet()) {
            int value = entry.getKey();
            int bddLow = entry.getValue();
            int bddHigh = mtbddHighMap.getOrDefault(value, bdd.falseFunction());
            int bddResult = bdd.table().pushToWorkStack(bdd.makeFunction(variable, bddLow, bddHigh));
            entry.setValue(bddResult);
        }
        mtbddHighMap.forEach((value, bddHigh) -> mtbddLowMap.computeIfAbsent(
                value, k -> bdd.table().pushToWorkStack(bdd.makeFunction(variable, bdd.falseFunction(), bddHigh))));

        bdd.table().popFromWorkStack(lowCount + highCount + mtbddLowMap.size());
        mtbddLowMap.values().forEach(bdd.table()::pushToWorkStack);
        return mtbddLowMap;
    }

    private int[] invertRecursiveArray(
            int mtbddNode, int domainSize, BitSet values, int depth, DepthPool<int[]> highLeafPool) {
        NodeTable bddTable = bdd.table();
        if (isConstant(mtbddNode)) {
            // Deliberately relying on `new int[]` being zero-initialized (guaranteed by the JLS) rather
            // than an explicit Arrays.fill - only sound because placeholder() is always exactly 0.
            assert placeholder() == 0;
            int[] result = new int[domainSize];
            int value = constantFunctionToValue(mtbddNode);
            values.set(value);
            result[value] = bddTable.pushToWorkStack(bdd.trueFunction());
            return result;
        }

        int variable = decisionVariable(mtbddNode);
        int[] lowArray = invertRecursiveArray(table.low(mtbddNode), domainSize, values, depth + 1, highLeafPool);

        int highChild = table.high(mtbddNode);
        int[] highArray;
        if (isConstant(highChild)) {
            highArray = highLeafPool.get(depth);
            Arrays.fill(highArray, NodeTable.PLACEHOLDER);
            int value = constantFunctionToValue(highChild);
            values.set(value);
            highArray[value] = bddTable.pushToWorkStack(bdd.trueFunction());
        } else {
            highArray = invertRecursiveArray(highChild, domainSize, values, depth + 1, highLeafPool);
        }

        int count = 0;
        for (int value = values.nextSetBit(0); value >= 0; value = values.nextSetBit(value + 1)) {
            boolean lowPresent = lowArray[value] != NodeTable.PLACEHOLDER;
            boolean highPresent = highArray[value] != NodeTable.PLACEHOLDER;
            if (!lowPresent && !highPresent) {
                continue;
            }
            int bddLow;
            if (lowPresent) {
                bddLow = lowArray[value];
                count += 1;
            } else {
                bddLow = bdd.falseFunction();
            }
            int bddHigh;
            if (highPresent) {
                bddHigh = highArray[value];
                count += 1;
            } else {
                bddHigh = bdd.falseFunction();
            }
            int bddResult = bddTable.pushToWorkStack(bdd.makeFunction(variable, bddLow, bddHigh));
            lowArray[value] = bddResult;
            count++;
        }

        bddTable.popFromWorkStack(count);
        for (int value = values.nextSetBit(0); value >= 0; value = values.nextSetBit(value + 1)) {
            if (lowArray[value] != NodeTable.PLACEHOLDER) {
                bddTable.pushToWorkStack(lowArray[value]);
            }
        }
        return lowArray;
    }

    @Override
    public FunctionToFunctionMap split(int mtbddFunction, BitSet splitVariables) {
        assert isValidFunction(mtbddFunction);
        assert table.isWorkStackEmpty();

        int highestSplitVariable = splitVariables.length() - 1;
        SplitBijection bijection = new SplitBijection(table);
        table.pushToWorkStack(mtbddFunction);
        int mtbddG = splitRecursive(mtbddFunction, splitVariables, highestSplitVariable, bijection);
        table.popFromWorkStack(bijection.size() + 1);
        assert table.isWorkStackEmpty();

        BitSet indices = new BitSet();
        indices.set(0, bijection.size());
        return new FunctionToFunctionMap() {
            @Override
            public int function() {
                return mtbddG;
            }

            @Override
            public int functionFor(int value) {
                return bijection.getFunction(value);
            }

            @Override
            public BitSet support() {
                return indices;
            }
        };
    }

    private int splitRecursive(
            int mtbddNode, BitSet splitVariables, int highestSplitVariable, SplitBijection bijection) {
        if (isConstant(mtbddNode)) {
            return of(bijection.intern(mtbddNode));
        }

        int variable = decisionVariable(mtbddNode);
        if (variable > highestSplitVariable) {
            return of(bijection.intern(mtbddNode));
        }

        int low = table.pushToWorkStack(
                splitRecursive(table.low(mtbddNode), splitVariables, highestSplitVariable, bijection));
        int high = table.pushToWorkStack(
                splitRecursive(table.high(mtbddNode), splitVariables, highestSplitVariable, bijection));

        int result = splitVariables.get(variable)
                ? makeFunction(variable, low, high)
                : splitCombineRecursive(low, high, variable, bijection);
        table.popFromWorkStack(2);
        return result;
    }

    private int splitCombineRecursive(int lowFragment, int highFragment, int variable, SplitBijection bijection) {
        if (lowFragment == highFragment) {
            return lowFragment;
        }
        if (isConstant(lowFragment) && isConstant(highFragment)) {
            int lowH = bijection.getFunction(constantFunctionToValue(lowFragment));
            int highH = bijection.getFunction(constantFunctionToValue(highFragment));
            int newH = makeFunction(variable, lowH, highH);
            return of(bijection.intern(newH));
        }

        int lowVariable = isConstant(lowFragment) ? Integer.MAX_VALUE : decisionVariable(lowFragment);
        int highVariable = isConstant(highFragment) ? Integer.MAX_VALUE : decisionVariable(highFragment);
        int splitVariable = Math.min(lowVariable, highVariable);

        int lowLow = lowVariable == splitVariable ? table.low(lowFragment) : lowFragment;
        int lowHigh = lowVariable == splitVariable ? table.high(lowFragment) : lowFragment;
        int highLow = highVariable == splitVariable ? table.low(highFragment) : highFragment;
        int highHigh = highVariable == splitVariable ? table.high(highFragment) : highFragment;

        int low = table.pushToWorkStack(splitCombineRecursive(lowLow, highLow, variable, bijection));
        int high = table.pushToWorkStack(splitCombineRecursive(lowHigh, highHigh, variable, bijection));
        int result = makeFunction(splitVariable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    private static final class SplitBijection {
        private final Map<Integer, Integer> functionToValue = new HashMap<>();
        private final IntArrayList valueToFunction = new IntArrayList();
        private final NodeTable table;

        public SplitBijection(NodeTable table) {
            this.table = table;
        }

        int intern(int function) {
            return functionToValue.computeIfAbsent(function, f -> {
                table.pushToWorkStack(f);
                return valueToFunction.add(f);
            });
        }

        int getFunction(int value) {
            return valueToFunction.get(value);
        }

        int size() {
            return valueToFunction.size();
        }
    }

    private static final class IntArrayList {
        private int[] values = new int[8];
        private int size = 0;

        int add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            int index = size;
            values[index] = value;
            size += 1;
            return index;
        }

        int get(int index) {
            assert index >= 0 && index < size;
            return values[index];
        }

        int size() {
            return size;
        }
    }

    @Override
    public FunctionToFunctionsMap cartesianProduct(int[] functions) {
        for (int function : functions) {
            assert isValidFunction(function);
        }

        if (functions.length == 0) {
            return new FunctionToFunctionsMap() {
                @Override
                public int function() {
                    return placeholder();
                }

                @Override
                public int[] functionFor(int value) {
                    throw new NoSuchElementException();
                }

                @Override
                public BitSet support() {
                    return new BitSet();
                }
            };
        }

        assert table.isWorkStackEmpty();
        for (int function : functions) {
            table.pushToWorkStack(function);
        }
        int[] values = new int[functions.length];
        TupleBijection bijection = new TupleBijection(values);
        int mtbddFunction = cartesianProductRecursive(
                Arrays.copyOf(functions, functions.length),
                values,
                0,
                new DepthPool<>(() -> new int[functions.length]),
                bijection);
        table.popFromWorkStack(functions.length);
        assert table.isWorkStackEmpty();

        BitSet indices = new BitSet();
        indices.set(0, bijection.size());
        return new FunctionToFunctionsMap() {
            @Override
            public int function() {
                return mtbddFunction;
            }

            @Override
            public int[] functionFor(int value) {
                return bijection.getTuple(value);
            }

            @Override
            public BitSet support() {
                return indices;
            }
        };
    }

    private int cartesianProductRecursive(
            int[] functions, int[] values, int depth, DepthPool<int[]> highPool, TupleBijection bijection) {
        int variable = minVariable(functions);
        if (variable == Integer.MAX_VALUE) {
            for (int i = 0; i < functions.length; i++) {
                values[i] = constantFunctionToValue(functions[i]);
            }
            return of(bijection.intern());
        }

        int[] highFunctions = highPool.get(depth);
        splitByVariable(functions, highFunctions, variable);

        int low = table.pushToWorkStack(cartesianProductRecursive(functions, values, depth + 1, highPool, bijection));
        int high =
                table.pushToWorkStack(cartesianProductRecursive(highFunctions, values, depth + 1, highPool, bijection));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    private static final class TupleBijection {
        private final Map<TupleKey, Integer> tupleToIndex = new HashMap<>();
        private final List<int[]> indexToTuple = new ArrayList<>();
        private final TupleKey lookupKey;

        TupleBijection(int[] values) {
            this.lookupKey = new TupleKey(values);
        }

        int intern() {
            Integer existingIndex = tupleToIndex.get(lookupKey);
            if (existingIndex != null) {
                return existingIndex;
            }
            int[] tuple = lookupKey.values.clone();
            int index = indexToTuple.size();
            indexToTuple.add(tuple);
            tupleToIndex.put(new TupleKey(tuple), index);
            return index;
        }

        int[] getTuple(int index) {
            return indexToTuple.get(index);
        }

        int size() {
            return indexToTuple.size();
        }
    }

    private static final class DepthPool<V> {
        private final Supplier<V> factory;
        private Object[] layers = new Object[8];

        DepthPool(Supplier<V> factory) {
            this.factory = factory;
        }

        @SuppressWarnings("unchecked")
        V get(int depth) {
            if (depth >= layers.length) {
                layers = Arrays.copyOf(layers, Math.max(depth + 1, layers.length * 2));
            }
            V value = (V) layers[depth];
            if (value == null) {
                value = factory.get();
                layers[depth] = value;
            }
            return value;
        }
    }

    private static final class TupleKey {
        private final int[] values;

        private TupleKey(int[] values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TupleKey && Arrays.equals(values, ((TupleKey) o).values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    @Override
    public int simplify(int mtbddFunction, int bddDomain) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddDomain);

        if (isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        assert table.isWorkStackEmpty();
        table.pushToWorkStack(mtbddFunction);
        int leaf = of(anyLeafValue(mtbddFunction));
        int result = simplifyRecursive(mtbddFunction, bddDomain, leaf);
        table.popFromWorkStack();
        assert table.isWorkStackEmpty();
        return result;
    }

    private int simplifyRecursive(int mtbddNode, int bddDomain, int leaf) {
        if (bddDomain == bdd.falseFunction()) {
            return leaf;
        }
        if (bddDomain == bdd.trueFunction() || isConstant(mtbddNode)) {
            return mtbddNode;
        }

        int mtbddVariable = decisionVariable(mtbddNode);
        int bddVariable = bdd.decisionVariable(bddDomain);
        int variable = Math.min(mtbddVariable, bddVariable);

        int mtbddLow = mtbddVariable == variable ? table.low(mtbddNode) : mtbddNode;
        int mtbddHigh = mtbddVariable == variable ? table.high(mtbddNode) : mtbddNode;
        int bddLow = bddVariable == variable ? bdd.lowOf(bddDomain) : bddDomain;
        int bddHigh = bddVariable == variable ? bdd.highOf(bddDomain) : bddDomain;

        if (bddLow == bdd.falseFunction()) {
            return simplifyRecursive(mtbddHigh, bddHigh, leaf);
        }
        if (bddHigh == bdd.falseFunction()) {
            return simplifyRecursive(mtbddLow, bddLow, leaf);
        }

        int low = table.pushToWorkStack(simplifyRecursive(mtbddLow, bddLow, leaf));
        int high = table.pushToWorkStack(simplifyRecursive(mtbddHigh, bddHigh, leaf));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    private int anyLeafValue(int mtbddNode) {
        int node = mtbddNode;
        while (!isConstant(node)) {
            node = table.low(node);
        }
        return constantFunctionToValue(node);
    }

    String format(int reference) {
        return isConstant(reference)
                ? String.format("V%d", constantFunctionToValue(reference))
                : String.format("N%d", reference);
    }

    @Override
    public Map<String, Object> statistics() {
        // No cache to concat in (unlike BddImpl/MddImpl) - see MTBDD_NOTES.md's "no caching anywhere" gap.
        Map<String, Object> statistics = new HashMap<>(table.statistics());
        statistics.put("allocated_values", allocatedValues.cardinality());
        return statistics;
    }

    private static final class PathIterator implements Iterator<BinaryPath> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final MtBddImpl mtbdd;
        private final int[] path;
        private final BitSet pathSupport;
        private final BitSet assignment;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private final int rootVariable;
        private int currentValue = -1;

        PathIterator(MtBddImpl mtbdd, int function) {
            assert mtbdd.isValidNonConstantFunction(function);

            this.mtbdd = mtbdd;
            int variableCount = mtbdd.numberOfVariables();
            this.path = new int[variableCount];
            this.pathSupport = new BitSet(variableCount);
            this.assignment = new BitSet(variableCount);

            rootVariable = mtbdd.decisionVariable(function);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = function;
            pathSupport.set(rootVariable);

            leafNodeVariable = 0;
            hasNextPath = true;
        }

        int currentValue() {
            return currentValue;
        }

        @Override
        public boolean hasNext() {
            return hasNextPath;
        }

        @Override
        public BinaryPath next() {
            assert IntStream.range(0, mtbdd.numberOfVariables())
                    .allMatch(i -> pathSupport.get(i) || path[i] == NON_PATH_NODE);

            int currentNode;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
            } else {
                assert hasNextPath : "Expected another path after " + assignment;

                // Backtrack until we find a node whose high branch we haven't taken yet.
                currentNode = path[leafNodeVariable];
                int branchVar = leafNodeVariable;

                while (assignment.get(branchVar)) {
                    branchVar = pathSupport.previousSetBit(branchVar - 1);
                    if (branchVar == -1) {
                        throw new NoSuchElementException("No next element");
                    }
                    currentNode = path[branchVar];
                }
                assert !assignment.get(branchVar);
                assert leafNodeVariable >= highestSwitchableVariable;
                assert mtbdd.decisionVariable(currentNode) == branchVar;
                assert pathSupport.get(branchVar);

                // currentNode is the shallowest node we can still switch to high; do so and descend anew.
                assignment.clear(branchVar + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);
                pathSupport.clear(branchVar + 1, leafNodeVariable + 1);

                assignment.set(branchVar);
                assert path[branchVar] == currentNode;
                currentNode = mtbdd.table.high(currentNode);
                leafNodeVariable = branchVar;

                // We just consumed the recorded switch candidate, if any - clear it.
                if (highestSwitchableVariable == leafNodeVariable) {
                    highestSwitchableVariable = -1;
                }
            }

            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (!mtbdd.isConstant(currentNode)) {
                leafNodeVariable = mtbdd.decisionVariable(currentNode);
                path[leafNodeVariable] = currentNode;
                pathSupport.set(leafNodeVariable);

                // Every decision node's high branch is a genuine, always-reachable alternative (no FALSE
                // to prune here) - the first one found on this descent, unless one is already recorded,
                // becomes the next backtracking target.
                if (!hasNextPath) {
                    hasNextPath = true;
                    highestSwitchableVariable = leafNodeVariable;
                }
                currentNode = mtbdd.table.low(currentNode);
            }
            currentValue = constantFunctionToValue(currentNode);

            return new BinaryPath(assignment, pathSupport);
        }
    }

    private static final class AssignmentIterator implements Iterator<BitSet> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final MtBddImpl mtbdd;
        private final IntPredicate values;
        private final BitSet support;
        private final int[] path;
        private final BitSet assignment;
        // TODO Improve on this
        // 0 = unknown, 1 = reaches a match, 2 = does not; indexed by decision node id
        private final byte[] reachesMatch;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private boolean hasNextAssignment;
        private final int rootVariable;

        AssignmentIterator(MtBddImpl mtbdd, int function, IntPredicate values, BitSet support) {
            assert !mtbdd.isConstant(function);

            this.mtbdd = mtbdd;
            this.values = values;
            this.support = support;
            this.path = new int[mtbdd.numberOfVariables()];
            this.assignment = new BitSet(mtbdd.numberOfVariables());
            this.reachesMatch = new byte[mtbdd.table.size()];

            if (!canReachMatch(function)) {
                // No solution exists at all - leave the iterator in an immediately-exhausted state.
                rootVariable = -1;
                hasNextPath = false;
                hasNextAssignment = false;
                return;
            }

            rootVariable = mtbdd.decisionVariable(function);
            assert support.get(rootVariable);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = function;

            leafNodeVariable = 0;
            hasNextPath = true;
            hasNextAssignment = true;
        }

        private boolean canReachMatch(int node) {
            if (mtbdd.isConstant(node)) {
                return values.test(constantFunctionToValue(node));
            }
            byte cached = reachesMatch[node];
            if (cached != 0) {
                return cached == 1;
            }
            boolean result = canReachMatch(mtbdd.table.low(node)) || canReachMatch(mtbdd.table.high(node));
            reachesMatch[node] = (byte) (result ? 1 : 2);
            return result;
        }

        @Override
        public boolean hasNext() {
            assert !hasNextPath || hasNextAssignment;
            return hasNextAssignment;
        }

        @Override
        public BitSet next() {
            assert IntStream.range(0, mtbdd.numberOfVariables())
                    .allMatch(i -> support.get(i) || path[i] == NON_PATH_NODE);

            int currentNode;
            if (firstRun) {
                firstRun = false;
                currentNode = path[rootVariable];
            } else {
                // Try to advance any non-path ("don't care") support variable, treating them as a binary
                // counter: flip the lowest 0-bit to 1 (done), or clear a 1-bit and carry into the next one.
                boolean clearedAny = false;
                for (int var = support.nextSetBit(0); var >= 0; var = support.nextSetBit(var + 1)) {
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
                                for (int i = support.nextSetBit(var + 1); i >= 0; i = support.nextSetBit(i + 1)) {
                                    if (path[i] == NON_PATH_NODE && !assignment.get(i)) {
                                        hasNextAssignment = true;
                                        break;
                                    }
                                }
                            }
                            assert values.test(mtbdd.evaluate(path[rootVariable], assignment));
                            return assignment;
                        }
                    }
                }

                // All don't-cares for the current path are exhausted - backtrack through the path until we
                // find a node whose high branch we haven't taken yet and which can still reach a match.
                assert hasNextPath : "Expected another path after " + assignment;

                currentNode = path[leafNodeVariable];
                int branchVar = leafNodeVariable;

                while (assignment.get(branchVar) || !canReachMatch(mtbdd.table.high(currentNode))) {
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
                assert mtbdd.decisionVariable(currentNode) == branchVar;

                // currentNode is the lowest node we can still switch to high; do so and descend anew.
                assignment.clear(branchVar + 1, leafNodeVariable + 1);
                Arrays.fill(path, branchVar + 1, leafNodeVariable + 1, NON_PATH_NODE);

                assignment.set(branchVar);
                assert path[branchVar] == currentNode;
                currentNode = mtbdd.table.high(currentNode);
                assert canReachMatch(currentNode);
                leafNodeVariable = branchVar;

                // We just consumed the recorded switch candidate, if any - clear it.
                if (highestSwitchableVariable == leafNodeVariable) {
                    highestSwitchableVariable = -1;
                }
            }

            // currentNode's valuation was just flipped to 1, or we're in the initial state - descend,
            // preferring the low (0) branch for lexicographic ascending order, tracking the shallowest
            // still-open alternative (highestSwitchableVariable) for future backtracking.
            hasNextPath = highestSwitchableVariable > -1 && highestSwitchableVariable < leafNodeVariable;

            while (!mtbdd.isConstant(currentNode)) {
                leafNodeVariable = mtbdd.decisionVariable(currentNode);
                path[leafNodeVariable] = currentNode;
                assert support.get(leafNodeVariable);

                int low = mtbdd.table.low(currentNode);
                if (!canReachMatch(low)) {
                    assignment.set(leafNodeVariable);
                    currentNode = mtbdd.table.high(currentNode);
                } else {
                    if (!hasNextPath && canReachMatch(mtbdd.table.high(currentNode))) {
                        hasNextPath = true;
                        highestSwitchableVariable = leafNodeVariable;
                    }
                    currentNode = low;
                }
            }
            assert values.test(constantFunctionToValue(currentNode));
            assert values.test(mtbdd.evaluate(path[rootVariable], assignment));

            // If any support variable is still off-path, this leaf alone yields more than one solution
            // (the don't-care powerset), so there is always a next assignment regardless of hasNextPath.
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                if (path[i] == NON_PATH_NODE) {
                    assert !assignment.get(i);
                    hasNextAssignment = true;
                    return assignment;
                }
            }
            hasNextAssignment = hasNextPath;
            return assignment;
        }
    }

    private static final class MtBddTable extends NodeTable.Binary {
        private final MtBddImpl mtbdd;
        private final BitSet markedValues = new BitSet();

        MtBddTable(MtBddImpl mtbdd, int initialSize) {
            super(initialSize);
            this.mtbdd = mtbdd;
        }

        @Override
        public boolean isValidConstant(int pointer) {
            return mtbdd.isConstant(pointer);
        }

        @Override
        public boolean isValidPointer(int pointer) {
            return mtbdd.isValidFunction(pointer);
        }

        private boolean isUnmarkedConstant(int node) {
            return mtbdd.isConstant(node) && !markedValues.get(constantFunctionToValue(node));
        }

        private boolean isMarkedConstant(int node) {
            return mtbdd.isConstant(node) && markedValues.get(constantFunctionToValue(node));
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int low = low(node);
            int high = high(node);
            return (isUnmarkedConstant(low) || doIsNoneMarkedBelow(low))
                    && (isUnmarkedConstant(high) || doIsNoneMarkedBelow(high));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node, boolean includeLeafs) {
            int low = low(node);
            int high = high(node);
            return ((!includeLeafs && mtbdd.isConstant(low))
                            || isMarkedConstant(low)
                            || doIsAllMarkedBelow(low, includeLeafs))
                    && ((!includeLeafs && mtbdd.isConstant(high))
                            || isMarkedConstant(high)
                            || doIsAllMarkedBelow(high, includeLeafs));
        }

        @Override
        protected void markLeafNodeIfManaged(int node, boolean mark) {
            assert mtbdd.isValidConstant(node);
            markedValues.set(constantFunctionToValue(node), mark);
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark, boolean includeLeaves) {
            int low = low(node);
            int high = high(node);
            int sum = 0;
            if (mtbdd.isConstant(low)) {
                if (includeLeaves) {
                    markedValues.set(constantFunctionToValue(low), mark);
                }
            } else {
                sum += doSetMarkBelow(low, mark, includeLeaves);
            }
            if (mtbdd.isConstant(high)) {
                if (includeLeaves) {
                    markedValues.set(constantFunctionToValue(high), mark);
                }
            } else {
                sum += doSetMarkBelow(high, mark, includeLeaves);
            }
            return sum;
        }

        @Override
        protected void recurseForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
            int low = low(node);
            int high = high(node);
            if (!mtbdd.isConstant(low)) {
                doForEachVariable(low, action, filter, depthLimit);
            }
            if (!mtbdd.isConstant(high)) {
                doForEachVariable(high, action, filter, depthLimit);
            }
        }

        @Override
        public int treeNodeFor(int pointer) {
            return mtbdd.nodeFor(pointer);
        }

        @Override
        protected boolean isLeafNode(int node) {
            return mtbdd.isConstant(node);
        }

        @Override
        protected boolean isValidLeafNode(int node) {
            return mtbdd.isValidConstant(node);
        }

        @Override
        public boolean ensureCapacity() {
            if (freeNodeCount() > size() / 4) {
                return false;
            }

            NodeTable table = mtbdd.table;
            BddConfiguration configuration = mtbdd.bdd.configuration();
            int currentSize = table.size();
            int approximateDeadNodeCount = table.approximateDeadNodeCount();
            if (configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
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
                    mtbdd.clearCacheAfterGC(reclaimedNodes);
                    assert mtbdd.check();
                    return false;
                }

                logger.log(Level.FINER, "Not enough free nodes");
                table.invalidateUnmarkedNodes();
            }
            //noinspection NumericCastThatLosesPrecision
            table.grow((int) (currentSize * configuration.growthFactor()));
            assert mtbdd.check();
            return true;
        }

        @Override
        protected void invalidateUnmarkedAndUnreferencedLeaves() {
            assert BitSets.isSubset(markedValues, mtbdd.allocatedValues);
            mtbdd.allocatedValues.andNot(markedValues);
            for (int i = mtbdd.allocatedValues.nextSetBit(0);
                    i >= 0 && i < mtbdd.valueReferenceCounts.length;
                    i = mtbdd.allocatedValues.nextSetBit(i + 1)) {
                if (mtbdd.valueReferenceCounts[i] == 0) {
                    mtbdd.allocatedValues.clear(i);
                }
            }
            mtbdd.allocatedValues.clear(mtbdd.valueReferenceCounts.length, Integer.MAX_VALUE);
            mtbdd.allocatedValues.or(markedValues);
            markedValues.clear();
        }

        @Override
        protected boolean anyManagedLeafMarked() {
            return !markedValues.isEmpty();
        }

        @Override
        protected void unmarkAllManagedLeafs() {
            markedValues.clear();
        }

        @Override
        protected boolean isLeafNodeMarkedOrUnmanaged(int leaf) {
            return markedValues.get(constantFunctionToValue(leaf));
        }

        @Override
        protected boolean isLeafUnmarkedOrUnmanaged(int leaf) {
            return !markedValues.get(constantFunctionToValue(leaf));
        }

        @Override
        public String format(int pointer) {
            return mtbdd.format(pointer);
        }
    }

    private void clearCacheAfterGC(int reclaimedNodes) {}

    boolean check() {
        table.check();

        for (int value = 0; value < valueReferenceCounts.length; value++) {
            checkState(valueReferenceCounts[value] >= 0);
            checkState(
                    valueReferenceCounts[value] == 0 || allocatedValues.get(value),
                    "Value %d is referenced but not allocated",
                    value);
        }

        return true;
    }

    private static final class FunctionInverse implements Inverse {
        private final int function;
        private final IntUnaryOperator functionFor;
        private final BitSet values;

        public FunctionInverse(int function, IntUnaryOperator functionFor, BitSet values) {
            this.function = function;
            this.functionFor = functionFor;
            this.values = values;
        }

        @Override
        public int function() {
            return function;
        }

        @Override
        public int functionFor(int value) {
            return functionFor.applyAsInt(value);
        }

        @Override
        public BitSet support() {
            return values;
        }
    }
}
