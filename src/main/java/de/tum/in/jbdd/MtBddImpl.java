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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/*
 * Important differences to BDDs:
 *  - In a generic MTBDD we have no commutativity and neutral elements, hence much more "base case" branching is required
 */
@SuppressWarnings({"PMD", "AssignmentToMethodParameter", "AssertWithSideEffects"})
public class MtBddImpl implements MtBdd {
    /** {@link #agreement}'s predicate: raw terminal equality, which within one numbering is value
     * equality. Fixed, so its cache never needs an init. */
    private static final MtBddBinaryPredicate EQUALITY = MtBddBinaryPredicate.equality();

    private static final int INVERT_ARRAY_DOMAIN_THRESHOLD = 64;
    private static final int INITIAL_VALUE_CAPACITY = 1024;

    /* The variable order and everything else shared with the companion BDD, reordering included. */
    private final BddContextImpl context;
    private final BddImpl bdd;
    private final MtBddTable table;
    private final MtBddCache cache;
    private byte[] valueReferenceCounts;
    private static final byte MAXIMUM_REFERENCE_COUNT = Byte.MAX_VALUE;
    // Convert to sparse bit set?
    private final BitSet allocatedValues = new BitSet();
    private final NodeTableObserverGroup<NodeTableObserver> observers = new NodeTableObserverGroup<>();
    private final ProtectionTracker protectionTracker;
    private final ConcurrentAccessGuard accessGuard = new ConcurrentAccessGuard();

    MtBddImpl(BddContextImpl context) {
        this.context = context;
        this.bdd = context.bdd();
        this.table = new MtBddTable(this, context.configuration().mtbddInitialSize());
        this.cache = new MtBddCache(this, bdd);
        this.valueReferenceCounts = new byte[INITIAL_VALUE_CAPACITY];
        this.protectionTracker = bdd.protectionTracker();
        observers.registerStrongly(protectionTracker);

        // Strongly: these two hooks are owned by this MTBDD, nothing else holds them - see
        // NodeLifecycleObserverGroup#registerStrongly.
        observers.registerStrongly(new NodeTableObserver() {
            @Override
            public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
                cache.onMultiTerminalNodesInvalidated(reclaimedNodes, reclaimedValues);
            }

            @Override
            public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
                cache.tableSizeChanged(invalidatedNodes, reclaimedValues);
            }

            @Override
            public void levelsSwapped(DecisionDiagram origin, int level) {
                cache.levelsSwapped();
            }

            /* Nothing on an insertion: it preserves every level comparison, so no cached value goes stale
             * by it, and the variable count is variablesChanged's business. */
        });
        bdd.registerOwnedObserver(new NodeTableObserver() {
            @Override
            public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
                cache.onBooleanNodesInvalidated(reclaimedNodes);
            }

            @Override
            public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
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

    void registerObserver(NodeTableObserver observer) {
        observers.register(observer);
    }

    void notifyBeforeGc() {
        observers.dispatch(observer -> observer.beforeGc(this));
    }

    void notifyLevelsSwapped(int level) {
        observers.dispatch(observer -> observer.levelsSwapped(this, level));
    }

    void notifyVariableInserted(int level) {
        observers.dispatch(observer -> observer.variableInserted(this, level));
    }

    void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
        observers.dispatch(observer -> observer.afterGc(this, reclaimedNodes, reclaimedValues));
    }

    void notifyAfterTableGrow(int reclaimedNodes, BitSet reclaimedValues) {
        observers.dispatch(observer -> observer.afterTableGrowth(this, reclaimedNodes, reclaimedValues));
    }

    @Override
    public int gc() {
        assert accessGuard.acquire();
        notifyBeforeGc();
        table.markAllReferencedNodes();
        BitSet reclaimedValues = table.invalidateUnmarkedAndUnreferencedLeaves();
        int reclaimedNodes = table.reclaimUnmarkedNodes();
        assert table().isNoneMarked();
        notifyAfterGc(reclaimedNodes, reclaimedValues);
        assert accessGuard.release();
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
        return context.numberOfVariables();
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
        assert accessGuard.acquire();
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
        assert accessGuard.release();
        return function;
    }

    @SuppressWarnings("NarrowingCompoundAssignment")
    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        assert accessGuard.acquire();
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
        assert accessGuard.release();
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
        assert accessGuard.acquire();
        table.forEachVariable(function, action);
        assert accessGuard.release();
    }

    @Override
    public void forEachSupportVariableFiltered(int function, BitSet filter, IntConsumer action) {
        assert accessGuard.acquire();
        table.forEachVariable(function, filter, action);
        assert accessGuard.release();
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
        assert accessGuard.acquire();
        int result = table.nodeCount() + allocatedValues.cardinality();
        assert accessGuard.release();
        return result;
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

    int decisionLevel(int function) {
        assert isValidNonConstantFunction(function);
        return context.level(table.variable(function));
    }

    @Override
    public int size(int function) {
        assert isValidFunction(function);
        assert accessGuard.acquire();
        int result = table.nodeCountBelow(function);
        assert accessGuard.release();
        return result;
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

    /* The variable order is the companion BDD's - it is the same order, so reordering is the same act.
     * Reordering through either moves the nodes of both; see BddContextImpl#siftDown. */

    boolean reordered() {
        return context.reordered();
    }

    @Override
    public int level(int variable) {
        return context.level(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return context.variableAtLevel(level);
    }

    @Override
    public int reorder() {
        return context.reorder();
    }

    @Override
    public int reorder(List<BitSet> groups) {
        return context.reorder(groups);
    }

    @Override
    public void reorderTo(List<BitSet> blocks) {
        context.reorderTo(blocks);
    }

    @Override
    public void reorderToIdentity() {
        context.reorderToIdentity();
    }

    @Override
    public int createVariableAtLevel(int level) {
        return context.createVariableAtLevel(level);
    }

    @Override
    public int[] createVariablesAtLevel(int level, int count) {
        return context.createVariablesAtLevel(level, count);
    }

    @Override
    public void dropReorderStructures() {
        context.dropReorderStructures();
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
        return high(function);
    }

    @Override
    public int lowOf(int function) {
        assert isValidNonConstantFunction(function);
        return low(function);
    }

    int high(int function) {
        return table.highUnchecked(function);
    }

    int low(int function) {
        return table.lowUnchecked(function);
    }

    int highIf(int function, boolean decides) {
        return decides ? table.highUnchecked(function) : function;
    }

    int lowIf(int function, boolean decides) {
        return decides ? table.lowUnchecked(function) : function;
    }

    @Override
    public int evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidDecisionNode(currentNode);
            currentNode = assignment[decisionVariable(currentNode)] ? high(currentNode) : low(currentNode);
        }
        return constantFunctionToValue(currentNode);
    }

    @Override
    public int evaluate(int function, BitSet assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidDecisionNode(currentNode);
            currentNode = assignment.get(decisionVariable(currentNode)) ? high(currentNode) : low(currentNode);
        }
        return constantFunctionToValue(currentNode);
    }

    @Override
    public int of(int value) {
        assert value >= 0;
        assert accessGuard.acquire();
        // TODO Heuristically trigger GC if too many values are allocated.
        allocatedValues.set(value);
        assert accessGuard.release();
        return valueToConstantFunction(value);
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        assert 0 <= variable && variable < numberOfVariables() : String.format("Variable %d does not exist", variable);
        assert isValidFunction(trueChild) && isValidFunction(falseChild);
        assert accessGuard.acquire();
        table.pushToWorkStack(trueChild, falseChild);
        int result = trueChild == falseChild ? falseChild : table.makeNode(variable, falseChild, trueChild);
        table.popFromWorkStack(2);
        assert accessGuard.release();
        return result;
    }

    private boolean decidesOn(int function, int level) {
        return !isConstant(function) && decisionLevel(function) == level;
    }

    /**
     * Rewrites the given nodes - which all carried the variable that was at {@code level} - for a swap of
     * {@code level} and {@code level + 1}, which the order already reflects. See
     * {@code BddContextImpl#siftDown}; the same recursion, without complement edges to keep canonical.
     */
    void rewriteLevelAfterSwap(int[] nodes, int count, int level, int variable) {
        /* Decide first, and take the nodes that will change out of the unique table before building
         * anything: until a node is rewritten it still carries the old variable, so makeNode below could
         * hand it out as a fresh child and it would then be rewritten out from under that parent. The
         * ones that do not change stay in the table on purpose - they are genuine nodes of the variable
         * moving down, and a new child that matches one of them must find it rather than duplicate it. */
        int rewriteCount = 0;
        for (int index = 0; index < count; index++) {
            int node = nodes[index];
            if (decidesOn(low(node), level) || decidesOn(high(node), level)) {
                nodes[rewriteCount] = node;
                rewriteCount += 1;
                table.hideForRewrite(node);
            } else {
                // Neither child mentions the variable moving up, so this node just descends a level.
                table.addToVariableList(node, table.variable(node));
            }
        }

        for (int index = 0; index < rewriteCount; index++) {
            int node = nodes[index];
            int lowFunction = low(node);
            int highFunction = high(node);
            boolean lowDecides = decidesOn(lowFunction, level);
            boolean highDecides = decidesOn(highFunction, level);

            int lowLow = lowDecides ? low(lowFunction) : lowFunction;
            int lowHigh = lowDecides ? high(lowFunction) : lowFunction;
            int highLow = highDecides ? low(highFunction) : highFunction;
            int highHigh = highDecides ? high(highFunction) : highFunction;

            int newLow = table.pushToWorkStack(makeFunction(level + 1, lowLow, highLow));
            int newHigh = makeFunction(level + 1, lowHigh, highHigh);
            table.popFromWorkStack();
            table.rewriteNode(node, variable, newLow, newHigh);
        }
    }

    int makeFunction(int level, int lowFunction, int highFunction) {
        if (lowFunction == highFunction) {
            return lowFunction;
        }
        return table.makeNode(context.variableAtLevel(level), lowFunction, highFunction);
    }

    @Override
    public boolean allValuesMatch(int function, IntPredicate predicate) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return predicate.test(constantFunctionToValue(function));
        }

        assert accessGuard.acquire();
        assert table.isNoneMarkedBelowNode(function);
        boolean result = allValuesMatchRecursive(function, predicate);
        table.doSetMarkBelow(function, false, false);
        assert table.isNoneMarkedBelowNode(function);
        assert accessGuard.release();
        return result;
    }

    private boolean allValuesMatchRecursive(int node, IntPredicate predicate) {
        if (isConstant(node)) {
            return predicate.test(constantFunctionToValue(node));
        }
        return !table.markNodeIfUnmarked(node)
                || (allValuesMatchRecursive(low(node), predicate) && allValuesMatchRecursive(high(node), predicate));
    }

    @Override
    public boolean anyValueMatches(int function, IntPredicate predicate) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return predicate.test(constantFunctionToValue(function));
        }

        assert accessGuard.acquire();
        assert table.isNoneMarkedBelowNode(function);
        boolean result = anyValueMatchesRecursive(function, predicate);
        table.doSetMarkBelow(function, false, false);
        assert table.isNoneMarkedBelowNode(function);
        assert accessGuard.release();
        return result;
    }

    private boolean anyValueMatchesRecursive(int node, IntPredicate predicate) {
        if (isConstant(node)) {
            return predicate.test(constantFunctionToValue(node));
        }
        return table.markNodeIfUnmarked(node)
                && (anyValueMatchesRecursive(low(node), predicate) || anyValueMatchesRecursive(high(node), predicate));
    }

    @Override
    public void forEachValue(int function, IntConsumer action) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            action.accept(constantFunctionToValue(function));
            return;
        }

        assert accessGuard.acquire();
        assert table.isNoneMarkedBelowNode(function);
        table.markAllBelowNode(function, true);
        BitSets.forEach(table.markedValues, action);
        table.unMarkAllBelowNode(function, true);
        assert table.isNoneMarkedBelowNode(function);
        assert accessGuard.release();
    }

    @Override
    public BitSet valuesOf(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            return BitSets.of(constantFunctionToValue(function));
        }

        // The mark phase computes exactly this set as a side effect, so copy it out directly instead of
        // going through forEachValue's IntConsumer round-trip.
        assert accessGuard.acquire();
        assert table.isNoneMarkedBelowNode(function);
        table.markAllBelowNode(function, true);
        BitSet values = BitSets.copyOf(table.markedValues);
        table.unMarkAllBelowNode(function, true);
        assert table.isNoneMarkedBelowNode(function);
        assert accessGuard.release();
        return values;
    }

    @Override
    public void forEachPath(int function, PathConsumer action) {
        assert accessGuard.acquire();
        for (ValuedCursor<BinaryPath> cursor = pathCursor(function); cursor.valid(); cursor.advance()) {
            action.accept(cursor.current(), cursor.value());
        }
        assert accessGuard.release();
    }

    @Override
    public ValuedCursor<BinaryPath> pathCursor(int function) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            // The single, entirely unconstrained path.
            BitSet empty = BitSets.of();
            return new SingletonValuedCursor<>(new BinaryPath(empty, empty), constantFunctionToValue(function));
        }
        return new PathCursor(this, function);
    }

    @Override
    public Optional<BitSet> anyAssignment(int function, IntPredicate values) {
        assert isValidFunction(function);

        assert accessGuard.acquire();
        cache.initAnyValueMatches(values);
        BitSet assigment = new BitSet(numberOfVariables());
        boolean found = anyAssigmentRecursive(function, values, assigment);
        assert accessGuard.release();
        return found ? Optional.of(assigment) : Optional.empty();
    }

    private boolean anyAssigmentRecursive(int function, @Nullable IntPredicate values, BitSet assignment) {
        if (isConstant(function)) {
            return values == null || values.test(constantFunctionToValue(function));
        }
        int low = low(function);
        if (canReachMatch(low, values) && anyAssigmentRecursive(low, values, assignment)) {
            return true;
        }
        int high = high(function);
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
        boolean result = canReachMatch(low(node), values) || canReachMatch(high(node), values);
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

        assert accessGuard.acquire();
        cache.initCount(values);
        int level = decisionLevel(function);
        BigInteger satisfyingBelow = countSatisfyingAssignmentsRecursive(function, values);
        assert accessGuard.release();
        return TWO.pow(level).multiply(satisfyingBelow);
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

        int nodeLevel = decisionLevel(node);
        BigInteger lowCount = doCountSatisfyingAssignments(low(node), nodeLevel, values);
        BigInteger highCount = doCountSatisfyingAssignments(high(node), nodeLevel, values);
        BigInteger result = lowCount.add(highCount);
        cache.putCount(hash, node, result);
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int function, int previousLevel, IntPredicate values) {
        if (isConstant(function)) {
            return values.test(constantFunctionToValue(function))
                    ? TWO.pow(numberOfVariables() - previousLevel - 1)
                    : ZERO;
        }
        BigInteger multiplier = TWO.pow(decisionLevel(function) - previousLevel - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(function, values));
    }

    @Override
    public ValuedCursor<BitSet> assignmentCursor(int function, @Nullable IntPredicate values) {
        assert isValidFunction(function);

        BitSet support = new BitSet(numberOfVariables());
        support.set(0, numberOfVariables());
        return assignmentCursor(function, values, support);
    }

    @Override
    public void forEachSolution(int function, @Nullable IntPredicate values, Consumer<? super BitSet> action) {
        assert isValidFunction(function);
        assert accessGuard.acquire();
        for (ValuedCursor<BitSet> cursor = assignmentCursor(function, values); cursor.valid(); cursor.advance()) {
            action.accept(cursor.current());
        }
        assert accessGuard.release();
    }

    @Override
    public ValuedCursor<BitSet> assignmentCursor(int function, @Nullable IntPredicate values, BitSet support) {
        assert isValidFunction(function);
        assert BitSets.isSubset(support(function), support);

        if (isConstant(function)) {
            int value = constantFunctionToValue(function);
            // Every assignment yields the same value, so the whole power set is (or is not) a solution.
            return (values == null || values.test(value))
                    ? new ConstantValuedCursor<>(Cursors.powerSet(support), value)
                    : new ConstantValuedCursor<>(Cursors.empty(), value);
        }
        cache.initAnyValueMatches(values);
        if (!canReachMatch(function, values)) {
            // Nothing matches anywhere, so there is nothing to walk - and no value to ever report.
            return emptyValued();
        }
        return new AssignmentCursor(this, function, values, support);
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

    int apply(
            int function1,
            int function2,
            int bddDomain,
            MtBddBinaryOperator operator,
            MtBddCache.@Nullable BinaryToIntCache registeredApplyCache,
            MtBddCache.@Nullable ApplySimplifyCache registeredApplySimplifyCache) {
        assert isValidFunction(function1) && isValidFunction(function2);
        assert bdd.isValidFunction(bddDomain);

        if (bddDomain == bdd.falseFunction()) {
            // Nothing is constrained, so any constant is a valid answer - pick one from the operands'
            // co-domains rather than recursing at all (see #simplify).
            return of(operator.applyAsInt(anyLeafValue(function1), anyLeafValue(function2)));
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

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
        assert accessGuard.release();
        return result;
    }

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

        // To simplify branching, use MAX_VALUE as a sentinel for "this side has no level left"
        int level1 = constant1 ? Integer.MAX_VALUE : decisionLevel(function1);
        int level2 = constant2 ? Integer.MAX_VALUE : decisionLevel(function2);
        int level = Math.min(level1, level2);
        assert level != Integer.MAX_VALUE : "Two constants are handled above";
        int domainLevel = universeDomain ? Integer.MAX_VALUE : bdd.decisionLevel(bddDomain);

        int result;
        if (domainLevel < level) {
            int domainLow = bdd.low(bddDomain);
            int domainHigh = bdd.high(bddDomain);
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
            int low1 = lowIf(function1, level1 == level);
            int high1 = highIf(function1, level1 == level);
            int low2 = lowIf(function2, level2 == level);
            int high2 = highIf(function2, level2 == level);

            boolean domainTestsVariable = domainLevel == level;
            int lowDomain = bdd.lowIf(bddDomain, domainTestsVariable);
            int highDomain = bdd.highIf(bddDomain, domainTestsVariable);

            if (lowDomain == bdd.falseFunction()) {
                // The domain forces this level, so only one branch is constrained - drop the node.
                result = computeApply(high1, high2, highDomain, operator, applyCache, applySimplifyCache);
            } else if (highDomain == bdd.falseFunction()) {
                result = computeApply(low1, low2, lowDomain, operator, applyCache, applySimplifyCache);
            } else {
                int low = table.pushToWorkStack(
                        computeApply(low1, low2, lowDomain, operator, applyCache, applySimplifyCache));
                int high = table.pushToWorkStack(
                        computeApply(high1, high2, highDomain, operator, applyCache, applySimplifyCache));
                result = makeFunction(level, low, high);
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

        if (bddDomain == bdd.falseFunction()) {
            return of(map.applyAsInt(anyLeafValue(function)));
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        cache.initMap(map);
        table.pushToWorkStack(function);
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = computeMap(function, bddDomain, map);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();
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

        int level = decisionLevel(function);
        int domainLevel = bddDomain == bdd.trueFunction() ? Integer.MAX_VALUE : bdd.decisionLevel(bddDomain);

        int result;
        if (domainLevel < level) {
            int domainLow = bdd.low(bddDomain);
            int domainHigh = bdd.high(bddDomain);
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
            int lowDomain = bdd.lowIf(bddDomain, domainLevel == level);
            int highDomain = bdd.highIf(bddDomain, domainLevel == level);

            if (lowDomain == bdd.falseFunction()) {
                result = computeMap(high(function), highDomain, map);
            } else if (highDomain == bdd.falseFunction()) {
                result = computeMap(low(function), lowDomain, map);
            } else {
                int low = table.pushToWorkStack(computeMap(low(function), lowDomain, map));
                int high = table.pushToWorkStack(computeMap(high(function), highDomain, map));
                result = makeFunction(level, low, high);
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

        assert accessGuard.acquire();
        if (operator instanceof MtBddNaryOperator.Unary) {
            int result = this.map(functions[0], (MtBddNaryOperator.Unary) operator);
            assert accessGuard.release();
            return result;
        }
        if (operator instanceof MtBddNaryOperator.Binary) {
            int result = this.apply(functions[0], functions[1], (MtBddNaryOperator.Binary) operator);
            assert accessGuard.release();
            return result;
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
            // Canonicalize for caching
            Arrays.sort(copy);
        }
        int[] values = new int[functions.length];
        int result = computeNaryApply(copy, values, operator, 0, new DepthPool<>(() -> new int[functions.length]));
        table.popFromWorkStack(functions.length);
        assert table.workStacksEmpty();
        assert accessGuard.release();
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

        int level = minLevel(functions);
        if (level == Integer.MAX_VALUE) {
            for (int i = 0; i < functions.length; i++) {
                values[i] = constantFunctionToValue(functions[i]);
            }
            return of(operator.applyAsInt(values));
        }

        int[] highFunctions = highPool.get(depth);
        splitByLevel(functions, highFunctions, level);

        int low = table.pushToWorkStack(computeNaryApply(functions, values, operator, depth + 1, highPool));
        int high = table.pushToWorkStack(computeNaryApply(highFunctions, values, operator, depth + 1, highPool));
        int result = makeFunction(level, low, high);
        table.popFromWorkStack(2);
        return result;
    }

    private int minLevel(int[] functions) {
        int level = Integer.MAX_VALUE;
        for (int function : functions) {
            if (!isConstant(function)) {
                int functionLevel = decisionLevel(function);
                if (functionLevel < level) {
                    level = functionLevel;
                }
            }
        }
        return level;
    }

    private void splitByLevel(int[] functions, int[] highFunctions, int level) {
        for (int i = 0; i < functions.length; i++) {
            int function = functions[i];
            if (!isConstant(function) && decisionLevel(function) == level) {
                functions[i] = low(function);
                highFunctions[i] = high(function);
            } else {
                highFunctions[i] = function;
            }
        }
    }

    @Override
    public int agreement(int mtbddFunction1, int mtbddFunction2) {
        /* Its own dedicated cache rather than the ephemeral one applyBoolean uses: the predicate is
         * always the same, so nothing can ever displace its entries and there is no init to pay. */
        return applyBoolean(mtbddFunction1, mtbddFunction2, EQUALITY, cache.agreementCache());
    }

    @Override
    public int applyBoolean(int mtbddFunction1, int mtbddFunction2, MtBddBinaryPredicate predicate) {
        cache.initApplyBoolean(predicate);
        return applyBoolean(mtbddFunction1, mtbddFunction2, predicate, cache.applyBooleanCache());
    }

    private int applyBoolean(
            int mtbddFunction1,
            int mtbddFunction2,
            MtBddBinaryPredicate predicate,
            MtBddCache.BinaryToBddCache booleanCache) {
        assert isValidFunction(mtbddFunction1) && isValidFunction(mtbddFunction2);
        assert accessGuard.acquire();
        assert bdd.table().workStacksEmpty();
        int result = applyBooleanRecursive(mtbddFunction1, mtbddFunction2, predicate, booleanCache);
        assert bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int applyBooleanRecursive(
            int mtbddNode1, int mtbddNode2, MtBddBinaryPredicate predicate, MtBddCache.BinaryToBddCache booleanCache) {
        /* Two identical sub-diagrams pair every terminal with itself, so a reflexive predicate holds
         * everywhere below without looking. Without the claim there is nothing to say - the pair may be
         * identical nodes standing for values from two different numberings. */
        if (predicate.reflexive && mtbddNode1 == mtbddNode2) {
            return bdd.trueFunction();
        }

        boolean constant1 = isConstant(mtbddNode1);
        boolean constant2 = isConstant(mtbddNode2);
        if (constant1 && constant2) {
            boolean holds = predicate.test(constantFunctionToValue(mtbddNode1), constantFunctionToValue(mtbddNode2));
            return holds ? bdd.trueFunction() : bdd.falseFunction();
        }

        // A pair and its mirror image have the same answer under a symmetric predicate, so order them
        // and let the two share one cache entry.
        if (predicate.symmetric && mtbddNode1 > mtbddNode2) {
            int nodeSwap = mtbddNode1;
            mtbddNode1 = mtbddNode2;
            mtbddNode2 = nodeSwap;
            constant1 = isConstant(mtbddNode1);
            constant2 = isConstant(mtbddNode2);
        }

        int lookup = cache.lookupBinaryToBdd(booleanCache, mtbddNode1, mtbddNode2);
        if (lookup != bdd.placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int level;
        int mtbddLow1;
        int mtbddHigh1;
        int mtbddLow2;
        int mtbddHigh2;
        if (constant1) {
            level = decisionLevel(mtbddNode2);
            mtbddLow1 = mtbddNode1;
            mtbddHigh1 = mtbddNode1;
            mtbddLow2 = low(mtbddNode2);
            mtbddHigh2 = high(mtbddNode2);
        } else if (constant2) {
            level = decisionLevel(mtbddNode1);
            mtbddLow1 = low(mtbddNode1);
            mtbddHigh1 = high(mtbddNode1);
            mtbddLow2 = mtbddNode2;
            mtbddHigh2 = mtbddNode2;
        } else {
            int level1 = decisionLevel(mtbddNode1);
            int level2 = decisionLevel(mtbddNode2);
            level = Math.min(level1, level2);
            mtbddLow1 = lowIf(mtbddNode1, level1 == level);
            mtbddHigh1 = highIf(mtbddNode1, level1 == level);
            mtbddLow2 = lowIf(mtbddNode2, level2 == level);
            mtbddHigh2 = highIf(mtbddNode2, level2 == level);
        }

        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(applyBooleanRecursive(mtbddLow1, mtbddLow2, predicate, booleanCache));
        int bddHigh = bddTable.pushToWorkStack(applyBooleanRecursive(mtbddHigh1, mtbddHigh2, predicate, booleanCache));
        int bddResult = bdd.makeFunction(level, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        cache.putBinaryToBdd(booleanCache, hash, mtbddNode1, mtbddNode2, bddResult);
        return bddResult;
    }

    @Override
    public int mapBoolean(int mtbddFunction, IntPredicate values) {
        assert isValidFunction(mtbddFunction);
        assert accessGuard.acquire();
        assert bdd.table().workStacksEmpty();
        cache.initMapBoolean(values);
        int result = mapBooleanRecursive(mtbddFunction, values);
        assert bdd.table().workStacksEmpty();
        assert accessGuard.release();
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

        int level = decisionLevel(mtbddNode);
        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(mapBooleanRecursive(low(mtbddNode), values));
        int bddHigh = bddTable.pushToWorkStack(mapBooleanRecursive(high(mtbddNode), values));
        int bddResult = bdd.makeFunction(level, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        cache.putMapBoolean(hash, mtbddNode, bddResult);
        return bddResult;
    }

    @Override
    public int update(int mtbddFunction, int bddAssignments, int value) {
        assert isValidFunction(mtbddFunction);
        assert bdd.isValidFunction(bddAssignments);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result = updateRecursive(mtbddFunction, bddAssignments, value);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
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

        int bddLevel = bdd.decisionLevel(bddNode);
        int mtbddLevel = isConstant(mtbddNode) ? Integer.MAX_VALUE : decisionLevel(mtbddNode);
        int level = Math.min(bddLevel, mtbddLevel);

        int mtbddLow = lowIf(mtbddNode, mtbddLevel == level);
        int mtbddHigh = highIf(mtbddNode, mtbddLevel == level);
        int bddLow = bdd.lowIf(bddNode, bddLevel == level);
        int bddHigh = bdd.highIf(bddNode, bddLevel == level);

        int low = table.pushToWorkStack(updateRecursive(mtbddLow, bddLow, value));
        int high = table.pushToWorkStack(updateRecursive(mtbddHigh, bddHigh, value));
        int result = makeFunction(level, low, high);
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

        assert accessGuard.acquire();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(bddVariableMapping);
        if (analysis.maxReplacedLevel == -1) {
            int result = simplify(mtbddFunction, bddDomain);
            assert accessGuard.release();
            return result;
        }

        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

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

        cache.initCompose(bddVariableMapping);
        int result = composeGeneral(
                mtbddFunction,
                bddDomain,
                bddVariableMapping,
                analysis.maxReplacedLevel,
                cache.composeCache(),
                cache.composeSimplifyCache());
        bddTable.popFromWorkStack(bddWorkStackCount);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public RegisteredOperation.Unary registerCompose(int[] bddVariableMapping) {
        int[] resolved = bddVariableMapping.clone();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            return function -> function;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return function -> restrict(function, restrictSupport, restrictValues);
        }
        return new MtBddOperations.Compose(
                this, resolved, analysis.maxReplacedLevel, Util.protectNodes(bdd, resolved), false);
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] bddVariableMapping) {
        int[] resolved = bddVariableMapping.clone();
        BddImpl.ComposeAnalysis analysis = bdd.analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            return this::simplify;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return (function, domain) -> simplify(restrict(function, restrictSupport, restrictValues), domain);
        }
        return new MtBddOperations.Compose(
                this, resolved, analysis.maxReplacedLevel, Util.protectNodes(bdd, resolved), true);
    }

    int composeGeneral(
            int mtbddFunction,
            int bddDomain,
            int[] bddVariableMapping,
            int maxReplacedLevel,
            MtBddCache.UnaryToIntCache composeCache,
            MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache) {
        assert bddDomain != bdd.falseFunction();
        assert bddDomain == bdd.trueFunction() || composeSimplifyCache != null;

        table.pushToWorkStack(mtbddFunction);
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = composeRecursive(
                mtbddFunction, bddVariableMapping, maxReplacedLevel, bddDomain, composeCache, composeSimplifyCache);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        return result;
    }

    // simplify is integrated directly due to most code paths being shared - see BddImpl#computeComposeSimplify
    private int composeRecursive(
            int mtbddNode,
            int[] bddVariableMapping,
            int maxReplacedLevel,
            int bddDomain,
            MtBddCache.UnaryToIntCache composeCache,
            MtBddCache.@Nullable MtbddBddToIntCache composeSimplifyCache) {
        assert bddDomain != bdd.falseFunction();

        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        /* Two different things, and compose needs both: the level orders the descent against the domain,
         * while bddVariableMapping is indexed by the variable itself. */
        int nodeVariable = decisionVariable(mtbddNode);
        int level = level(nodeVariable);
        if (level > maxReplacedLevel) {
            // Nothing left to replace below here, but the domain may still simplify what remains.
            return computeSimplify(mtbddNode, bddDomain);
        }

        boolean domainIsTrue = bddDomain == bdd.trueFunction();
        assert composeSimplifyCache != null || domainIsTrue;

        int lookup = domainIsTrue ? composeCache.lookup(mtbddNode) : composeSimplifyCache.lookup(mtbddNode, bddDomain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = domainIsTrue ? composeCache.lookupHash : composeSimplifyCache.lookupHash;

        int domainLevel = domainIsTrue ? Integer.MAX_VALUE : bdd.decisionLevel(bddDomain);
        int domainLow = bdd.lowIf(bddDomain, domainLevel <= level);
        int domainHigh = bdd.highIf(bddDomain, domainLevel <= level);

        int result;
        if (domainLevel < level) {
            if (domainLow == bdd.falseFunction()) {
                result = composeRecursive(
                        mtbddNode,
                        bddVariableMapping,
                        maxReplacedLevel,
                        domainHigh,
                        composeCache,
                        composeSimplifyCache);
            } else if (domainHigh == bdd.falseFunction()) {
                result = composeRecursive(
                        mtbddNode, bddVariableMapping, maxReplacedLevel, domainLow, composeCache, composeSimplifyCache);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(domainLow, domainHigh));
                result = composeRecursive(
                        mtbddNode,
                        bddVariableMapping,
                        maxReplacedLevel,
                        widenedDomain,
                        composeCache,
                        composeSimplifyCache);
                bdd.table().popFromWorkStack();
            }
        } else {
            /* A mapping shorter than the variable count leaves the rest unchanged - and under a
             * non-identity order such a variable can well sit above maxReplacedLevel's variable, so
             * the recursion reaches it. */
            int bddReplacement = nodeVariable < bddVariableMapping.length
                    ? bddVariableMapping[nodeVariable]
                    : bdd.variableFunction(nodeVariable);
            if (bddReplacement == bdd.trueFunction()) {
                result = composeRecursive(
                        high(mtbddNode),
                        bddVariableMapping,
                        maxReplacedLevel,
                        bddDomain,
                        composeCache,
                        composeSimplifyCache);
            } else if (bddReplacement == bdd.falseFunction()) {
                result = composeRecursive(
                        low(mtbddNode),
                        bddVariableMapping,
                        maxReplacedLevel,
                        bddDomain,
                        composeCache,
                        composeSimplifyCache);
            } else {
                // The domain constrains the *composed* function, so its cofactor w.r.t. this level only
                // describes the branch we are descending into if the level maps to itself.
                boolean aligned = domainLevel == level && bddReplacement == bdd.variableFunction(nodeVariable);
                int lowDomain = aligned ? domainLow : bddDomain;
                int highDomain = aligned ? domainHigh : bddDomain;

                if (lowDomain == bdd.falseFunction()) {
                    result = composeRecursive(
                            high(mtbddNode),
                            bddVariableMapping,
                            maxReplacedLevel,
                            highDomain,
                            composeCache,
                            composeSimplifyCache);
                } else if (highDomain == bdd.falseFunction()) {
                    result = composeRecursive(
                            low(mtbddNode),
                            bddVariableMapping,
                            maxReplacedLevel,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache);
                } else {
                    int low = table.pushToWorkStack(composeRecursive(
                            low(mtbddNode),
                            bddVariableMapping,
                            maxReplacedLevel,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache));
                    int high = table.pushToWorkStack(composeRecursive(
                            high(mtbddNode),
                            bddVariableMapping,
                            maxReplacedLevel,
                            highDomain,
                            composeCache,
                            composeSimplifyCache));
                    result = ifThenElseRecursive(bddReplacement, high, low);
                    table.popFromWorkStack(2);
                }
            }
        }

        if (domainIsTrue) {
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

        assert accessGuard.acquire();
        int maxRestrictedLevel = bdd.maxLevel(restrictedVariables);
        cache.initRestrict(restrictedVariables, restrictedVariableValues);
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        int result =
                restrictRecursive(mtbddFunction, restrictedVariables, restrictedVariableValues, maxRestrictedLevel);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int restrictRecursive(
            int mtbddNode, BitSet restrictedVariables, BitSet restrictedVariableValues, int maxRestrictedLevel) {
        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        /* The level orders the descent and is what makeFunction wants; the two BitSets are indexed by
         * the variable itself. */
        int nodeVariable = decisionVariable(mtbddNode);
        int level = level(nodeVariable);
        if (level > maxRestrictedLevel) {
            return mtbddNode;
        }

        int lookup = cache.lookupRestrict(mtbddNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (restrictedVariables.get(nodeVariable)) {
            int child = restrictedVariableValues.get(nodeVariable) ? high(mtbddNode) : low(mtbddNode);
            result = restrictRecursive(child, restrictedVariables, restrictedVariableValues, maxRestrictedLevel);
        } else {
            int low = table.pushToWorkStack(restrictRecursive(
                    low(mtbddNode), restrictedVariables, restrictedVariableValues, maxRestrictedLevel));
            int high = table.pushToWorkStack(restrictRecursive(
                    high(mtbddNode), restrictedVariables, restrictedVariableValues, maxRestrictedLevel));
            result = makeFunction(level, low, high);
            table.popFromWorkStack(2);
        }
        cache.putRestrict(hash, mtbddNode, result);
        return result;
    }

    @Override
    public int ifThenElse(int bddIfFunction, int mtbddThenFunction, int mtbddElseFunction) {
        assert isValidFunction(mtbddThenFunction) && isValidFunction(mtbddElseFunction);
        assert bdd.isValidFunction(bddIfFunction);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddThenFunction, mtbddElseFunction);
        int result = ifThenElseRecursive(bddIfFunction, mtbddThenFunction, mtbddElseFunction);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
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

        int bddLevel = bdd.decisionLevel(bddNode);
        int thenLevel = isConstant(mtbddThenNode) ? Integer.MAX_VALUE : decisionLevel(mtbddThenNode);
        int elseLevel = isConstant(mtbddElseNode) ? Integer.MAX_VALUE : decisionLevel(mtbddElseNode);
        int level = Math.min(bddLevel, Math.min(thenLevel, elseLevel));

        int bddLow = bdd.lowIf(bddNode, bddLevel == level);
        int bddHigh = bdd.highIf(bddNode, bddLevel == level);
        int mtbddThenLow = lowIf(mtbddThenNode, thenLevel == level);
        int mtbddThenHigh = highIf(mtbddThenNode, thenLevel == level);
        int mtbddElseLow = lowIf(mtbddElseNode, elseLevel == level);
        int mtbddElseHigh = highIf(mtbddElseNode, elseLevel == level);

        int low = table.pushToWorkStack(ifThenElseRecursive(bddLow, mtbddThenLow, mtbddElseLow));
        int high = table.pushToWorkStack(ifThenElseRecursive(bddHigh, mtbddThenHigh, mtbddElseHigh));
        int result = makeFunction(level, low, high);
        table.popFromWorkStack(2);
        cache.putIfThenElse(hash, bddNode, mtbddThenNode, mtbddElseNode, result);
        return result;
    }

    @Override
    public MultiTerminalDecisionDiagram.Inverse invert(int mtbddFunction) {
        assert isValidFunction(mtbddFunction);
        assert accessGuard.acquire();
        assert bdd.table().workStacksEmpty();

        int domainSize = allocatedValues.length();
        MultiTerminalDecisionDiagram.Inverse result;
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
        assert accessGuard.release();
        return result;
    }

    private Map<Integer, Integer> invertRecursive(
            int mtbddNode, int depth, DepthPool<Map<Integer, Integer>> highLeafPool) {
        if (isConstant(mtbddNode)) {
            Map<Integer, Integer> result = new HashMap<>();
            result.put(constantFunctionToValue(mtbddNode), bdd.table().pushToWorkStack(bdd.trueFunction()));
            return result;
        }

        int level = decisionLevel(mtbddNode);
        Map<Integer, Integer> mtbddLowMap = invertRecursive(low(mtbddNode), depth + 1, highLeafPool);

        int highChild = high(mtbddNode);
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
            int bddResult = bdd.table().pushToWorkStack(bdd.makeFunction(level, bddLow, bddHigh));
            entry.setValue(bddResult);
        }
        mtbddHighMap.forEach((value, bddHigh) -> mtbddLowMap.computeIfAbsent(
                value, k -> bdd.table().pushToWorkStack(bdd.makeFunction(level, bdd.falseFunction(), bddHigh))));

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

        int level = decisionLevel(mtbddNode);
        int[] lowArray = invertRecursiveArray(low(mtbddNode), domainSize, values, depth + 1, highLeafPool);

        int highChild = high(mtbddNode);
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
            int bddResult = bddTable.pushToWorkStack(bdd.makeFunction(level, bddLow, bddHigh));
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
        assert accessGuard.acquire();
        assert table.workStacksEmpty();

        cache.initSplit();
        int maxSplitLevel = bdd.maxLevel(splitVariables);
        SplitBijection bijection = new SplitBijection(table);
        table.pushToWorkStack(mtbddFunction);
        int mtbddG = splitRecursive(mtbddFunction, splitVariables, maxSplitLevel, bijection);
        table.popFromWorkStack();
        table.popFromSecondaryWorkStack(bijection.size());
        assert table.workStacksEmpty();
        assert accessGuard.release();

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
        assert accessGuard.acquire();
        assert table.workStacksEmpty();

        // Relabel in two passes for simplicity: For one pass, we would need to be careful not to call the
        // relabeler on intermediate nodes, which is tough to determine

        cache.initSplit();
        int maxSplitLevel = bdd.maxLevel(splitVariables);
        SplitBijection bijection = new SplitBijection(table);
        table.pushToWorkStack(mtbddFunction);
        int mtbddG = splitRecursive(mtbddFunction, splitVariables, maxSplitLevel, bijection);
        table.popFromWorkStack();

        table.pushToWorkStack(mtbddG);
        // Relabel every residual up front, once per distinct residual - which is what this method
        // promises. Doing it inside the map callback instead would call the relabeler once per *edge*
        // into a constant, since computeMap short-circuits constants before its cache lookup; a relabeler
        // with side effects (the interesting case - see BddMap#split) would then see the same
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
        assert accessGuard.release();
        return result;
    }

    private int splitRecursive(int mtbddNode, BitSet splitVariables, int maxSplitLevel, SplitBijection bijection) {
        if (isConstant(mtbddNode)) {
            return of(bijection.intern(mtbddNode));
        }

        /* The level orders the descent and is what makeFunction wants; splitVariables is indexed by the
         * variable itself. */
        int nodeVariable = decisionVariable(mtbddNode);
        int level = level(nodeVariable);
        if (level > maxSplitLevel) {
            return of(bijection.intern(mtbddNode));
        }

        int lookup = cache.lookupSplit(mtbddNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int low = table.pushToWorkStack(splitRecursive(low(mtbddNode), splitVariables, maxSplitLevel, bijection));
        int high = table.pushToWorkStack(splitRecursive(high(mtbddNode), splitVariables, maxSplitLevel, bijection));

        int result = splitVariables.get(nodeVariable)
                ? makeFunction(level, low, high)
                : splitCombineRecursive(low, high, level, bijection);
        table.popFromWorkStack(2);
        cache.putSplit(hash, mtbddNode, result);
        return result;
    }

    private int splitCombineRecursive(int lowFragment, int highFragment, int level, SplitBijection bijection) {
        if (lowFragment == highFragment) {
            return lowFragment;
        }

        int lookup = cache.lookupSplitCombine(lowFragment, highFragment, level);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (isConstant(lowFragment) && isConstant(highFragment)) {
            int lowH = bijection.getFunction(constantFunctionToValue(lowFragment));
            int highH = bijection.getFunction(constantFunctionToValue(highFragment));
            int newH = makeFunction(level, lowH, highH);
            result = of(bijection.intern(newH));
        } else {
            int lowLevel = isConstant(lowFragment) ? Integer.MAX_VALUE : decisionLevel(lowFragment);
            int highLevel = isConstant(highFragment) ? Integer.MAX_VALUE : decisionLevel(highFragment);
            int splitLevel = Math.min(lowLevel, highLevel);

            int lowLow = lowIf(lowFragment, lowLevel == splitLevel);
            int lowHigh = highIf(lowFragment, lowLevel == splitLevel);
            int highLow = lowIf(highFragment, highLevel == splitLevel);
            int highHigh = highIf(highFragment, highLevel == splitLevel);

            int low = table.pushToWorkStack(splitCombineRecursive(lowLow, highLow, level, bijection));
            int high = table.pushToWorkStack(splitCombineRecursive(lowHigh, highHigh, level, bijection));
            result = makeFunction(splitLevel, low, high);
            table.popFromWorkStack(2);
        }
        cache.putSplitCombine(hash, lowFragment, highFragment, level, result);
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

        assert accessGuard.acquire();
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
        assert accessGuard.release();

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
        int level = minLevel(functions);
        if (level == Integer.MAX_VALUE) {
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
        splitByLevel(functions, highFunctions, level);

        int low = table.pushToWorkStack(cartesianProductRecursive(functions, values, depth + 1, highPool, bijection));
        int high =
                table.pushToWorkStack(cartesianProductRecursive(highFunctions, values, depth + 1, highPool, bijection));
        int result = makeFunction(level, low, high);
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

        // Deliberately does not pre-reduce bddDomain via Bdd#simplificationDomain: the recursion
        // already performs that quantification lazily, and doing it eagerly only pays when amortized over
        // many calls against the same domain. A caller with a large, reused care set should hoist it.
        return constrainSimplify(mtbddFunction, bddDomain, false);
    }

    private int constrainSimplify(int mtbddFunction, int bddDomain, boolean constrain) {
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(mtbddFunction);
        // Simplify uses bdd.or to widen the domain; we need to protect it
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = constrainSimplifyRecursive(mtbddFunction, bddDomain, constrain);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

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

        int mtbddLevel = decisionLevel(mtbddNode);
        int bddLevel = bdd.decisionLevel(bddDomain);
        int level = Math.min(mtbddLevel, bddLevel);

        int mtbddLow = lowIf(mtbddNode, mtbddLevel == level);
        int mtbddHigh = highIf(mtbddNode, mtbddLevel == level);
        boolean bddDecides = bddLevel == level;
        int bddLow = bdd.lowIf(bddDomain, bddDecides);
        int bddHigh = bdd.highIf(bddDomain, bddDecides);

        int result;
        if (bddDecides && bddLow == bdd.falseFunction()) {
            result = constrainSimplifyRecursive(mtbddHigh, bddHigh, constrain);
        } else if (bddDecides && bddHigh == bdd.falseFunction()) {
            result = constrainSimplifyRecursive(mtbddLow, bddLow, constrain);
        } else if (bddLevel < mtbddLevel) {
            if (constrain) {
                int low = table.pushToWorkStack(constrainSimplifyRecursive(mtbddNode, bddLow, true));
                int high = table.pushToWorkStack(constrainSimplifyRecursive(mtbddNode, bddHigh, true));
                result = makeFunction(level, low, high);
                table.popFromWorkStack(2);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(bddLow, bddHigh));
                result = constrainSimplifyRecursive(mtbddNode, widenedDomain, false);
                bdd.table().popFromWorkStack();
            }
        } else {
            int low = table.pushToWorkStack(constrainSimplifyRecursive(mtbddLow, bddLow, constrain));
            int high = table.pushToWorkStack(constrainSimplifyRecursive(mtbddHigh, bddHigh, constrain));
            result = makeFunction(level, low, high);
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
            node = low(node);
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
        assert accessGuard.acquire();
        Map<String, Object> statistics = new HashMap<>(table.statistics("mtbdd_"));
        statistics.putAll(cache.statistics());
        statistics.put("mtbdd_allocated_values", allocatedValues.cardinality());
        assert accessGuard.release();
        return DecisionDiagram.prefixStatistics(bdd.configuration().name(), statistics);
    }

    /**
     * The traversal both iterators run on: it walks the paths of a function, one at a time, in the order
     * the diagram is laid out in, and reports the terminal each one reaches.
     *
     * <p>Not a {@link Cursor} itself: it hands nothing out, it only moves. {@link #advance()} steps it on,
     * and the state it exposes describes where it now is. The cursors below differ only in what they make
     * of that state, which is why the descent and the backtracking live here and nowhere else. It is a
     * final class held in fields of its own type, so nothing here is dispatched virtually.
     *
     * <p>Unlike a {@code Bdd}, there is no {@code FALSE} to prune against: every decision node's high
     * branch is a genuine alternative. A {@code values} predicate takes that role when the caller only
     * wants paths reaching certain terminals - {@code null} keeps every path.
     */
    private static final class PathWalk {
        private static final int NON_PATH_NODE = NodeTable.PLACEHOLDER;

        private final MtBddImpl mtbdd;
        private final @Nullable IntPredicate values;
        private final int[] path;
        private final BitSet levelAssignment;
        private final BitSet pathSupportLevels;
        private final int rootLevel;
        private boolean onPath;
        private int leafNodeLevel;
        private int pathValue = -1;

        PathWalk(MtBddImpl mtbdd, int function, @Nullable IntPredicate values) {
            assert mtbdd.isValidNonConstantFunction(function);
            assert values == null || mtbdd.canReachMatch(function, values);

            int variableCount = mtbdd.numberOfVariables();
            this.mtbdd = mtbdd;
            this.values = values;
            this.path = new int[variableCount];
            this.levelAssignment = new BitSet(variableCount);
            this.pathSupportLevels = new BitSet(variableCount);
            this.rootLevel = mtbdd.decisionLevel(function);

            Arrays.fill(path, NON_PATH_NODE);
            path[rootLevel] = function;
            pathSupportLevels.set(rootLevel);
            this.leafNodeLevel = 0;
            /* Positioned on the first path right away, so there is no "have we started yet" state to
             * carry: whoever holds the cursor asks onPath(), and advance() only ever means "the next
             * one". The constructor already checked the root can reach a match, so this lands. */
            descend(path[rootLevel]);
            this.onPath = true;
        }

        /** The levels the current path fixes. */
        BitSet pathSupportLevels() {
            return pathSupportLevels;
        }

        /**
         * The values the current path fixes those levels to. Also the working set an assignment iterator
         * adds the levels the path leaves free to - they are disjoint from the path's own by construction,
         * and a path switch only ever clears a range it has already counted back down to zero.
         */
        BitSet levelAssignment() {
            return levelAssignment;
        }

        /** The terminal the current path reaches. Only the path decides it. */
        int pathValue() {
            return pathValue;
        }

        int rootFunction() {
            return path[rootLevel];
        }

        /** Whether the cursor is on a path: false once the enumeration is over. */
        boolean onPath() {
            return onPath;
        }

        /** Moves to the next path. Returns {@code false} when there are none left. */
        boolean advance() {
            assert IntStream.range(0, path.length)
                    .allMatch(i -> pathSupportLevels.get(i) == (path[i] != NON_PATH_NODE));

            // Backtrack to a node whose high branch we have not taken yet and which can still reach a match.
            int currentNode = path[leafNodeLevel];
            int branchLevel = leafNodeLevel;

            while (levelAssignment.get(branchLevel) || !canReach(mtbdd.table.high(currentNode))) {
                branchLevel = pathSupportLevels.previousSetBit(branchLevel - 1);
                if (branchLevel == -1) {
                    onPath = false;
                    return false;
                }
                currentNode = path[branchLevel];
            }
            assert mtbdd.decisionLevel(currentNode) == branchLevel;
            assert path[branchLevel] == currentNode;

            // Switch it to high and descend anew, retracting everything the old path had below it.
            levelAssignment.clear(branchLevel + 1, leafNodeLevel + 1);
            Arrays.fill(path, branchLevel + 1, leafNodeLevel + 1, NON_PATH_NODE);
            pathSupportLevels.clear(branchLevel + 1, leafNodeLevel + 1);
            levelAssignment.set(branchLevel);
            leafNodeLevel = branchLevel;

            int high = mtbdd.table.high(currentNode);
            assert canReach(high);
            descend(high);
            return true;
        }

        /** Descends preferring the low branch wherever it can still reach a match. */
        private void descend(int startNode) {
            int currentNode = startNode;
            while (!mtbdd.isConstant(currentNode)) {
                leafNodeLevel = mtbdd.decisionLevel(currentNode);
                path[leafNodeLevel] = currentNode;
                pathSupportLevels.set(leafNodeLevel);

                int low = mtbdd.table.low(currentNode);
                if (canReach(low)) {
                    currentNode = low;
                } else {
                    levelAssignment.set(leafNodeLevel);
                    currentNode = mtbdd.table.high(currentNode);
                }
            }
            pathValue = constantFunctionToValue(currentNode);
            assert values == null || values.test(pathValue);
        }

        private boolean canReach(int node) {
            return values == null || mtbdd.canReachMatch(node, values);
        }
    }

    /** A walk with nothing in it, for a function no assignment satisfies. */
    private static <E> ValuedCursor<E> emptyValued() {
        return new ValuedCursor<E>() {
            @Override
            public boolean valid() {
                return false;
            }

            @Override
            public E current() {
                throw new IllegalStateException("Cursor is not valid");
            }

            @Override
            public int value() {
                throw new IllegalStateException("Cursor is not valid");
            }

            @Override
            public boolean advance() {
                return false;
            }
        };
    }

    /** A caller's support, as the levels the walk works in. */
    private static BitSet levelsOf(MtBddImpl mtbdd, BitSet variables) {
        BitSet levels = new BitSet(mtbdd.numberOfVariables());
        BitSets.map(variables, levels, mtbdd::level);
        return levels;
    }

    private static final class SingletonValuedCursor<E> implements ValuedCursor<E> {
        private final E element;
        private final int value;
        private boolean valid = true;

        SingletonValuedCursor(E element, int value) {
            this.element = element;
            this.value = value;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public E current() {
            assert valid : "current() is only defined while the cursor is valid";
            return element;
        }

        @Override
        public int value() {
            assert valid : "value() is only defined while the cursor is valid";
            return value;
        }

        @Override
        public boolean advance() {
            valid = false;
            return false;
        }
    }

    /** A walk whose every element leads to the same terminal - what a constant function enumerates. */
    private static final class ConstantValuedCursor<E> implements ValuedCursor<E> {
        private final Cursor<E> cursor;
        private final int value;

        ConstantValuedCursor(Cursor<E> cursor, int value) {
            this.cursor = cursor;
            this.value = value;
        }

        @Override
        public boolean valid() {
            return cursor.valid();
        }

        @Override
        public E current() {
            return cursor.current();
        }

        @Override
        public int value() {
            assert cursor.valid() : "value() is only defined while the cursor is valid";
            return value;
        }

        @Override
        public boolean advance() {
            return cursor.advance();
        }
    }

    /**
     * Walks the paths of a function together with the terminal each one reaches.
     *
     * <p>Hands out its own working path, so nothing is copied per element - see {@link Cursor}. The one
     * exception is a diagram that has been reordered, where the walk is by level and the caller wants
     * variables, and a translation buffer is unavoidable.
     */
    private static final class PathCursor implements ValuedCursor<BinaryPath> {
        private final MtBddImpl mtbdd;
        private final PathWalk path;
        /** Only on a reordered diagram, where the walk is by level and the caller wants variables. */
        private final @Nullable BinaryPath translated;
        /** What {@link #current()} hands out: the translation buffer, or the walk's own sets wrapped. */
        private final BinaryPath current;

        private boolean valid;

        PathCursor(MtBddImpl mtbdd, int function) {
            int variableCount = mtbdd.numberOfVariables();
            this.mtbdd = mtbdd;
            this.path = new PathWalk(mtbdd, function, null);
            this.valid = path.onPath();
            this.translated =
                    mtbdd.reordered() ? new BinaryPath(new BitSet(variableCount), new BitSet(variableCount)) : null;
            this.current =
                    translated == null ? new BinaryPath(path.levelAssignment(), path.pathSupportLevels()) : translated;
            if (valid) {
                translate();
            }
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public BinaryPath current() {
            assert valid : "current() is only defined while the cursor is valid";
            return current;
        }

        @Override
        public int value() {
            assert valid : "value() is only defined while the cursor is valid";
            return path.pathValue();
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }
            valid = path.advance();
            if (valid) {
                translate();
            }
            return valid;
        }

        /* The traversal is by level; what the caller gets is indexed by variable. Identical until the
         * shared variable order is changed - see BddImpl's cursors, which do the same. */
        private void translate() {
            if (translated != null) {
                BitSets.map(path.levelAssignment(), translated.assignment, mtbdd::variableAtLevel);
                BitSets.map(path.pathSupportLevels(), translated.support, mtbdd::variableAtLevel);
            }
        }
    }

    /**
     * Walks the assignments whose terminal matches, together with that terminal: every path the predicate
     * admits, and for each of them every way of filling in the support variables it leaves free. Only the
     * path decides the terminal, so {@link #value()} stays put across those.
     */
    private static final class AssignmentCursor implements ValuedCursor<BitSet> {
        private final MtBddImpl mtbdd;
        private final PathWalk path;
        private final BitSet supportLevels;
        /** The support levels the current path leaves free; see BddImpl's solution cursor. */
        private final BitSet freeLevels;

        private final @Nullable BitSet translated;
        private boolean valid;

        AssignmentCursor(MtBddImpl mtbdd, int function, @Nullable IntPredicate values, BitSet supportLevels) {
            assert !mtbdd.isConstant(function);
            assert mtbdd.canReachMatch(function, values) : "The empty walk is the factory's business";
            assert supportLevels.get(mtbdd.decisionVariable(function));

            int variableCount = mtbdd.numberOfVariables();
            this.mtbdd = mtbdd;
            boolean translating = mtbdd.reordered();
            this.supportLevels = translating ? levelsOf(mtbdd, supportLevels) : supportLevels;
            this.freeLevels = new BitSet(variableCount);
            this.translated = translating ? new BitSet(variableCount) : null;
            this.path = new PathWalk(mtbdd, function, values);
            this.valid = path.onPath();
            if (valid) {
                refreshFreeLevels();
                translate();
            }
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public BitSet current() {
            assert valid : "current() is only defined while the cursor is valid";
            return translated == null ? path.levelAssignment() : translated;
        }

        @Override
        public int value() {
            assert valid : "value() is only defined while the cursor is valid";
            return path.pathValue();
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }

            /* Binary addition over the support levels the current path leaves free: every combination of
             * them extends this path to an assignment, all reaching the same terminal. Carrying past the
             * last one leaves them at zero and moves the path on. */
            if (BitSets.increment(path.levelAssignment(), freeLevels)) {
                translate();
                return true;
            }
            if (!path.advance()) {
                valid = false;
                return false;
            }
            refreshFreeLevels();
            translate();
            return true;
        }

        private void refreshFreeLevels() {
            BitSets.difference(freeLevels, supportLevels, path.pathSupportLevels());
        }

        /** By level internally, by variable on the way out - see BddImpl's cursors. */
        private void translate() {
            if (translated != null) {
                BitSets.map(path.levelAssignment(), translated, mtbdd::variableAtLevel);
            }
            assert mtbdd.evaluate(path.rootFunction(), current()) == path.pathValue();
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
        protected int level(int variable) {
            // The MTBDD shares its companion BDD's variable order; that is what lets a cross-table
            // recursion expand on a single topmost variable.
            return mtbdd.bdd().level(variable);
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
        protected boolean recurseIsAllMarkedBelow(int node, boolean includeLeaves) {
            int low = low(node);
            int high = high(node);
            return ((!includeLeaves && mtbdd.isConstant(low))
                            || isMarkedConstant(low)
                            || doIsAllMarkedBelow(low, includeLeaves))
                    && ((!includeLeaves && mtbdd.isConstant(high))
                            || isMarkedConstant(high)
                            || doIsAllMarkedBelow(high, includeLeaves));
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
        protected BddConfiguration configuration() {
            return mtbdd.bdd.configuration();
        }

        @Override
        protected void notifyBeforeGc() {
            mtbdd.notifyBeforeGc();
        }

        @Override
        protected void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
            mtbdd.notifyAfterGc(reclaimedNodes, reclaimedValues);
        }

        @Override
        protected void notifyAfterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
            mtbdd.notifyAfterTableGrow(invalidatedNodes, reclaimedValues);
        }

        @Override
        protected BitSet sweepManagedLeaves() {
            return invalidateUnmarkedAndUnreferencedLeaves();
        }

        @Override
        protected boolean checkOwner() {
            return mtbdd.check();
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
        protected void unmarkAllManagedLeaves() {
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
