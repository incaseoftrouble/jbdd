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
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/*
 * Important differences to BDDs:
 *  - In a generic MTBDD we have no commutativity and neutral elements, hence much more "base case" branching is required
 */
@SuppressWarnings({"PMD", "AssignmentToMethodParameter"})
public class MtBddImpl implements MtBdd, NodeBasedDecisionDiagram {
    private static final int INVERT_ARRAY_DOMAIN_THRESHOLD = 32;
    private static final int INITIAL_VALUE_CAPACITY = 1024;
    private static final Logger logger = Logger.getLogger(MtBddImpl.class.getName());

    private final BddImpl bdd;
    private final MtBddTable table;
    private final MtBddCache cache;
    private byte[] valueReferenceCounts;
    private static final byte MAXIMUM_REFERENCE_COUNT = Byte.MAX_VALUE;
    // Convert to sparse bit set?
    private final BitSet allocatedValues = new BitSet();
    private final NodeLifecycleObserverGroup<NodeLifecycleObserver> observers = new NodeLifecycleObserverGroup<>();
    private final ProtectionTracker protectionTracker;

    MtBddImpl(BddImpl bdd) {
        this.bdd = bdd;
        this.table = new MtBddTable(this, bdd.configuration().mtbddInitialSize());
        this.cache = new MtBddCache(this, bdd);
        this.valueReferenceCounts = new byte[INITIAL_VALUE_CAPACITY];
        this.protectionTracker = bdd.protectionTracker();
        observers.registerStrongly(protectionTracker);

        // Strongly: these two hooks are owned by this MTBDD, nothing else holds them - see
        // NodeLifecycleObserverGroup#registerStrongly.
        observers.registerStrongly(new NodeLifecycleObserver() {
            @Override
            public void afterGc(int reclaimedNodes, BitSet reclaimedValues) {
                cache.onMultiTerminalNodesInvalidated(reclaimedNodes);
            }

            @Override
            public void afterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
                cache.tableSizeChanged(invalidatedNodes);
            }
        });
        bdd.registerOwnedObserver(new NodeLifecycleObserver() {
            @Override
            public void afterGc(int reclaimedNodes, BitSet reclaimedValues) {
                cache.onBooleanNodesInvalidated(reclaimedNodes);
            }

            @Override
            public void afterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
                cache.onBooleanNodesInvalidated(invalidatedNodes);
            }
        });
    }

    MtBddCache cache() {
        return cache;
    }

    void invalidateCache() {
        cache.invalidate();
    }

    ProtectionTracker protectionTracker() {
        return protectionTracker;
    }

    void registerObserver(NodeLifecycleObserver observer) {
        observers.register(observer);
    }

    void notifyBeforeGc() {
        observers.dispatch(NodeLifecycleObserver::beforeGc);
    }

    void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
        observers.dispatch(observer -> observer.afterGc(reclaimedNodes, reclaimedValues));
    }

    void notifyAfterTableGrow(int reclaimedNodes, BitSet reclaimedValues) {
        observers.dispatch(observer -> observer.afterTableGrowth(reclaimedNodes, reclaimedValues));
    }

    public int forceGc() {
        notifyBeforeGc();
        table.markAllReferencedNodes();
        // Leaves first: reclaimUnmarkedNodes asserts that nothing is marked afterwards, and leaf marks
        // live in their own BitSet which only the leaf sweep clears (see MtBddTable#ensureCapacity,
        // which has the same two steps in this order).
        BitSet reclaimedValues = table.invalidateUnmarkedAndUnreferencedLeaves();
        int reclaimedNodes = table.reclaimUnmarkedNodes();
        assert table().isNoneMarked();
        notifyAfterGc(reclaimedNodes, reclaimedValues);
        return reclaimedNodes;
    }

    @Override
    public Bdd bdd() {
        return bdd;
    }

    BddImpl bddImpl() {
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
            assert value < valueReferenceCounts.length && valueReferenceCounts[value] > 0
                    : "Dereferencing value " + value + ", which was never referenced";
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
    public boolean isUnmanaged(int function) {
        return isSaturatedNode(nodeFor(function));
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

    @Override
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
        // TODO Heuristically trigger GC if too many values are allocated.
        allocatedValues.set(value);
        return valueToConstantFunction(value);
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        assert 0 <= variable && variable < numberOfVariables() : "Variable " + variable + " does not exist";
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
    public BitSet valuesOf(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return BitSets.of(constantFunctionToValue(function));
        }

        // The mark phase computes exactly this set as a side effect, so copy it out directly instead of
        // going through forEachValue's IntConsumer round-trip.
        assert table.isNoneMarkedBelowNode(function);
        table.markAllBelowNode(function, true);
        BitSet values = BitSets.copyOf(table.markedValues);
        table.unMarkAllBelowNode(function, true);
        assert table.isNoneMarkedBelowNode(function);
        return values;
    }

    @Override
    public void forEachPath(int function, PathConsumer action) {
        ValuedIterator<BinaryPath> iterator = pathIterator(function);
        while (iterator.hasNext()) {
            BinaryPath path = iterator.next();
            action.accept(path, iterator.value());
        }
    }

    @Override
    public ValuedIterator<BinaryPath> pathIterator(int function) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            // The single, entirely unconstrained path.
            BitSet empty = BitSets.of();
            return new SingletonValuedIterator<>(new BinaryPath(empty, empty), constantFunctionToValue(function));
        }
        return new PathIterator(this, function);
    }

    @Override
    public Optional<BitSet> anyAssignment(int function, IntPredicate values) {
        assert isValidFunction(function);

        cache.initAnyValueMatches(values);
        BitSet assigment = new BitSet(numberOfVariables());
        boolean found = anyAssigmentRecursive(function, values, assigment);
        return found ? Optional.of(assigment) : Optional.empty();
    }

    private boolean anyAssigmentRecursive(int function, @Nullable IntPredicate values, BitSet assignment) {
        if (isConstant(function)) {
            return values == null || values.test(constantFunctionToValue(function));
        }
        int low = table.low(function);
        if (canReachMatch(low, values) && anyAssigmentRecursive(low, values, assignment)) {
            return true;
        }
        int high = table.high(function);
        if (canReachMatch(high, values) && anyAssigmentRecursive(high, values, assignment)) {
            assignment.set(decisionVariable(function));
            return true;
        }
        return false;
    }

    private boolean canReachMatch(int node, @Nullable IntPredicate values) {
        if (isConstant(node)) {
            return values == null || values.test(constantFunctionToValue(node));
        }
        if (values == null) {
            return true;
        }
        assert cache.isCurrentAnyValueMatches(values);
        if (cache.lookupNoValueMatches(node)) {
            return false;
        }
        boolean result = canReachMatch(table.low(node), values) || canReachMatch(table.high(node), values);
        if (!result) {
            cache.markNoValueMatches(node);
        }
        return result;
    }

    @Override
    public BigInteger countAssignments(int function, IntPredicate values) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function)) ? TWO.pow(numberOfVariables()) : ZERO;
        }

        cache.initCount(values);
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
        BigInteger cached = cache.lookupCount(node);
        if (cached != null) {
            return cached;
        }
        int hash = cache.lookupHash();

        int nodeVar = table.variable(node);
        BigInteger lowCount = doCountSatisfyingAssignments(table.low(node), nodeVar, values);
        BigInteger highCount = doCountSatisfyingAssignments(table.high(node), nodeVar, values);
        BigInteger result = lowCount.add(highCount);
        cache.putCount(hash, node, result);
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
    public ValuedIterator<BitSet> assignmentIterator(int function, @Nullable IntPredicate values) {
        assert isValidFunction(function);

        BitSet support = new BitSet(numberOfVariables());
        support.set(0, numberOfVariables());
        return assignmentIterator(function, values, support);
    }

    @Override
    public ValuedIterator<BitSet> assignmentIterator(int function, @Nullable IntPredicate values, BitSet support) {
        assert isValidFunction(function);
        assert BitSets.isSubset(support(function), support);

        if (isConstant(function)) {
            int value = constantFunctionToValue(function);
            // Every assignment yields the same value, so the whole power set is (or is not) a solution.
            return (values == null || values.test(value))
                    ? new ConstantValuedIterator<>(BitSets.powerSetIterator(support), value)
                    : new ConstantValuedIterator<>(Collections.emptyIterator(), value);
        }
        cache.initAnyValueMatches(values);
        return new AssignmentIterator(this, function, values, support);
    }

    @Override
    public int apply(int function1, int function2, MtBddBinaryOperator operator) {
        return apply(function1, function2, bdd.trueFunction(), operator, null, null);
    }

    @Override
    public int applySimplify(int function1, int function2, MtBddBinaryOperator operator, int bddDomain) {
        return apply(function1, function2, bddDomain, operator, null, null);
    }

    @Override
    public RegisteredOperation.Binary registerApply(MtBddBinaryOperator operator) {
        return new MtBddOperations.Apply(this, operator, false);
    }

    @Override
    public RegisteredOperation.Ternary registerApplySimplify(MtBddBinaryOperator operator) {
        return new MtBddOperations.Apply(this, operator, true);
    }

    /**
     * Shared implementation for both the ordinary, ephemeral-cache-backed {@code apply}/{@code applySimplify}
     * and {@link MtBddOperations}, which supplies its own persistent caches instead so that
     * {@code cache.initApply}'s reuse-detection - which would otherwise invalidate the whole cache on every
     * call with a different operator - can be skipped entirely: a registered applier's operator never
     * changes. Either both caches are supplied or neither is; a registered applier without a simplify cache
     * (the plain {@link #registerApply}) may only be invoked with a {@code TRUE} domain.
     */
    int apply(
            int function1,
            int function2,
            int bddDomain,
            MtBddBinaryOperator operator,
            MtBddCache.@Nullable BinaryToIntCache registeredApplyCache,
            MtBddCache.@Nullable ApplySimplifyCache registeredApplySimplifyCache) {
        assert isValidFunction(function1) && isValidFunction(function2);
        assert bdd.isValidFunction(bddDomain);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        if (bddDomain == bdd.falseFunction()) {
            // Nothing is constrained, so any constant is a valid answer - pick one from the operands'
            // co-domains rather than recursing at all (see #simplify).
            return of(operator.applyAsInt(anyLeafValue(function1), anyLeafValue(function2)));
        }

        MtBddCache.BinaryToIntCache applyCache = registeredApplyCache;
        MtBddCache.ApplySimplifyCache applySimplifyCache = registeredApplySimplifyCache;
        if (applyCache == null) {
            cache.initApply(operator);
            applyCache = cache.applyCache();
            applySimplifyCache = cache.applySimplifyCache();
        }
        assert bddDomain == bdd.trueFunction() || applySimplifyCache != null
                : "A domain-carrying apply must be registered through registerApplySimplify";

        table.pushToWorkStack(function1, function2);
        // The domain narrowing below builds Bdd nodes (see computeApply's widening step), so the domain
        // needs Bdd-side protection for the whole recursion, exactly like constrainSimplify's.
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = computeApply(function1, function2, bddDomain, operator, applyCache, applySimplifyCache);
        bddTable.popFromWorkStack();
        table.popFromWorkStack(2);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        return result;
    }

    /**
     * The apply recursion, with {@code simplify} integrated directly (most of the code path is shared, as in
     * {@link BddImpl}'s {@code computeComposeSimplify}). The classic {@code apply} is the {@code bddDomain ==
     * TRUE} special case: every domain-driven branch below is then inert and the plain, binary-keyed
     * {@code applyCache} is used instead of the ternary one.
     */
    private int computeApply(
            int function1,
            int function2,
            int bddDomain,
            MtBddBinaryOperator operator,
            MtBddCache.BinaryToIntCache applyCache,
            MtBddCache.@Nullable ApplySimplifyCache applySimplifyCache) {
        assert bddDomain != bdd.falseFunction();
        boolean universeDomain = bddDomain == bdd.trueFunction();
        assert universeDomain || applySimplifyCache != null;

        boolean constant1 = isConstant(function1);
        boolean constant2 = isConstant(function2);

        if (constant1) {
            int v1 = constantFunctionToValue(function1);
            if (v1 == operator.absorbing) {
                return function1;
            }
            if (v1 == operator.neutral) {
                return computeSimplify(function2, bddDomain);
            }
            if (constant2) {
                int v2 = constantFunctionToValue(function2);
                return of(operator.applyAsInt(v1, v2));
            }
        } else if (constant2) {
            int v2 = constantFunctionToValue(function2);
            if (v2 == operator.absorbing) {
                return function2;
            }
            if (v2 == operator.neutral) {
                return computeSimplify(function1, bddDomain);
            }
        }

        if (operator.commutative && function1 > function2) {
            int functionSwap = function1;
            function1 = function2;
            function2 = functionSwap;
            constant1 = isConstant(function1);
            constant2 = isConstant(function2);
        }

        int lookup;
        int hash;
        if (universeDomain) {
            lookup = applyCache.lookup(function1, function2);
            hash = applyCache.lookupHash();
        } else {
            lookup = applySimplifyCache.lookup(function1, function2, bddDomain);
            hash = applySimplifyCache.lookupHash();
        }
        if (lookup != placeholder()) {
            return lookup;
        }

        // To simplify branching, use MAX_VALUE as a sentinel for "this side has no variable left"
        int variable1 = constant1 ? Integer.MAX_VALUE : decisionVariable(function1);
        int variable2 = constant2 ? Integer.MAX_VALUE : decisionVariable(function2);
        int variable = Math.min(variable1, variable2);
        assert variable != Integer.MAX_VALUE : "Two constants are handled above";
        int domainVariable = universeDomain ? Integer.MAX_VALUE : bdd.decisionVariable(bddDomain);

        int result;
        if (domainVariable < variable) {
            int domainLow = bdd.lowOf(bddDomain);
            int domainHigh = bdd.highOf(bddDomain);
            if (domainLow == bdd.falseFunction()) {
                result = computeApply(function1, function2, domainHigh, operator, applyCache, applySimplifyCache);
            } else if (domainHigh == bdd.falseFunction()) {
                result = computeApply(function1, function2, domainLow, operator, applyCache, applySimplifyCache);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(domainLow, domainHigh));
                result = computeApply(function1, function2, widenedDomain, operator, applyCache, applySimplifyCache);
                bdd.table().popFromWorkStack();
            }
        } else {
            int low1 = variable1 == variable ? table.low(function1) : function1;
            int high1 = variable1 == variable ? table.high(function1) : function1;
            int low2 = variable2 == variable ? table.low(function2) : function2;
            int high2 = variable2 == variable ? table.high(function2) : function2;

            boolean domainTestsVariable = domainVariable == variable;
            int lowDomain = domainTestsVariable ? bdd.lowOf(bddDomain) : bddDomain;
            int highDomain = domainTestsVariable ? bdd.highOf(bddDomain) : bddDomain;

            if (lowDomain == bdd.falseFunction()) {
                // The domain forces this variable, so only one branch is constrained - drop the node.
                result = computeApply(high1, high2, highDomain, operator, applyCache, applySimplifyCache);
            } else if (highDomain == bdd.falseFunction()) {
                result = computeApply(low1, low2, lowDomain, operator, applyCache, applySimplifyCache);
            } else {
                int low = table.pushToWorkStack(
                        computeApply(low1, low2, lowDomain, operator, applyCache, applySimplifyCache));
                int high = table.pushToWorkStack(
                        computeApply(high1, high2, highDomain, operator, applyCache, applySimplifyCache));
                result = makeFunction(variable, low, high);
                table.popFromWorkStack(2);
            }
        }

        if (universeDomain) {
            applyCache.put(hash, function1, function2, result);
        } else {
            applySimplifyCache.put(hash, function1, function2, bddDomain, result);
        }
        return result;
    }

    @Override
    public int map(int function, IntUnaryOperator map) {
        return mapSimplify(function, map, bdd.trueFunction());
    }

    @Override
    public int mapSimplify(int function, IntUnaryOperator map, int bddDomain) {
        assert isValidFunction(function);
        assert bdd.isValidFunction(bddDomain);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        if (bddDomain == bdd.falseFunction()) {
            return of(map.applyAsInt(anyLeafValue(function)));
        }

        cache.initMap(map);
        table.pushToWorkStack(function);
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = computeMap(function, bddDomain, map);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        return result;
    }

    /** The unary counterpart of {@link #computeApply}; see there for the domain handling. */
    private int computeMap(int function, int bddDomain, IntUnaryOperator map) {
        assert bddDomain != bdd.falseFunction();

        if (isConstant(function)) {
            return of(map.applyAsInt(constantFunctionToValue(function)));
        }

        int lookup = bddDomain == bdd.trueFunction()
                ? cache.lookupMap(function)
                : cache.lookupMapSimplify(function, bddDomain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int variable = decisionVariable(function);
        int domainVariable = bddDomain == bdd.trueFunction() ? Integer.MAX_VALUE : bdd.decisionVariable(bddDomain);

        int result;
        if (domainVariable < variable) {
            int domainLow = bdd.lowOf(bddDomain);
            int domainHigh = bdd.highOf(bddDomain);
            if (domainLow == bdd.falseFunction()) {
                result = computeMap(function, domainHigh, map);
            } else if (domainHigh == bdd.falseFunction()) {
                result = computeMap(function, domainLow, map);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(domainLow, domainHigh));
                result = computeMap(function, widenedDomain, map);
                bdd.table().popFromWorkStack();
            }
        } else {
            boolean domainTestsVariable = domainVariable == variable;
            int lowDomain = domainTestsVariable ? bdd.lowOf(bddDomain) : bddDomain;
            int highDomain = domainTestsVariable ? bdd.highOf(bddDomain) : bddDomain;

            if (lowDomain == bdd.falseFunction()) {
                result = computeMap(table.high(function), highDomain, map);
            } else if (highDomain == bdd.falseFunction()) {
                result = computeMap(table.low(function), lowDomain, map);
            } else {
                int low = table.pushToWorkStack(computeMap(table.low(function), lowDomain, map));
                int high = table.pushToWorkStack(computeMap(table.high(function), highDomain, map));
                result = makeFunction(variable, low, high);
                table.popFromWorkStack(2);
            }
        }

        if (bddDomain == bdd.trueFunction()) {
            cache.putMap(hash, function, result);
        } else {
            cache.putMapSimplify(hash, function, bddDomain, result);
        }
        return result;
    }

    @Override
    public int apply(int[] functions, MtBddNaryOperator operator) {
        assert functions.length == operator.arity : "Operator declares arity " + operator.arity;

        if (functions.length == 0) {
            return placeholder();
        }
        if (operator instanceof MtBddNaryOperator.Unary) {
            return this.map(functions[0], (MtBddNaryOperator.Unary) operator);
        }
        if (operator instanceof MtBddNaryOperator.Binary) {
            return this.apply(functions[0], functions[1], (MtBddNaryOperator.Binary) operator);
        }
        for (int function : functions) {
            assert isValidFunction(function);
        }

        assert table.workStacksEmpty();
        for (int function : functions) {
            table.pushToWorkStack(function);
        }
        int[] copy = Arrays.copyOf(functions, functions.length);
        if (operator.commutative) {
            // No cache to canonicalize against yet (see MtBddNaryOperator's javadoc) - sorting is still
            // cheap and harmless, and future-proofs this the moment computeNaryApply gets one.
            Arrays.sort(copy);
        }
        int[] values = new int[functions.length];
        int result = computeNaryApply(copy, values, operator, 0, new DepthPool<>(() -> new int[functions.length]));
        table.popFromWorkStack(functions.length);
        assert table.workStacksEmpty();
        return result;
    }

    private int computeNaryApply(
            int[] functions, int[] values, MtBddNaryOperator operator, int depth, DepthPool<int[]> highPool) {
        if (operator.absorbing != -1) {
            for (int function : functions) {
                if (isConstant(function) && constantFunctionToValue(function) == operator.absorbing) {
                    return function;
                }
            }
        }
        if (operator.neutral != -1) {
            boolean hasSurvivor = false;
            int survivor = placeholder();
            boolean multipleSurvivors = false;
            for (int function : functions) {
                boolean isNeutral = isConstant(function) && constantFunctionToValue(function) == operator.neutral;
                if (!isNeutral) {
                    if (!hasSurvivor) {
                        hasSurvivor = true;
                        survivor = function;
                    } else {
                        multipleSurvivors = true;
                        break;
                    }
                }
            }
            if (!multipleSurvivors) {
                return hasSurvivor ? survivor : of(operator.neutral);
            }
        }

        int variable = minVariable(functions);
        if (variable == Integer.MAX_VALUE) {
            for (int i = 0; i < functions.length; i++) {
                values[i] = constantFunctionToValue(functions[i]);
            }
            return of(operator.applyAsInt(values));
        }

        int[] highFunctions = highPool.get(depth);
        splitByVariable(functions, highFunctions, variable);

        int low = table.pushToWorkStack(computeNaryApply(functions, values, operator, depth + 1, highPool));
        int high = table.pushToWorkStack(computeNaryApply(highFunctions, values, operator, depth + 1, highPool));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    private int minVariable(int... functions) {
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
        assert bdd.table().workStacksEmpty();
        int result = agreementRecursive(mtbddFunction1, mtbddFunction2);
        assert bdd.table().workStacksEmpty();
        return result;
    }

    private int agreementRecursive(int mtbddNode1, int mtbddNode2) {
        if (mtbddNode1 == mtbddNode2) {
            return bdd.trueFunction();
        }

        boolean constant1 = isConstant(mtbddNode1);
        boolean constant2 = isConstant(mtbddNode2);
        if (constant1 && constant2) {
            boolean agree = constantFunctionToValue(mtbddNode1) == constantFunctionToValue(mtbddNode2);
            return agree ? bdd.trueFunction() : bdd.falseFunction();
        }

        if (mtbddNode1 > mtbddNode2) {
            int nodeSwap = mtbddNode1;
            mtbddNode1 = mtbddNode2;
            mtbddNode2 = nodeSwap;
            constant1 = isConstant(mtbddNode1);
            constant2 = isConstant(mtbddNode2);
        }

        int lookup = cache.lookupAgreement(mtbddNode1, mtbddNode2);
        if (lookup != bdd.placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

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
        cache.putAgreement(hash, mtbddNode1, mtbddNode2, bddResult);
        return bddResult;
    }

    @Override
    public int mapBoolean(int mtbddFunction, IntPredicate values) {
        assert isValidFunction(mtbddFunction);
        assert bdd.table().workStacksEmpty();
        cache.initMapBoolean(values);
        int result = mapBooleanRecursive(mtbddFunction, values);
        assert bdd.table().workStacksEmpty();
        return result;
    }

    private int mapBooleanRecursive(int mtbddNode, IntPredicate values) {
        if (isConstant(mtbddNode)) {
            return values.test(constantFunctionToValue(mtbddNode)) ? bdd.trueFunction() : bdd.falseFunction();
        }

        int lookup = cache.lookupMapBoolean(mtbddNode);
        if (lookup != bdd.placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int variable = decisionVariable(mtbddNode);
        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(mapBooleanRecursive(table.low(mtbddNode), values));
        int bddHigh = bddTable.pushToWorkStack(mapBooleanRecursive(table.high(mtbddNode), values));
        int bddResult = bdd.makeFunction(variable, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        cache.putMapBoolean(hash, mtbddNode, bddResult);
        return bddResult;
    }

    @Override
    public int update(int mtbddFunction, int bddAssignments, int value) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddAssignments);

        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result = updateRecursive(mtbddFunction, bddAssignments, value);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        return result;
    }

    private int updateRecursive(int mtbddNode, int bddNode, int value) {
        if (bddNode == bdd.falseFunction()) {
            return mtbddNode;
        }
        if (bddNode == bdd.trueFunction()) {
            return of(value);
        }

        int lookup = cache.lookupUpdate(mtbddNode, bddNode, value);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

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
        cache.putUpdate(hash, mtbddNode, bddNode, value, result);
        return result;
    }

    @Override
    public int compose(int mtbddFunction, int[] bddVariableMapping) {
        return composeSimplify(mtbddFunction, bddVariableMapping, bdd.trueFunction());
    }

    @Override
    public int composeSimplify(int mtbddFunction, int[] bddVariableMapping, int bddDomain) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddDomain);
        assert bddVariableMapping.length <= numberOfVariables();

        if (isConstant(mtbddFunction)) {
            return mtbddFunction;
        }
        if (bddDomain == bdd.falseFunction()) {
            return of(anyLeafValue(mtbddFunction));
        }

        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(bddVariableMapping);
        if (analysis.highestReplacedVariable == -1) {
            return simplify(mtbddFunction, bddDomain);
        }

        // Plain compose builds no Bdd nodes at all (ifThenElseRecursive only reads the replacements), but
        // narrowing a domain does - so from here on the replacement array needs the same Bdd-side
        // protection BddImpl#composeSimplify gives it. A registered composer references them instead.
        NodeTable bddTable = bdd.table();
        int bddWorkStackCount = 0;
        if (bddDomain != bdd.trueFunction()) {
            for (int replacement : bddVariableMapping) {
                assert bdd.isValidFunction(replacement);
                if (!bdd.isUnmanaged(replacement)) {
                    bddTable.pushToWorkStack(replacement);
                    bddWorkStackCount++;
                }
            }
        }

        cache.initCompose(bddVariableMapping, analysis.highestReplacedVariable);
        int result = composeGeneral(
                mtbddFunction,
                bddDomain,
                bddVariableMapping,
                analysis.highestReplacedVariable,
                cache.composeCache(),
                cache.composeSimplifyCache());
        bddTable.popFromWorkStack(bddWorkStackCount);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        return result;
    }

    @Override
    public RegisteredOperation.Unary registerCompose(int[] bddVariableMapping) {
        int[] resolved = bddVariableMapping.clone();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(resolved);
        if (analysis.highestReplacedVariable == -1) {
            return function -> function;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return function -> restrict(function, restrictSupport, restrictValues);
        }
        return new MtBddOperations.Compose(
                this, resolved, analysis.highestReplacedVariable, Util.protectNodes(bdd, resolved), false);
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] bddVariableMapping) {
        int[] resolved = bddVariableMapping.clone();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(resolved);
        if (analysis.highestReplacedVariable == -1) {
            return this::simplify;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return (function, domain) -> simplify(restrict(function, restrictSupport, restrictValues), domain);
        }
        return new MtBddOperations.Compose(
                this, resolved, analysis.highestReplacedVariable, Util.protectNodes(bdd, resolved), true);
    }

    /**
     * The general (non-identity, non-constant) compose/composeSimplify recursion, shared by the ordinary
     * path and {@link MtBddOperations}. Guards {@code mtbddFunction} and {@code bddDomain} for the call's
     * duration; the caller is responsible both for having already ruled out an empty {@code bddDomain} and
     * for protecting {@code bddVariableMapping}'s own (Bdd-side) nodes for as long as needed.
     */
    int composeGeneral(
            int mtbddFunction,
            int bddDomain,
            int[] bddVariableMapping,
            int highestReplacedVariable,
            MtBddCache.UnaryToIntCache composeCache,
            MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache) {
        assert bddDomain != bdd.falseFunction();
        assert bddDomain == bdd.trueFunction() || composeSimplifyCache != null;

        table.pushToWorkStack(mtbddFunction);
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = composeRecursive(
                mtbddFunction,
                bddVariableMapping,
                highestReplacedVariable,
                bddDomain,
                composeCache,
                composeSimplifyCache);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        return result;
    }

    // simplify is integrated directly due to most code paths being shared - see BddImpl#computeComposeSimplify
    private int composeRecursive(
            int mtbddNode,
            int[] bddVariableMapping,
            int highestReplacedVariable,
            int bddDomain,
            MtBddCache.UnaryToIntCache composeCache,
            MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache) {
        assert bddDomain != bdd.falseFunction();

        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        int variable = decisionVariable(mtbddNode);
        if (variable > highestReplacedVariable) {
            // Nothing left to replace below here, but the domain may still simplify what remains.
            return computeSimplify(mtbddNode, bddDomain);
        }

        int lookup;
        int hash;
        if (bddDomain == bdd.trueFunction()) {
            lookup = composeCache.lookup(mtbddNode);
            hash = composeCache.lookupHash();
        } else {
            lookup = composeSimplifyCache.lookup(mtbddNode, bddDomain);
            hash = composeSimplifyCache.lookupHash();
        }
        if (lookup != placeholder()) {
            return lookup;
        }

        int domainVariable = bddDomain == bdd.trueFunction() ? Integer.MAX_VALUE : bdd.decisionVariable(bddDomain);
        int domainLow = domainVariable <= variable ? bdd.lowOf(bddDomain) : bddDomain;
        int domainHigh = domainVariable <= variable ? bdd.highOf(bddDomain) : bddDomain;

        int result;
        if (domainVariable < variable) {
            if (domainLow == bdd.falseFunction()) {
                result = composeRecursive(
                        mtbddNode,
                        bddVariableMapping,
                        highestReplacedVariable,
                        domainHigh,
                        composeCache,
                        composeSimplifyCache);
            } else if (domainHigh == bdd.falseFunction()) {
                result = composeRecursive(
                        mtbddNode,
                        bddVariableMapping,
                        highestReplacedVariable,
                        domainLow,
                        composeCache,
                        composeSimplifyCache);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(domainLow, domainHigh));
                result = composeRecursive(
                        mtbddNode,
                        bddVariableMapping,
                        highestReplacedVariable,
                        widenedDomain,
                        composeCache,
                        composeSimplifyCache);
                bdd.table().popFromWorkStack();
            }
        } else {
            int bddReplacement = bddVariableMapping[variable];
            if (bddReplacement == bdd.trueFunction()) {
                result = composeRecursive(
                        table.high(mtbddNode),
                        bddVariableMapping,
                        highestReplacedVariable,
                        bddDomain,
                        composeCache,
                        composeSimplifyCache);
            } else if (bddReplacement == bdd.falseFunction()) {
                result = composeRecursive(
                        table.low(mtbddNode),
                        bddVariableMapping,
                        highestReplacedVariable,
                        bddDomain,
                        composeCache,
                        composeSimplifyCache);
            } else {
                // The domain constrains the *composed* function, so its cofactor w.r.t. this variable only
                // describes the branch we are descending into if the variable maps to itself.
                boolean aligned = domainVariable == variable && bddReplacement == bdd.variableFunction(variable);
                int lowDomain = aligned ? domainLow : bddDomain;
                int highDomain = aligned ? domainHigh : bddDomain;

                if (lowDomain == bdd.falseFunction()) {
                    result = composeRecursive(
                            table.high(mtbddNode),
                            bddVariableMapping,
                            highestReplacedVariable,
                            highDomain,
                            composeCache,
                            composeSimplifyCache);
                } else if (highDomain == bdd.falseFunction()) {
                    result = composeRecursive(
                            table.low(mtbddNode),
                            bddVariableMapping,
                            highestReplacedVariable,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache);
                } else {
                    int low = table.pushToWorkStack(composeRecursive(
                            table.low(mtbddNode),
                            bddVariableMapping,
                            highestReplacedVariable,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache));
                    int high = table.pushToWorkStack(composeRecursive(
                            table.high(mtbddNode),
                            bddVariableMapping,
                            highestReplacedVariable,
                            highDomain,
                            composeCache,
                            composeSimplifyCache));
                    result = ifThenElseRecursive(bddReplacement, high, low);
                    table.popFromWorkStack(2);
                }
            }
        }

        if (bddDomain == bdd.trueFunction()) {
            composeCache.put(hash, mtbddNode, result);
        } else {
            composeSimplifyCache.put(hash, mtbddNode, bddDomain, result);
        }
        return result;
    }

    @Override
    public int restrict(int mtbddFunction, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isValidFunction(mtbddFunction);

        if (restrictedVariables.isEmpty() || isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        int highestRestrictedVariable = restrictedVariables.length() - 1;
        cache.initRestrict(restrictedVariables, restrictedVariableValues);
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result = restrictRecursive(
                mtbddFunction, restrictedVariables, restrictedVariableValues, highestRestrictedVariable);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
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

        int lookup = cache.lookupRestrict(mtbddNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (restrictedVariables.get(variable)) {
            int child = restrictedVariableValues.get(variable) ? table.high(mtbddNode) : table.low(mtbddNode);
            result = restrictRecursive(child, restrictedVariables, restrictedVariableValues, highestRestrictedVariable);
        } else {
            int low = table.pushToWorkStack(restrictRecursive(
                    table.low(mtbddNode), restrictedVariables, restrictedVariableValues, highestRestrictedVariable));
            int high = table.pushToWorkStack(restrictRecursive(
                    table.high(mtbddNode), restrictedVariables, restrictedVariableValues, highestRestrictedVariable));
            result = makeFunction(variable, low, high);
            table.popFromWorkStack(2);
        }
        cache.putRestrict(hash, mtbddNode, result);
        return result;
    }

    @Override
    public int ifThenElse(int bddIfFunction, int mtbddThenFunction, int mtbddElseFunction) {
        assert isValidFunction(mtbddThenFunction) && isValidFunction(mtbddElseFunction);
        assert bdd.isValidFunction(bddIfFunction);

        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddThenFunction, mtbddElseFunction);
        int result = ifThenElseRecursive(bddIfFunction, mtbddThenFunction, mtbddElseFunction);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
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

        int lookup = cache.lookupIfThenElse(bddNode, mtbddThenNode, mtbddElseNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

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
        cache.putIfThenElse(hash, bddNode, mtbddThenNode, mtbddElseNode, result);
        return result;
    }

    @Override
    public MtBdd.Inverse invert(int mtbddFunction) {
        assert isValidFunction(mtbddFunction);
        assert bdd.table().workStacksEmpty();

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

        assert bdd.table().workStacksEmpty();
        return result;
    }

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
        assert table.workStacksEmpty();

        cache.initSplit();
        int highestSplitVariable = splitVariables.length() - 1;
        SplitBijection bijection = new SplitBijection(table);
        table.pushToWorkStack(mtbddFunction);
        int mtbddG = splitRecursive(mtbddFunction, splitVariables, highestSplitVariable, bijection);
        table.popFromWorkStack();
        table.popFromSecondaryWorkStack(bijection.size());
        assert table.workStacksEmpty();

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
            public BitSet codomain() {
                return indices;
            }
        };
    }

    @Override
    public int splitRelabeled(int mtbddFunction, BitSet splitVariables, IntUnaryOperator relabeler) {
        assert isValidFunction(mtbddFunction);
        assert table.workStacksEmpty();

        // Relabel in two passes for simplicity: For one pass, we would need to be careful not to call the
        // relabeler on intermediate nodes, which is tough to determine

        cache.initSplit();
        int highestSplitVariable = splitVariables.length() - 1;
        SplitBijection bijection = new SplitBijection(table);
        table.pushToWorkStack(mtbddFunction);
        int mtbddG = splitRecursive(mtbddFunction, splitVariables, highestSplitVariable, bijection);
        table.popFromWorkStack();

        table.pushToWorkStack(mtbddG);
        // Relabel every residual up front, once per distinct residual - which is what this method
        // promises. Doing it inside the map callback instead would call the relabeler once per *edge*
        // into a constant, since computeMap short-circuits constants before its cache lookup; a relabeler
        // with side effects (the interesting case - see BddMapFactory#split) would then see the same
        // sub-function twice.
        int residualCount = bijection.size();
        int[] relabeledResiduals = new int[residualCount];
        for (int index = 0; index < residualCount; index++) {
            relabeledResiduals[index] = relabeler.applyAsInt(bijection.getFunction(index));
        }

        IntUnaryOperator combined = value -> relabeledResiduals[value];
        cache.initMap(combined);
        int result = computeMap(mtbddG, bdd.trueFunction(), combined);
        table.popFromWorkStack();

        table.popFromSecondaryWorkStack(bijection.size());
        assert table.workStacksEmpty();
        return result;
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

        int lookup = cache.lookupSplit(mtbddNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int low = table.pushToWorkStack(
                splitRecursive(table.low(mtbddNode), splitVariables, highestSplitVariable, bijection));
        int high = table.pushToWorkStack(
                splitRecursive(table.high(mtbddNode), splitVariables, highestSplitVariable, bijection));

        int result = splitVariables.get(variable)
                ? makeFunction(variable, low, high)
                : splitCombineRecursive(low, high, variable, bijection);
        table.popFromWorkStack(2);
        cache.putSplit(hash, mtbddNode, result);
        return result;
    }

    private int splitCombineRecursive(int lowFragment, int highFragment, int variable, SplitBijection bijection) {
        if (lowFragment == highFragment) {
            return lowFragment;
        }

        int lookup = cache.lookupSplitCombine(lowFragment, highFragment, variable);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (isConstant(lowFragment) && isConstant(highFragment)) {
            int lowH = bijection.getFunction(constantFunctionToValue(lowFragment));
            int highH = bijection.getFunction(constantFunctionToValue(highFragment));
            int newH = makeFunction(variable, lowH, highH);
            result = of(bijection.intern(newH));
        } else {
            int lowVariable = isConstant(lowFragment) ? Integer.MAX_VALUE : decisionVariable(lowFragment);
            int highVariable = isConstant(highFragment) ? Integer.MAX_VALUE : decisionVariable(highFragment);
            int splitVariable = Math.min(lowVariable, highVariable);

            int lowLow = lowVariable == splitVariable ? table.low(lowFragment) : lowFragment;
            int lowHigh = lowVariable == splitVariable ? table.high(lowFragment) : lowFragment;
            int highLow = highVariable == splitVariable ? table.low(highFragment) : highFragment;
            int highHigh = highVariable == splitVariable ? table.high(highFragment) : highFragment;

            int low = table.pushToWorkStack(splitCombineRecursive(lowLow, highLow, variable, bijection));
            int high = table.pushToWorkStack(splitCombineRecursive(lowHigh, highHigh, variable, bijection));
            result = makeFunction(splitVariable, low, high);
            table.popFromWorkStack(2);
        }
        cache.putSplitCombine(hash, lowFragment, highFragment, variable, result);
        return result;
    }

    public int tableSize() {
        return table.size();
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
                table.pushToSecondaryWorkStack(f);
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
                public BitSet codomain() {
                    return new BitSet();
                }
            };
        }

        assert table.workStacksEmpty();
        cache.initCartesianProduct();
        for (int function : functions) {
            table.pushToWorkStack(function);
        }
        int[] values = new int[functions.length];
        IntTupleBijection bijection = new IntTupleBijection();
        int mtbddFunction = cartesianProductRecursive(
                Arrays.copyOf(functions, functions.length),
                values,
                0,
                new DepthPool<>(() -> new int[functions.length]),
                bijection);
        table.popFromWorkStack(functions.length);
        assert table.workStacksEmpty();

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
            public BitSet codomain() {
                return indices;
            }
        };
    }

    private int cartesianProductRecursive(
            int[] functions, int[] values, int depth, DepthPool<int[]> highPool, IntTupleBijection bijection) {
        int variable = minVariable(functions);
        if (variable == Integer.MAX_VALUE) {
            for (int i = 0; i < functions.length; i++) {
                values[i] = constantFunctionToValue(functions[i]);
            }
            return of(bijection.intern(values));
        }

        int lookup = cache.lookupCartesianProduct(functions);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        // These are modified in place so we need to copy for caching; we have to see whether caching actually helps
        // more than this hurts
        int[] key = Arrays.copyOf(functions, functions.length);

        int[] highFunctions = highPool.get(depth);
        splitByVariable(functions, highFunctions, variable);

        int low = table.pushToWorkStack(cartesianProductRecursive(functions, values, depth + 1, highPool, bijection));
        int high =
                table.pushToWorkStack(cartesianProductRecursive(highFunctions, values, depth + 1, highPool, bijection));
        int result = makeFunction(variable, low, high);
        table.popFromWorkStack(2);
        cache.putCartesianProduct(hash, key, result);
        return result;
    }

    private static final class IntTupleBijection {
        private final Map<IntArrayTuple, Integer> tupleToIndex = new HashMap<>();
        private final List<int[]> indexToTuple = new ArrayList<>();

        int intern(int[] values) {
            Integer existingIndex = tupleToIndex.get(new IntArrayTuple(values));
            if (existingIndex != null) {
                return existingIndex;
            }
            int[] tuple = Arrays.copyOf(values, values.length);
            int index = indexToTuple.size();
            indexToTuple.add(tuple);
            tupleToIndex.put(new IntArrayTuple(tuple), index);
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
        private @Nullable Object[] layers = new Object[8];

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

    @Override
    public int constrain(int mtbddFunction, int bddDomain) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddDomain);
        assert bddDomain != bdd.falseFunction() : "Constrain is undefined for an empty domain";

        if (isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        return constrainSimplify(mtbddFunction, bddDomain, true);
    }

    @Override
    public int simplify(int mtbddFunction, int bddDomain) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddDomain);

        if (isConstant(mtbddFunction)) {
            return mtbddFunction;
        }
        if (bddDomain == bdd.falseFunction()) {
            return of(anyLeafValue(mtbddFunction));
        }

        // Note this deliberately does not pre-reduce bddDomain via Bdd#simplificationDomain: the recursion
        // already performs that quantification lazily, and doing it eagerly only pays when amortized over
        // many calls against the same domain. A caller with a large, reused care set should hoist it.
        return constrainSimplify(mtbddFunction, bddDomain, false);
    }

    private int constrainSimplify(int mtbddFunction, int bddDomain, boolean constrain) {
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        // Simplify uses bdd.or to widen the domain; we need to protect it
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = constrainSimplifyRecursive(mtbddFunction, bddDomain, constrain);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        return result;
    }

    /**
     * {@link #simplify} without the entry-point bookkeeping, for the {@code xxxSimplify} recursions to
     * finish off a sub-result they have stopped descending into. Requires {@code mtbddNode} and
     * {@code bddDomain} to be protected by the caller, and {@code bddDomain} to be non-empty; a
     * {@code TRUE} domain returns {@code mtbddNode} unchanged.
     */
    private int computeSimplify(int mtbddNode, int bddDomain) {
        return constrainSimplifyRecursive(mtbddNode, bddDomain, false);
    }

    private int constrainSimplifyRecursive(int mtbddNode, int bddDomain, boolean constrain) {
        if (bddDomain == bdd.trueFunction() || isConstant(mtbddNode)) {
            return mtbddNode;
        }

        int lookup =
                constrain ? cache.lookupConstrain(mtbddNode, bddDomain) : cache.lookupSimplify(mtbddNode, bddDomain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int mtbddVariable = decisionVariable(mtbddNode);
        int bddVariable = bdd.decisionVariable(bddDomain);
        int variable = Math.min(mtbddVariable, bddVariable);

        int mtbddLow = mtbddVariable == variable ? table.low(mtbddNode) : mtbddNode;
        int mtbddHigh = mtbddVariable == variable ? table.high(mtbddNode) : mtbddNode;
        int bddLow = bddVariable == variable ? bdd.lowOf(bddDomain) : bddDomain;
        int bddHigh = bddVariable == variable ? bdd.highOf(bddDomain) : bddDomain;

        int result;
        if (bddLow == bdd.falseFunction()) {
            result = constrainSimplifyRecursive(mtbddHigh, bddHigh, constrain);
        } else if (bddHigh == bdd.falseFunction()) {
            result = constrainSimplifyRecursive(mtbddLow, bddLow, constrain);
        } else if (bddVariable < mtbddVariable) {
            if (constrain) {
                int low = table.pushToWorkStack(constrainSimplifyRecursive(mtbddNode, bddLow, true));
                int high = table.pushToWorkStack(constrainSimplifyRecursive(mtbddNode, bddHigh, true));
                result = makeFunction(variable, low, high);
                table.popFromWorkStack(2);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(bddLow, bddHigh));
                result = constrainSimplifyRecursive(mtbddNode, widenedDomain, false);
                bdd.table().popFromWorkStack();
            }
        } else {
            int low = table.pushToWorkStack(constrainSimplifyRecursive(mtbddLow, bddLow, constrain));
            int high = table.pushToWorkStack(constrainSimplifyRecursive(mtbddHigh, bddHigh, constrain));
            result = makeFunction(variable, low, high);
            table.popFromWorkStack(2);
        }

        if (constrain) {
            cache.putConstrain(hash, mtbddNode, bddDomain, result);
        } else {
            cache.putSimplify(hash, mtbddNode, bddDomain, result);
        }
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
        Map<String, Object> statistics = new HashMap<>(table.statistics("mtbdd_"));
        statistics.putAll(cache.statistics());
        statistics.put("mtbdd_allocated_values", allocatedValues.cardinality());
        return DecisionDiagram.prefixStatistics(bdd.configuration().name(), statistics);
    }

    /** A {@link ValuedIterator} over a fixed, unconstrained single element - the constant-function case. */
    private static final class SingletonValuedIterator<E> implements ValuedIterator<E> {
        private final E element;
        private final int value;
        private boolean hasNext = true;

        SingletonValuedIterator(E element, int value) {
            this.element = element;
            this.value = value;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public E next() {
            if (!hasNext) {
                throw new NoSuchElementException("No next element");
            }
            hasNext = false;
            return element;
        }

        @Override
        public int value() {
            return value;
        }
    }

    /**
     * A {@link ValuedIterator} over an existing iterator whose elements all yield the same value - used for
     * a constant function, where the value does not depend on the assignment at all.
     */
    private static final class ConstantValuedIterator<E> implements ValuedIterator<E> {
        private final Iterator<E> iterator;
        private final int value;

        ConstantValuedIterator(Iterator<E> iterator, int value) {
            this.iterator = iterator;
            this.value = value;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public E next() {
            return iterator.next();
        }

        @Override
        public int value() {
            return value;
        }
    }

    private static final class PathIterator implements ValuedIterator<BinaryPath> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final MtBddImpl mtbdd;
        private final int[] path;
        private final BitSet pathSupport;
        private final BitSet assignment;

        /**
         * Handed out by every {@link #next()}: both of its bit sets are the ones mutated in place above, so
         * the wrapper only has to be built once (its own state is entirely those two references).
         */
        private final BinaryPath currentPath;

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
            this.currentPath = new BinaryPath(assignment, pathSupport);

            rootVariable = mtbdd.decisionVariable(function);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootVariable] = function;
            pathSupport.set(rootVariable);

            leafNodeVariable = 0;
            hasNextPath = true;
        }

        @Override
        public int value() {
            assert !firstRun : "value() is only defined after the first next()";
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

            return currentPath;
        }
    }

    private static final class AssignmentIterator implements ValuedIterator<BitSet> {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final MtBddImpl mtbdd;
        private final @Nullable IntPredicate values;
        private final BitSet support;
        private final int[] path;
        private final BitSet assignment;
        private boolean firstRun = true;
        private int highestSwitchableVariable = 0;
        private int leafNodeVariable;
        private boolean hasNextPath;
        private boolean hasNextAssignment;
        private final int rootVariable;
        private int currentValue = -1;

        AssignmentIterator(MtBddImpl mtbdd, int function, @Nullable IntPredicate values, BitSet support) {
            assert !mtbdd.isConstant(function);

            this.mtbdd = mtbdd;
            this.values = values;
            this.support = support;
            this.path = new int[mtbdd.numberOfVariables()];
            this.assignment = new BitSet(mtbdd.numberOfVariables());

            if (!mtbdd.canReachMatch(function, values)) {
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

        @Override
        public int value() {
            assert currentValue >= 0 : "value() is only defined after the first next()";
            return currentValue;
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
                            assert values == null || values.test(mtbdd.evaluate(path[rootVariable], assignment));
                            return assignment;
                        }
                    }
                }

                // All don't-cares for the current path are exhausted - backtrack through the path until we
                // find a node whose high branch we haven't taken yet and which can still reach a match.
                assert hasNextPath : "Expected another path after " + assignment;

                currentNode = path[leafNodeVariable];
                int branchVar = leafNodeVariable;

                while (assignment.get(branchVar) || !mtbdd.canReachMatch(mtbdd.table.high(currentNode), values)) {
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
                assert mtbdd.canReachMatch(currentNode, values);
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
                if (!mtbdd.canReachMatch(low, values)) {
                    assignment.set(leafNodeVariable);
                    currentNode = mtbdd.table.high(currentNode);
                } else {
                    if (!hasNextPath && mtbdd.canReachMatch(mtbdd.table.high(currentNode), values)) {
                        hasNextPath = true;
                        highestSwitchableVariable = leafNodeVariable;
                    }
                    currentNode = low;
                }
            }
            assert values == null || values.test(constantFunctionToValue(currentNode));
            assert values == null || values.test(mtbdd.evaluate(path[rootVariable], assignment));
            // Only the path determines the value; the don't-care advancement above returns without
            // descending, so it deliberately leaves this untouched.
            currentValue = constantFunctionToValue(currentNode);

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
        int treeNodeFor(int pointer) {
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
        boolean ensureCapacity() {
            if (freeNodeCount() > size() / 4) {
                return false;
            }

            BddConfiguration configuration = mtbdd.bdd.configuration();
            int currentSize = size();
            int approximateDeadNodeCount = approximateDeadNodeCount();
            int invalidatedNodes;
            BitSet invalidatedLeaves;
            if (configuration.useGarbageCollection() && approximateDeadNodeCount > 0) {
                mtbdd.notifyBeforeGc();

                logger.log(Level.FINE, "Running GC on {0} has size {1} and approximately {2} dead nodes", new Object[] {
                    this, currentSize, approximateDeadNodeCount
                });

                @SuppressWarnings("NumericCastThatLosesPrecision")
                // If we only can free few nodes, it is not worth the effort
                int maximumReferencedNodes = (int) (currentSize * 0.7);

                // Leaves all referenced nodes marked
                int referencedNodes = markAllReferencedNodes();
                invalidatedLeaves = invalidateUnmarkedAndUnreferencedLeaves();
                if (referencedNodes <= maximumReferencedNodes) {
                    invalidatedNodes = reclaimUnmarkedNodes();
                    logger.log(Level.FINE, "Collected {0} nodes", invalidatedNodes);
                    mtbdd.notifyAfterGc(invalidatedNodes, invalidatedLeaves);
                    assert mtbdd.check();
                    return false;
                }

                logger.log(Level.FINER, "Not enough free nodes");
                invalidatedNodes = invalidateUnmarkedNodes();
            } else {
                invalidatedNodes = 0;
                invalidatedLeaves = BitSets.of();
            }
            //noinspection NumericCastThatLosesPrecision
            grow((int) (currentSize * configuration.growthFactor()));
            mtbdd.notifyAfterTableGrow(invalidatedNodes, invalidatedLeaves);
            assert mtbdd.check();
            return true;
        }

        BitSet invalidateUnmarkedAndUnreferencedLeaves() {
            assert BitSets.isSubset(markedValues, mtbdd.allocatedValues);
            BitSet freed = BitSets.copyOf(mtbdd.allocatedValues);
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
            freed.andNot(mtbdd.allocatedValues);
            return freed;
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
        String format(int pointer) {
            return mtbdd.format(pointer);
        }
    }

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
        public BitSet codomain() {
            return values;
        }
    }
}
