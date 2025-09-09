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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

@SuppressWarnings("PMD")
abstract class MtBddImpl<V> implements MtBdd<V>, NodeBasedDecisionDiagram {
    private static final Logger logger = Logger.getLogger(MtBddImpl.class.getName());

    private final BddImpl bdd;
    private final Class<V> valueType;
    private final List<V> values;
    private final Map<V, Integer> valueToTerminal = new HashMap<>();
    private final Map<Integer, V> terminalToValue = new HashMap<>();

    private final MtBddTable<V> table;

    private int numberOfVariables = 0;

    MtBddImpl(BddImpl bdd, Class<V> valueType) {
        this.bdd = bdd;
        this.valueType = valueType;
        this.values = new ArrayList<>();

        this.table = new MtBddTable<>(this, 1024);
    }

    @Override
    public Bdd bdd() {
        return bdd;
    }

    @Override
    public Class<V> valueType() {
        return valueType;
    }

    // Reference counting

    @Override
    public int reference(int function) {
        assert isValidFunction(function);
        table.referenceNode(function);
        return function;
    }

    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        table.dereferenceNode(function);
        return function;
    }

    @Override
    public int updateWith(int result, int input) {
        if (result != input) {
            table.referenceNode(result);
            table.dereferenceNode(input);
        }
        return result;
    }

    @Override
    public int consume(int result, int input1, int input2) {
        // result + 1, input1 - 1, input2 - 1
        if (result == input1) {
            table.dereferenceNode(input2);
        } else {
            if (result != input2) {
                table.referenceNode(result);
                table.dereferenceNode(input2);
            }
            table.dereferenceNode(input1);
        }
        return result;
    }

    @Override
    public int nodeReferenceCount(int node) {
        return table.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return table.isSaturatedNode(node);
    }

    @Override
    public void forEachSupportVariable(int function, IntConsumer action) {
        table.forEachVariable(function, action);
    }

    @Override
    public void forEachSupportFiltered(int function, BitSet filter, IntConsumer action) {
        table.forEachVariable(function, filter, action);
    }

    @Override
    public int referencedNodeCount() {
        return table.referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return table.nodeCount();
    }

    @Override
    public int nodeFor(int function) {
        return isConstant(function) ? NodeTable.PLACEHOLDER : function;
    }

    @Override
    public int decisionVariable(int function) {
        assert isValidNonConstantFunction(function);
        return table.variable(function);
    }

    public boolean isValidFunction(int function) {
        return function < 0 ? terminalToValue.containsKey(function) : table.isValidNode(function);
    }

    private boolean isValidNonConstantFunction(int function) {
        return function > 0 && table.isValidNode(function);
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
    public V evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidNode(currentNode);
            currentNode = assignment[decisionVariable(currentNode)] ? table.high(currentNode) : table.low(currentNode);
        }
        return terminalToValue.get(currentNode);
    }

    @Override
    public V evaluate(int function, BitSet assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidNode(currentNode);
            currentNode =
                    assignment.get(decisionVariable(currentNode)) ? table.high(currentNode) : table.low(currentNode);
        }
        return terminalToValue.get(currentNode);
    }

    @Override
    public int of(V value) {
        return valueToTerminal.computeIfAbsent(value, k -> {
            int node = -1;
            while (terminalToValue.containsKey(node)) {
                node -= 1;
            }
            terminalToValue.put(node, k);
            return node;
        });
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        return table.makeNode(variable, trueChild, falseChild);
    }

    @Override
    public Optional<BitSet> anyAssignment(int function, Predicate<? super V> values) {
        assert isValidFunction(function);

        BitSet assigment = new BitSet(numberOfVariables);
        boolean found = anyAssigmentRecursive(function, values, assigment);
        return found ? Optional.of(assigment) : Optional.empty();
    }

    private boolean anyAssigmentRecursive(int function, Predicate<? super V> values, BitSet assignment) {
        if (isConstant(function)) {
            if (values.test(terminalToValue.get(function))) {
                return true;
            }
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
    public BigInteger countAssignments(int function, Predicate<? super V> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BigInteger countAssignments(int function, Predicate<? super V> values, BitSet support) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, Predicate<? super V> values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, Predicate<? super V> values, BitSet support) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forEachPath(int function, BiConsumer<BinaryPath, ? super V> action) {
        throw new UnsupportedOperationException();
    }

    private static final class MtBddTable<V> extends NodeTable.Binary {
        private final MtBddImpl<V> mtbdd;
        private Map<V, Integer> valueToTerminal;
        // <--REF--><MARK>
        private int[] terminalData;

        MtBddTable(MtBddImpl<V> mtbdd, int initialSize) {
            super(initialSize);
            this.mtbdd = mtbdd;
        }

        @Override
        public boolean isConstantPointer(int pointer) {
            return mtbdd.isConstant(pointer);
        }

        @Override
        public boolean isValidPointer(int pointer) {
            return mtbdd.isValidFunction(pointer);
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int low = low(node);
            int high = high(node);
            return (mtbdd.isConstant(node) || doIsNoneMarkedBelow(low))
                    && (mtbdd.isConstant(high) || doIsNoneMarkedBelow(high));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node) {
            int low = low(node);
            int high = high(node);
            return (mtbdd.isConstant(node) || doIsAllMarkedBelow(low))
                    && (mtbdd.isConstant(high) || doIsAllMarkedBelow(high));
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark) {
            int low = low(node);
            int high = high(node);
            return (mtbdd.isConstant(node) ? 0 : doSetMarkBelow(low, mark))
                    + (mtbdd.isConstant(high) ? 0 : doSetMarkBelow(high, mark));
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
        public int nodeFor(int pointer) {
            return pointer;
        }

        @Override
        public boolean ensureCapacity() {
            if (freeNodeCount() > size() / 4) {
                return false;
            }

            NodeTable table = mtbdd.table;
            int currentSize = table.size();
            int approximateDeadNodeCount = table.approximateDeadNodeCount();
            if ( // mtbdd.configuration.useGarbageCollection() &&
            approximateDeadNodeCount > 0) {
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
            table.grow((int) (currentSize * 2.0)); // mtbdd.configuration.growthFactor()));
            // mtbdd.cache.tableSizeChanged();
            assert mtbdd.check();
            return true;
        }

        @Override
        public String format(int pointer) {
            throw new UnsupportedOperationException();
        }
    }

    private void clearCacheAfterGC(int reclaimedNodes) {}

    private boolean check() {
        return true;
    }
}
