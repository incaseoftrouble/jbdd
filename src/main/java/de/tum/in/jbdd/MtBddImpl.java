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
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/*
 * Important differences to BDDs:
 *  - In a generic MTBDD we have no commutativity and neutral elements, hence much more "base case" branching is required
 */
@SuppressWarnings({"PMD", "AssignmentToMethodParameter", "AssertWithSideEffects"})
public class MtBddImpl implements MtBdd, StatisticsSource {
    /** {@link #agreement}'s predicate: raw terminal equality, which within one numbering is value
     * equality. Fixed, so its cache never needs an init. */
    private static final MtBddBinaryPredicate EQUALITY = MtBddBinaryPredicate.equality();

    private static final int INVERT_ARRAY_DOMAIN_THRESHOLD = 64;
    private static final int INITIAL_VALUE_CAPACITY = 1024;
    /* How many values may be allocated between collections before one is forced, and how far that grows
     * when forcing it turns out to free nothing - see shouldCollectForValues. */
    private static final int INITIAL_VALUE_COLLECTION_THRESHOLD = 1 << 12;
    private static final int MAXIMUM_VALUE_COLLECTION_THRESHOLD = 1 << 24;

    /* The variable order and everything else shared with the companion BDD, reordering included. */
    private final DdContextImpl context;
    /* Direct, not through the context: decisionLevel and makeFunction go through it on every node. */
    private final DdVariableOrderImpl order;
    private final BddImpl bdd;
    private final MtBddTable table;
    private final MtBddCache cache;
    private byte[] valueReferenceCounts;
    private static final byte MAXIMUM_REFERENCE_COUNT = Byte.MAX_VALUE;
    // Convert to sparse bit set?
    private final BitSet allocatedValues = new BitSet();
    private int valuesAllocatedSinceCollection = 0;
    private int valueCollectionThreshold = INITIAL_VALUE_COLLECTION_THRESHOLD;
    private long createdNodesAtCollection = 0L;
    private boolean collectingForValues = false;
    /** How often values alone, with no node allocation to trigger one, forced a collection. */
    private int valueTriggeredCollectionCount = 0;

    private final ObserverGroup<NodeTableObserver> observers = new ObserverGroup<>();
    private final ProtectionTracker protectionTracker;
    private final ConcurrentAccessGuard accessGuard = new ConcurrentAccessGuard();

    MtBddImpl(DdContextImpl context) {
        this.context = context;
        this.order = context.variableOrder();
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
        });
        // As the BDD's.
        order.registerOwnedObserver(cache);
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

    @Override
    public String treeToString(int function) {
        return table.treeToString(function);
    }

    @Override
    public void invalidateCache() {
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

    void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
        valuesCollected(reclaimedValues);
        observers.dispatch(observer -> observer.afterGc(this, reclaimedNodes, reclaimedValues));
    }

    void notifyAfterTableGrow(int reclaimedNodes, BitSet reclaimedValues) {
        valuesCollected(reclaimedValues);
        observers.dispatch(observer -> observer.afterTableGrowth(this, reclaimedNodes, reclaimedValues));
    }

    private void valuesCollected(BitSet reclaimedValues) {
        if (collectingForValues) {
            /* Forcing one and freeing nothing means the values are genuinely live. Trying again at the
             * same count would make a workload holding many of them pay a full mark every threshold
             * allocations, so back off; a collection that did free some puts it back. */
            valueCollectionThreshold = reclaimedValues.isEmpty()
                    ? Math.min(valueCollectionThreshold * 2, MAXIMUM_VALUE_COLLECTION_THRESHOLD)
                    : INITIAL_VALUE_COLLECTION_THRESHOLD;
        }
        valuesAllocatedSinceCollection = 0;
        createdNodesAtCollection = table.createdNodeCount();
    }

    @Override
    public int gc() {
        assert accessGuard.acquire();
        notifyBeforeGc();
        table.markAllReferencedNodes();
        // Before reclaimUnmarkedNodes, whose closing assertion checks the leaf marks too.
        BitSet reclaimedValues = table.clearUnreferencedLeaves();
        int reclaimedNodes = table.reclaimUnmarkedNodes();
        assert !Assertions.COSTLY_ASSERTIONS || table().isNoneMarked();
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
        return order.numberOfVariables();
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
        assert value < Integer.MAX_VALUE / 2 : String.format("Value %d is too large to keep counts for", value);
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
        return order.levelOfVariable(table.variable(function));
    }

    int decisionLevelOrMax(int function) {
        return isConstant(function) ? Integer.MAX_VALUE : decisionLevel(function);
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

    boolean isReordered() {
        return order.isExplicitOrder();
    }

    DdContextImpl context() {
        return context;
    }

    @Override
    public DdVariableOrderImpl variableOrder() {
        return order;
    }

    @Override
    public int levelOfVariable(int variable) {
        return order.levelOfVariable(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return order.variableAtLevel(level);
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
        int function = valueToConstantFunction(value);
        if (!allocatedValues.get(value)) {
            allocatedValues.set(value);
            valuesAllocatedSinceCollection += 1;
            if (shouldCollectForValues()) {
                collectForValues(function);
            }
        }
        assert accessGuard.release();
        return function;
    }

    /**
     * Values are the one thing that can pile up without the node table noticing: allocating a node is what
     * eventually reaches {@code ensureCapacity}, and a workload producing many values while building few
     * nodes never gets there. Hence the comparison against node creation rather than an absolute count -
     * where nodes are being made, the table's own trigger is already doing this job, and this one stays
     * out of the way. Two loads and a compare, on a path that runs once per genuinely new value.
     */
    private boolean shouldCollectForValues() {
        return valuesAllocatedSinceCollection >= valueCollectionThreshold
                && table.createdNodeCount() - createdNodesAtCollection < valuesAllocatedSinceCollection
                && context.configuration().useGarbageCollection();
    }

    private void collectForValues(int function) {
        // The value being handed out is not referenced yet and sits under no node, so the mark would not
        // reach it - the work stack is what carries a bare terminal through a collection.
        table.pushToWorkStack(function);
        collectingForValues = true;
        valueTriggeredCollectionCount += 1;
        gc();
        collectingForValues = false;
        table.popFromWorkStack();
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        assert 0 <= variable && variable < numberOfVariables() : String.format("Variable %d does not exist", variable);
        assert isValidFunction(trueChild) && isValidFunction(falseChild);
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(trueChild, falseChild);
        int result = trueChild == falseChild ? falseChild : table.makeNode(variable, falseChild, trueChild);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private boolean decidesOn(int function, int level) {
        return !isConstant(function) && decisionLevel(function) == level;
    }

    void rewriteLevelAfterSwap(int[] nodes, int count, int level, int variable) {
        // See BddImpl's implementation for details, it's the same recursion
        int rewriteCount = 0;
        for (int index = 0; index < count; index++) {
            int node = nodes[index];
            if (decidesOn(low(node), level) || decidesOn(high(node), level)) {
                nodes[rewriteCount] = node;
                rewriteCount += 1;
                table.rewriteHideAndUnlink(node);
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
        return table.makeNode(order.variableAtLevel(level), lowFunction, highFunction);
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
    public void forEachPath(int function, PathValueConsumer action) {
        assert accessGuard.acquire();
        for (ValuedCursor<Cube> cursor = pathCursor(function); cursor.valid(); cursor.advance()) {
            action.accept(cursor.current(), cursor.value());
        }
        assert accessGuard.release();
    }

    @Override
    public ValuedCursor<Cube> pathCursor(int function) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            // The single, entirely unconstrained path.
            BitSet empty = BitSets.of();
            return new ValuedCursor.SingletonValuedCursor<>(new Cube(empty, empty), constantFunctionToValue(function));
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
                    ? new ValuedCursor.ConstantValuedCursor<>(Cursors.powerSet(support), value)
                    : new ValuedCursor.ConstantValuedCursor<>(Cursors.empty(), value);
        }
        cache.initAnyValueMatches(values);
        if (!canReachMatch(function, values)) {
            // Nothing matches anywhere, so there is nothing to walk - and no value to ever report.
            return ValuedCursor.emptyValued();
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
        assert bddDomain != bdd.falseFunction() : "Constrain is undefined for an empty domain";
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
        }

        int lookup = universeDomain
                ? applyCache.lookup(function1, function2)
                : applySimplifyCache.lookup(function1, function2, bddDomain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = universeDomain ? applyCache.lookupHash() : applySimplifyCache.lookupHash();

        int level1 = decisionLevelOrMax(function1);
        int level2 = decisionLevelOrMax(function2);
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
        return map(function, bdd.trueFunction(), map, null, null);
    }

    @Override
    public int mapSimplify(int function, IntUnaryOperator map, int bddDomain) {
        return map(function, bddDomain, map, null, null);
    }

    @Override
    public RegisteredOperation.Unary registerMap(IntUnaryOperator map) {
        return new MtBddOperations.Mapper(this, map, false);
    }

    @Override
    public RegisteredOperation.Binary registerMapSimplify(IntUnaryOperator map) {
        return new MtBddOperations.Mapper(this, map, true);
    }

    int map(
            int function,
            int bddDomain,
            IntUnaryOperator map,
            MtBddCache.@Nullable UnaryToIntCache registeredMapCache,
            MtBddCache.@Nullable MtbddBddToIntCache registeredMapSimplifyCache) {
        assert isValidFunction(function);
        assert bdd.isValidFunction(bddDomain);

        if (bddDomain == bdd.falseFunction()) {
            return of(map.applyAsInt(anyLeafValue(function)));
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        MtBddCache.UnaryToIntCache mapCache = registeredMapCache;
        MtBddCache.MtbddBddToIntCache mapSimplifyCache = registeredMapSimplifyCache;
        if (mapCache == null) {
            cache.initMap(map);
            mapCache = cache.mapCache();
            mapSimplifyCache = cache.mapSimplifyCache();
        }
        assert bddDomain == bdd.trueFunction() || mapSimplifyCache != null
                : "A domain-carrying map must be registered through registerMapSimplify";

        table.pushToWorkStack(function);
        NodeTable bddTable = bdd.table();
        bddTable.pushToWorkStack(bddDomain);
        int result = computeMap(function, bddDomain, map, mapCache, mapSimplifyCache);
        bddTable.popFromWorkStack();
        table.popFromWorkStack();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    /** The unary counterpart of {@link #computeApply}; see there for the domain handling. */
    private int computeMap(
            int function,
            int bddDomain,
            IntUnaryOperator map,
            MtBddCache.UnaryToIntCache mapCache,
            MtBddCache.@Nullable MtbddBddToIntCache mapSimplifyCache) {
        assert bddDomain != bdd.falseFunction();
        boolean universeDomain = bddDomain == bdd.trueFunction();
        assert universeDomain || mapSimplifyCache != null;

        if (isConstant(function)) {
            return of(map.applyAsInt(constantFunctionToValue(function)));
        }

        int lookup = universeDomain ? mapCache.lookup(function) : mapSimplifyCache.lookup(function, bddDomain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = universeDomain ? mapCache.lookupHash() : mapSimplifyCache.lookupHash();

        int level = decisionLevel(function);
        int domainLevel = universeDomain ? Integer.MAX_VALUE : bdd.decisionLevel(bddDomain);

        int result;
        if (domainLevel < level) {
            int domainLow = bdd.low(bddDomain);
            int domainHigh = bdd.high(bddDomain);
            if (domainLow == bdd.falseFunction()) {
                result = computeMap(function, domainHigh, map, mapCache, mapSimplifyCache);
            } else if (domainHigh == bdd.falseFunction()) {
                result = computeMap(function, domainLow, map, mapCache, mapSimplifyCache);
            } else {
                int widenedDomain = bdd.table().pushToWorkStack(bdd.computeOr(domainLow, domainHigh));
                result = computeMap(function, widenedDomain, map, mapCache, mapSimplifyCache);
                bdd.table().popFromWorkStack();
            }
        } else {
            int lowDomain = bdd.lowIf(bddDomain, domainLevel == level);
            int highDomain = bdd.highIf(bddDomain, domainLevel == level);

            if (lowDomain == bdd.falseFunction()) {
                result = computeMap(high(function), highDomain, map, mapCache, mapSimplifyCache);
            } else if (highDomain == bdd.falseFunction()) {
                result = computeMap(low(function), lowDomain, map, mapCache, mapSimplifyCache);
            } else {
                int low = table.pushToWorkStack(computeMap(low(function), lowDomain, map, mapCache, mapSimplifyCache));
                int high =
                        table.pushToWorkStack(computeMap(high(function), highDomain, map, mapCache, mapSimplifyCache));
                result = makeFunction(level, low, high);
                table.popFromWorkStack(2);
            }
        }

        if (universeDomain) {
            mapCache.put(hash, function, result);
        } else {
            mapSimplifyCache.put(hash, function, bddDomain, result);
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
        cache.initNaryApply(operator);
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

        // Without a memo the recursion walks every combination of paths, which sharing makes exponential.
        int lookup = cache.lookupNaryApply(functions);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();
        // The operand array is rewritten in place below, so the cache needs one of its own.
        // TODO Copying is costly; can we do it better? Write in a DepthPool?
        //   The array only needs to survive until the cache put
        int[] key = Arrays.copyOf(functions, functions.length);

        int[] highFunctions = highPool.get(depth);
        splitByLevel(functions, highFunctions, level);

        int low = table.pushToWorkStack(computeNaryApply(functions, values, operator, depth + 1, highPool));
        int high = table.pushToWorkStack(computeNaryApply(highFunctions, values, operator, depth + 1, highPool));
        int result = makeFunction(level, low, high);
        table.popFromWorkStack(2);
        cache.putNaryApply(hash, key, result);
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
        return applyBoolean(mtbddFunction1, mtbddFunction2, EQUALITY, cache.agreementCache());
    }

    @Override
    public int applyBoolean(int mtbddFunction1, int mtbddFunction2, MtBddBinaryPredicate predicate) {
        assert isValidFunction(mtbddFunction1) && isValidFunction(mtbddFunction2);
        cache.initApplyBoolean(predicate);
        return applyBoolean(mtbddFunction1, mtbddFunction2, predicate, cache.applyBooleanCache());
    }

    @Override
    public boolean allMatch(int mtbddFunction1, int mtbddFunction2, MtBddBinaryPredicate predicate) {
        assert isValidFunction(mtbddFunction1) && isValidFunction(mtbddFunction2);
        assert accessGuard.acquire();
        cache.initAllMatch(predicate);
        boolean result = allMatchRecursive(mtbddFunction1, mtbddFunction2, predicate, cache.allMatchCache());
        assert accessGuard.release();
        return result;
    }

    // applyBooleanRecursive's shape, but nothing is built, so nothing needs protecting either.
    private boolean allMatchRecursive(
            int mtbddNode1,
            int mtbddNode2,
            MtBddBinaryPredicate predicate,
            MtBddCache.BinaryToBooleanCache matchCache) {
        if (predicate.reflexive && mtbddNode1 == mtbddNode2) {
            return true;
        }

        if (isConstant(mtbddNode1) && isConstant(mtbddNode2)) {
            return predicate.test(constantFunctionToValue(mtbddNode1), constantFunctionToValue(mtbddNode2));
        }

        if (predicate.symmetric && mtbddNode1 > mtbddNode2) {
            int nodeSwap = mtbddNode1;
            mtbddNode1 = mtbddNode2;
            mtbddNode2 = nodeSwap;
        }

        int lookup = matchCache.lookup(mtbddNode1, mtbddNode2);
        if (lookup != MtBddCache.BinaryToBooleanCache.MISS) {
            return lookup == 1;
        }
        int hash = matchCache.lookupHash();

        int level1 = decisionLevelOrMax(mtbddNode1);
        int level2 = decisionLevelOrMax(mtbddNode2);
        int level = Math.min(level1, level2);
        int mtbddLow1 = lowIf(mtbddNode1, level1 == level);
        int mtbddHigh1 = highIf(mtbddNode1, level1 == level);
        int mtbddLow2 = lowIf(mtbddNode2, level2 == level);
        int mtbddHigh2 = highIf(mtbddNode2, level2 == level);

        boolean result = allMatchRecursive(mtbddLow1, mtbddLow2, predicate, matchCache)
                && allMatchRecursive(mtbddHigh1, mtbddHigh2, predicate, matchCache);
        matchCache.put(hash, mtbddNode1, mtbddNode2, result);
        return result;
    }

    @Override
    public RegisteredOperation.Binary registerApplyBoolean(MtBddBinaryPredicate predicate) {
        return new MtBddOperations.ApplyBoolean(this, predicate);
    }

    int applyBoolean(
            int mtbddFunction1,
            int mtbddFunction2,
            MtBddBinaryPredicate predicate,
            MtBddCache.BinaryToBddCache booleanCache) {
        assert isValidFunction(mtbddFunction1) && isValidFunction(mtbddFunction2);
        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        int result = applyBooleanRecursive(mtbddFunction1, mtbddFunction2, predicate, booleanCache);
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int applyBooleanRecursive(
            int mtbddNode1, int mtbddNode2, MtBddBinaryPredicate predicate, MtBddCache.BinaryToBddCache booleanCache) {
        if (predicate.reflexive && mtbddNode1 == mtbddNode2) {
            return bdd.trueFunction();
        }

        if (isConstant(mtbddNode1) && isConstant(mtbddNode2)) {
            boolean holds = predicate.test(constantFunctionToValue(mtbddNode1), constantFunctionToValue(mtbddNode2));
            return holds ? bdd.trueFunction() : bdd.falseFunction();
        }

        // A pair and its mirror image have the same answer under a symmetric predicate, so order them
        // and let the two share one cache entry.
        if (predicate.symmetric && mtbddNode1 > mtbddNode2) {
            int nodeSwap = mtbddNode1;
            mtbddNode1 = mtbddNode2;
            mtbddNode2 = nodeSwap;
        }

        int lookup = cache.lookupBinaryToBdd(booleanCache, mtbddNode1, mtbddNode2);
        if (lookup != bdd.placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int level1 = decisionLevelOrMax(mtbddNode1);
        int level2 = decisionLevelOrMax(mtbddNode2);
        int level = Math.min(level1, level2);
        int mtbddLow1 = lowIf(mtbddNode1, level1 == level);
        int mtbddHigh1 = highIf(mtbddNode1, level1 == level);
        int mtbddLow2 = lowIf(mtbddNode2, level2 == level);
        int mtbddHigh2 = highIf(mtbddNode2, level2 == level);

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
        cache.initMapBoolean(values);
        return mapBoolean(mtbddFunction, values, cache.mapBooleanCache());
    }

    @Override
    public RegisteredOperation.Unary registerMapBoolean(IntPredicate values) {
        return new MtBddOperations.MapBoolean(this, values);
    }

    int mapBoolean(int mtbddFunction, IntPredicate values, MtBddCache.UnaryToBddCache mapBooleanCache) {
        assert isValidFunction(mtbddFunction);
        assert accessGuard.acquire();
        assert bdd.table().workStacksEmpty();
        int result = mapBooleanRecursive(mtbddFunction, values, mapBooleanCache);
        assert bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int mapBooleanRecursive(int mtbddNode, IntPredicate values, MtBddCache.UnaryToBddCache mapBooleanCache) {
        if (isConstant(mtbddNode)) {
            return values.test(constantFunctionToValue(mtbddNode)) ? bdd.trueFunction() : bdd.falseFunction();
        }

        int lookup = mapBooleanCache.lookup(mtbddNode);
        if (lookup != bdd.placeholder()) {
            return lookup;
        }
        int hash = mapBooleanCache.lookupHash();

        int level = decisionLevel(mtbddNode);
        NodeTable bddTable = bdd.table();
        int bddLow = bddTable.pushToWorkStack(mapBooleanRecursive(low(mtbddNode), values, mapBooleanCache));
        int bddHigh = bddTable.pushToWorkStack(mapBooleanRecursive(high(mtbddNode), values, mapBooleanCache));
        int bddResult = bdd.makeFunction(level, bddLow, bddHigh);
        bddTable.popFromWorkStack(2);
        mapBooleanCache.put(hash, mtbddNode, bddResult);
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
        int mtbddLevel = decisionLevelOrMax(mtbddNode);
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
            return RegisteredOperation.identity();
        }
        if (analysis.isRestrict) {
            Cube restriction = analysis.restriction;
            return function -> restrict(function, restriction);
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
            Cube restriction = analysis.restriction;
            return (function, domain) -> simplify(restrict(function, restriction), domain);
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
        int level = levelOfVariable(nodeVariable);
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
        int hash = domainIsTrue ? composeCache.lookupHash() : composeSimplifyCache.lookupHash();

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
    public int restrict(int mtbddFunction, Cube restriction) {
        assert isValidFunction(mtbddFunction);

        if (restriction.isEmpty() || isConstant(mtbddFunction)) {
            return mtbddFunction;
        }

        int current = mtbddFunction;
        while (!isConstant(current) && restriction.support.get(decisionVariable(current))) {
            current = restriction.assignment.get(decisionVariable(current)) ? high(current) : low(current);
        }
        if (isConstant(current)) {
            return current;
        }
        int maxRestrictedLevel = bdd.maxLevel(restriction.support);
        if (decisionLevel(current) > maxRestrictedLevel) {
            return current;
        }
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        // TODO Unclear if this (and the BDD parallel) really is beneficial for caching as the decent depth
        //  probably depends on the structure of the current argument
        Cube remaining = order.literalsBelow(restriction, decisionLevel(current));
        cache.initRestrict(remaining);
        table.pushToWorkStack(current);
        int result = restrictRecursive(current, remaining, maxRestrictedLevel);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int restrictRecursive(int mtbddNode, Cube restriction, int maxRestrictedLevel) {
        if (isConstant(mtbddNode)) {
            return mtbddNode;
        }
        // The level orders the descent and is what makeFunction wants; the cube is indexed by the variable.
        int nodeVariable = decisionVariable(mtbddNode);
        int level = levelOfVariable(nodeVariable);
        if (level > maxRestrictedLevel) {
            return mtbddNode;
        }

        int lookup = cache.lookupRestrict(mtbddNode);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (restriction.support.get(nodeVariable)) {
            int child = restriction.assignment.get(nodeVariable) ? high(mtbddNode) : low(mtbddNode);
            result = restrictRecursive(child, restriction, maxRestrictedLevel);
        } else {
            int low = table.pushToWorkStack(restrictRecursive(low(mtbddNode), restriction, maxRestrictedLevel));
            int high = table.pushToWorkStack(restrictRecursive(high(mtbddNode), restriction, maxRestrictedLevel));
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
        int thenLevel = decisionLevelOrMax(mtbddThenNode);
        int elseLevel = decisionLevelOrMax(mtbddElseNode);
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
        int result = computeMap(mtbddG, bdd.trueFunction(), combined, cache.mapCache(), cache.mapSimplifyCache());
        table.popFromWorkStack();

        table.popFromSecondaryWorkStack(bijection.size());
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public FunctionToFunctionMap splitBdd(int bddFunction, BitSet splitVariables) {
        assert bdd.isValidFunction(bddFunction);
        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        SplitBijection residuals = new SplitBijection(bdd.table());
        int mtbddG = splitBdd(bddFunction, splitVariables, residuals);
        bdd.table().popFromSecondaryWorkStack(residuals.size());
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();

        BitSet indices = new BitSet();
        indices.set(0, residuals.size());
        return new FunctionToFunctionMap() {
            @Override
            public int function() {
                return mtbddG;
            }

            @Override
            public int functionFor(int value) {
                return residuals.getFunction(value);
            }

            @Override
            public BitSet codomain() {
                return indices;
            }
        };
    }

    // splitBdd with the residuals relabeled, as splitRelabeled does for split.
    int splitBddRelabeled(int bddFunction, BitSet splitVariables, IntUnaryOperator relabeler) {
        assert bdd.isValidFunction(bddFunction);
        assert accessGuard.acquire();
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();

        SplitBijection residuals = new SplitBijection(bdd.table());
        int mtbddG = table.pushToWorkStack(splitBdd(bddFunction, splitVariables, residuals));
        // As in splitRelabeled: once per distinct residual, up front.
        int[] relabeledResiduals = new int[residuals.size()];
        for (int index = 0; index < relabeledResiduals.length; index++) {
            relabeledResiduals[index] = relabeler.applyAsInt(residuals.getFunction(index));
        }
        IntUnaryOperator combined = value -> relabeledResiduals[value];
        cache.initMap(combined);
        int result = computeMap(mtbddG, bdd.trueFunction(), combined, cache.mapCache(), cache.mapSimplifyCache());
        table.popFromWorkStack();

        bdd.table().popFromSecondaryWorkStack(residuals.size());
        assert table.workStacksEmpty() && bdd.table().workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    // The residuals are BDD functions, interned on the BDD's secondary work stack for the whole call.
    private int splitBdd(int bddFunction, BitSet splitVariables, SplitBijection residuals) {
        cache.initSplitBdd();
        bdd.table().pushToWorkStack(bddFunction);
        int result = splitBddRecursive(bddFunction, splitVariables, bdd.maxLevel(splitVariables), residuals);
        bdd.table().popFromWorkStack();
        return result;
    }

    private int splitBddRecursive(int bddFunction, BitSet splitVariables, int maxSplitLevel, SplitBijection residuals) {
        int level = bdd.decisionLevelOrMax(bddFunction);
        if (level > maxSplitLevel) {
            return of(residuals.intern(bddFunction));
        }

        int lookup = cache.lookupSplitBdd(bddFunction);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int low = table.pushToWorkStack(
                splitBddRecursive(bdd.low(bddFunction), splitVariables, maxSplitLevel, residuals));
        int high = table.pushToWorkStack(
                splitBddRecursive(bdd.high(bddFunction), splitVariables, maxSplitLevel, residuals));
        int result = splitVariables.get(bdd.decisionVariable(bddFunction))
                ? makeFunction(level, low, high)
                : splitBddCombineRecursive(low, high, level, residuals);
        table.popFromWorkStack(2);
        cache.putSplitBdd(hash, bddFunction, result);
        return result;
    }

    // splitCombineRecursive's shape, building the residual on the BDD side.
    private int splitBddCombineRecursive(int lowFragment, int highFragment, int level, SplitBijection residuals) {
        if (lowFragment == highFragment) {
            return lowFragment;
        }

        int lookup = cache.lookupSplitBddCombine(lowFragment, highFragment, level);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int result;
        if (isConstant(lowFragment) && isConstant(highFragment)) {
            int lowResidual = residuals.getFunction(constantFunctionToValue(lowFragment));
            int highResidual = residuals.getFunction(constantFunctionToValue(highFragment));
            result = of(residuals.intern(bdd.makeFunction(level, lowResidual, highResidual)));
        } else {
            int lowLevel = decisionLevelOrMax(lowFragment);
            int highLevel = decisionLevelOrMax(highFragment);
            int splitLevel = Math.min(lowLevel, highLevel);

            int low = table.pushToWorkStack(splitBddCombineRecursive(
                    lowIf(lowFragment, lowLevel == splitLevel),
                    lowIf(highFragment, highLevel == splitLevel),
                    level,
                    residuals));
            int high = table.pushToWorkStack(splitBddCombineRecursive(
                    highIf(lowFragment, lowLevel == splitLevel),
                    highIf(highFragment, highLevel == splitLevel),
                    level,
                    residuals));
            result = makeFunction(splitLevel, low, high);
            table.popFromWorkStack(2);
        }
        cache.putSplitBddCombine(hash, lowFragment, highFragment, level, result);
        return result;
    }

    private int splitRecursive(int mtbddNode, BitSet splitVariables, int maxSplitLevel, SplitBijection bijection) {
        if (isConstant(mtbddNode)) {
            return of(bijection.intern(mtbddNode));
        }

        int nodeVariable = decisionVariable(mtbddNode);
        int level = levelOfVariable(nodeVariable);
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
            int lowLevel = decisionLevelOrMax(lowFragment);
            int highLevel = decisionLevelOrMax(highFragment);
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

        // TODO Measure whether caching here pays for the copy this forces
        // The operand array is rewritten in place below, so the cache needs one of its own.
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
        statistics.put("mtbdd_value_triggered_collections", valueTriggeredCollectionCount);
        assert accessGuard.release();
        return Util.prefixStatistics(bdd.configuration().name(), statistics);
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

    /** A caller's support, as the levels the walk works in. */
    private static BitSet levelsOf(MtBddImpl mtbdd, BitSet variables) {
        BitSet levels = new BitSet(mtbdd.numberOfVariables());
        BitSets.map(variables, levels, mtbdd::levelOfVariable);
        return levels;
    }

    /**
     * Walks the paths of a function together with the terminal each one reaches.
     *
     * <p>Hands out its own working path, so nothing is copied per element - see {@link Cursor}. The one
     * exception is a diagram that has been reordered, where the walk is by level and the caller wants
     * variables, and a translation buffer is unavoidable.
     */
    private static final class PathCursor implements ValuedCursor<Cube> {
        private final MtBddImpl mtbdd;
        private final PathWalk path;
        /** Only on a reordered diagram, where the walk is by level and the caller wants variables. */
        private final @Nullable Cube translated;
        /** What {@link #current()} hands out: the translation buffer, or the walk's own sets wrapped. */
        private final Cube current;

        private boolean valid;

        PathCursor(MtBddImpl mtbdd, int function) {
            int variableCount = mtbdd.numberOfVariables();
            this.mtbdd = mtbdd;
            this.path = new PathWalk(mtbdd, function, null);
            this.valid = path.onPath();
            this.translated =
                    mtbdd.isReordered() ? new Cube(new BitSet(variableCount), new BitSet(variableCount)) : null;
            this.current = translated == null ? new Cube(path.levelAssignment(), path.pathSupportLevels()) : translated;
            if (valid) {
                translate();
            }
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public Cube current() {
            assert valid; // current() is only defined while the cursor is valid
            return current;
        }

        @Override
        public int value() {
            assert valid; // value() is only defined while the cursor is valid
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
            boolean translating = mtbdd.isReordered();
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
            assert valid; // current() is only defined while the cursor is valid
            return translated == null ? path.levelAssignment() : translated;
        }

        @Override
        public int value() {
            assert valid; // value() is only defined while the cursor is valid
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
        protected int levelOfVariable(int variable) {
            // The MTBDD shares its companion BDD's variable order; that is what lets a cross-table
            // recursion expand on a single topmost variable.
            return mtbdd.bdd().levelOfVariable(variable);
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
        protected BitSet clearUnreferencedLeaves() {
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
        protected boolean checkOwner() {
            return mtbdd.check();
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

    @Override
    public boolean check() {
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
