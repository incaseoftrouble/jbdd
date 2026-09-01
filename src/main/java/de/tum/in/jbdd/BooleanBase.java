/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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
import java.util.BitSet;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("AssertWithSideEffects")
public abstract class BooleanBase<S, P> implements BooleanTerminalDecisionDiagram<S, P>, NodeBasedDd {
    private static final BitSet NO_VALUES = new BitSet(0);

    static final BigInteger TWO = BigInteger.ONE.add(BigInteger.ONE);
    static final int[] EMPTY_INT_ARRAY = new int[0];
    static final int TRUE = Integer.MAX_VALUE;
    static final int FALSE = complement(TRUE);

    private final NodeTableObserverGroup<NodeTableObserver> observers = new NodeTableObserverGroup<>();
    private final ProtectionTracker protectionTracker = new ProtectionTracker();
    final ConcurrentAccessGuard accessGuard = new ConcurrentAccessGuard();

    BooleanBase() {
        observers.registerStrongly(protectionTracker);
        // Strongly: this hook is owned by the diagram, nothing else holds it - see
        // NodeLifecycleObserverGroup#registerStrongly.
        observers.registerStrongly(new NodeTableObserver() {
            @Override
            public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
                cache().onBddNodesInvalidated(reclaimedNodes);
            }

            @Override
            public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
                cache().tableSizeChanged(invalidatedNodes);
            }

            @Override
            public void levelsSwapped(DecisionDiagram origin, int level) {
                cache().levelsSwapped();
            }

            /* Nothing: an insertion preserves every level comparison, so no cached value goes stale by it.
             * The variable count does change, which is what variablesChanged already covers. */
        });
    }

    abstract BooleanCache cache();

    abstract NodeTable table();

    abstract BddConfiguration configuration();

    int tableSize() {
        return table().size();
    }

    boolean check() {
        return table().check();
    }

    @SuppressWarnings({"ClassReferencesSubclass", "InstanceofThis"})
    @Override
    public Map<String, Object> statistics() {
        assert accessGuard.acquire();
        Map<String, Object> statistics = Stream.of(
                        table().statistics((this instanceof BddImpl) ? "bdd_" : "mdd_").entrySet().stream(),
                        cache().statistics().entrySet().stream(),
                        ownStatistics().entrySet().stream())
                .flatMap(stream -> stream)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        assert accessGuard.release();
        return DecisionDiagram.prefixStatistics(configuration().name(), statistics);
    }

    /** Whatever the concrete diagram wants to report beyond its table's and its caches'. */
    Map<String, Object> ownStatistics() {
        return Map.of();
    }

    public void invalidateCache() {
        // Mainly available for testing
        cache().invalidate();
    }

    ProtectionTracker protectionTracker() {
        return protectionTracker;
    }

    void registerObserver(NodeTableObserver observer) {
        observers.register(observer);
    }

    /** Registers an observer owned by this diagram, see {@link NodeTableObserverGroup#registerStrongly}. */
    void registerOwnedObserver(NodeTableObserver observer) {
        observers.registerStrongly(observer);
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

    void notifyAfterGc(int reclaimedNodes) {
        observers.dispatch(observer -> observer.afterGc(this, reclaimedNodes, NO_VALUES));
    }

    void notifyAfterTableGrow(int reclaimedNodes) {
        observers.dispatch(observer -> observer.afterTableGrowth(this, reclaimedNodes, NO_VALUES));
    }

    @Override
    public int gc() {
        assert accessGuard.acquire();
        notifyBeforeGc();
        table().markAllReferencedNodes();
        int reclaimedNodes = table().reclaimUnmarkedNodes();
        assert table().isNoneMarked();
        notifyAfterGc(reclaimedNodes);
        assert accessGuard.release();
        return reclaimedNodes;
    }

    boolean isValidNonConstantFunction(int function) {
        return table().isValidDecisionNode(positive(function));
    }

    // Reference counting

    @Override
    public int reference(int function) {
        assert isValidFunction(function);
        int positive = positive(function);
        if (positive == TRUE) {
            return function;
        }
        assert accessGuard.acquire();
        table().referenceNode(positive);
        assert accessGuard.release();
        return function;
    }

    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        int positive = positive(function);
        if (positive == TRUE) {
            return function;
        }
        assert accessGuard.acquire();
        table().dereferenceNode(positive);
        assert accessGuard.release();
        return function;
    }

    @Override
    public boolean isUnmanaged(int function) {
        return isSaturatedNode(nodeFor(function));
    }

    @Override
    public int updateWith(int result, int input) {
        int resultNode = positive(result);
        int node1 = positive(input);
        if (resultNode == node1) {
            return result;
        }
        assert accessGuard.acquire();
        if (resultNode != TRUE) {
            table().referenceNode(resultNode);
        }
        if (node1 != TRUE) {
            table().dereferenceNode(node1);
        }
        assert accessGuard.release();
        return result;
    }

    @Override
    public int consume(int result, int input1, int input2) {
        // result + 1, input1 - 1, input2 - 1
        int resultNode = positive(result);
        int node1 = positive(input1);
        int node2 = positive(input2);
        assert accessGuard.acquire();
        if (resultNode == node1) {
            if (node2 != TRUE) {
                table().dereferenceNode(node2);
            }
        } else {
            if (resultNode != node2) {
                if (resultNode != TRUE) {
                    table().referenceNode(resultNode);
                }
                if (node2 != TRUE) {
                    table().dereferenceNode(node2);
                }
            }
            if (node1 != TRUE) {
                table().dereferenceNode(node1);
            }
        }
        assert accessGuard.release();
        return result;
    }

    @Override
    public int nodeReferenceCount(int node) {
        return table().nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return node == TRUE || table().isSaturatedNode(node);
    }

    @Override
    public void forEachSupportVariable(int function, IntConsumer action) {
        assert accessGuard.acquire();
        table().forEachVariable(function, action);
        assert accessGuard.release();
    }

    @Override
    public void forEachSupportVariableFiltered(int function, BitSet filter, IntConsumer action) {
        assert accessGuard.acquire();
        table().forEachVariable(function, filter, action);
        assert accessGuard.release();
    }

    @Override
    public int referencedNodeCount() {
        return table().referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        assert accessGuard.acquire();
        int result = table().nodeCount();
        assert accessGuard.release();
        return result;
    }

    // Nodes

    @Override
    public int trueFunction() {
        return TRUE;
    }

    @Override
    public int falseFunction() {
        return FALSE;
    }

    @Override
    public boolean isValidFunction(int function) {
        int positive = positive(function);
        return positive == TRUE || table().isValidDecisionNode(positive);
    }

    @Override
    public int nodeFor(int function) {
        assert isValidFunction(function);
        return positive(function);
    }

    @Override
    public int decisionVariable(int function) {
        assert isValidNonConstantFunction(function);
        return table().variable(positive(function));
    }

    @Override
    public int placeholder() {
        return NodeTable.PLACEHOLDER;
    }

    @Override
    public boolean isConstant(int function) {
        return function == TRUE || function == FALSE;
    }

    boolean isPositive(int function) {
        return function > 0;
    }

    @Override
    public int size(int function) {
        assert accessGuard.acquire();
        int result = table().nodeCountBelow(nodeFor(function));
        assert accessGuard.release();
        return result;
    }

    @Override
    public int andNot(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return and(function1, complement(function2));
    }

    @Override
    public int andNotSimplify(int function1, int function2, int domain) {
        assert isValidFunction(function1) && isValidFunction(function2) && isValidFunction(domain);
        return andSimplify(function1, complement(function2), domain);
    }

    @Override
    public int equivalence(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        return complement(xor(function1, function2));
    }

    @Override
    public int equivalenceSimplify(int function1, int function2, int domain) {
        return complement(xorSimplify(function1, function2, domain));
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        return complement(exists(complement(function), quantifiedVariables));
    }

    @Override
    public int implication(int function1, int function2) {
        return complement(and(function1, complement(function2)));
    }

    @Override
    public int implicationSimplify(int function1, int function2, int domain) {
        return complement(andSimplify(function1, complement(function2), domain));
    }

    @Override
    public int not(int function) {
        assert isValidFunction(function);
        return complement(function);
    }

    @Override
    public int notAnd(int function1, int function2) {
        return complement(and(function1, function2));
    }

    @Override
    public int notAndSimplify(int function1, int function2, int domain) {
        return complement(andSimplify(function1, function2, domain));
    }

    @Override
    public int or(int function1, int function2) {
        return complement(and(complement(function1), complement(function2)));
    }

    @Override
    public int orSimplify(int function1, int function2, int domain) {
        return complement(andSimplify(complement(function1), complement(function2), domain));
    }

    String format(int reference) {
        if (reference == TRUE) {
            return "TRUE";
        }
        if (reference == FALSE) {
            return "FALSE";
        }
        return String.format("%s%d", isPositive(reference) ? "" : "!", positive(reference));
    }

    static int positive(int function) {
        return function < 0 ? -function : function;
    }

    static int complement(int function) {
        return -function;
    }

    static int complementIf(int function, boolean condition) {
        return condition ? complement(function) : function;
    }

    static boolean isComplementFunction(int function) {
        return function < 0;
    }

    static boolean isTrue(int function, boolean lookingFor) {
        return lookingFor ? (function == TRUE) : (function == FALSE);
    }

    static boolean isFalse(int function, boolean lookingFor) {
        return lookingFor ? (function == FALSE) : (function == TRUE);
    }
}
