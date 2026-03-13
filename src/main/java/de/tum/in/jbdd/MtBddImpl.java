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
import static java.math.BigInteger.*;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

@SuppressWarnings("PMD")
abstract class MtBddImpl implements MtBdd, NodeBasedDecisionDiagram {
    private static final Logger logger = Logger.getLogger(MtBddImpl.class.getName());

    private final BddImpl bdd;
    private final MtBddTable table;
    private int[] valueReferenceCounts;
    // Should be sparse bit set
    private final BitSet allocatedValues = new BitSet();
    private int numberOfVariables = 0;

    MtBddImpl(BddImpl bdd) {
        this.bdd = bdd;
        this.table = new MtBddTable(this, 1024);
        this.valueReferenceCounts = new int[1024];
    }

    @Override
    public Bdd bdd() {
        return bdd;
    }

    private static int valueToConstant(int value) {
        assert value >= 0;
        return -value - 1;
    }

    private static int constantToValue(int function) {
        assert function < 0;
        return -function - 1;
    }

    // Reference counting

    @Override
    public int reference(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            int value = constantToValue(function);
            if (valueReferenceCounts[value] < Integer.MAX_VALUE) {
                valueReferenceCounts[value] += 1;
            }
        } else {
            table.referenceNode(function);
        }
        return function;
    }

    @Override
    public int dereference(int function) {
        assert isValidFunction(function);
        if (isConstant(function)) {
            int value = constantToValue(function);
            assert valueReferenceCounts[value] > 0;
            if (valueReferenceCounts[value] < Integer.MAX_VALUE) {
                valueReferenceCounts[value] -= 1;
            }
        } else {
            table.dereferenceNode(function);
        }
        return function;
    }

    @Override
    public int nodeReferenceCount(int node) {
        if (isConstant(node)) {
            int value = constantToValue(node);
            int referenceCount = valueReferenceCounts[value];
            return referenceCount == Integer.MAX_VALUE ? -1 : referenceCount;
        }
        return table.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        if (isConstant(node)) {
            return valueReferenceCounts[constantToValue(node)] == Integer.MAX_VALUE;
        }
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
        return function;
    }

    @Override
    public int decisionVariable(int function) {
        assert isValidNonConstantFunction(function);
        return table.variable(function);
    }

    public boolean isValidFunction(int function) {
        return function < 0 && isValidConstant(function) || table.isValidDecisionNode(function);
    }

    private boolean isValidNonConstantFunction(int function) {
        return function > 0 && table.isValidDecisionNode(function);
    }

    private boolean isValidConstant(int function) {
        return allocatedValues.get(constantToValue(function));
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
    public int evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = function;
        while (!isConstant(currentNode)) {
            assert table.isValidDecisionNode(currentNode);
            currentNode = assignment[decisionVariable(currentNode)] ? table.high(currentNode) : table.low(currentNode);
        }
        return constantToValue(currentNode);
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
        return constantToValue(currentNode);
    }

    @Override
    public int of(int value) {
        assert value >= 0;
        return valueToConstant(value);
    }

    @Override
    public int of(int variable, int trueChild, int falseChild) {
        return table.makeNode(variable, trueChild, falseChild);
    }

    @Override
    public Optional<BitSet> anyAssignment(int function, IntPredicate values) {
        assert isValidFunction(function);

        BitSet assigment = new BitSet(numberOfVariables);
        boolean found = anyAssigmentRecursive(function, values, assigment);
        return found ? Optional.of(assigment) : Optional.empty();
    }

    private boolean anyAssigmentRecursive(int function, IntPredicate values, BitSet assignment) {
        if (isConstant(function)) {
            if (values.test(constantToValue(function))) {
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
    public BigInteger countAssignments(int function, IntPredicate values) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return values.test(constantToValue(function)) ? TWO.pow(numberOfVariables) : ZERO;
        }

        int variable = decisionVariable(function);
        BigInteger satisfyingBelow = countSatisfyingAssignmentsRecursive(function, values);
        return TWO.pow(variable).multiply(satisfyingBelow);
    }

    @Override
    public BigInteger countAssignments(int function, IntPredicate values, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        return countAssignments(function, values).divide(TWO.pow(numberOfVariables - support.cardinality()));
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int node, IntPredicate values) {
        assert isValidFunction(node);

        int nodeVar = table.variable(node);

        /*
        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return lookingFor
                ? cacheLookup
                : TWO.pow(numberOfVariables - nodeVar).subtract(cacheLookup);
        }
        int hash = cache.lookupHash();
        */

        BigInteger lowCount = doCountSatisfyingAssignments(table.low(node), nodeVar, values);
        BigInteger highCount = doCountSatisfyingAssignments(table.high(node), nodeVar, values);
        BigInteger result = lowCount.add(highCount);
        /* cache.putSatisfaction(
        hash,
        node,
        lookingFor ? result : TWO.pow(numberOfVariables - nodeVar).subtract(result)); */
        return result;
    }

    private BigInteger doCountSatisfyingAssignments(int function, int previousVar, IntPredicate values) {
        if (isConstant(function)) {
            return values.test(constantToValue(function)) ? TWO.pow(numberOfVariables - previousVar - 1) : ZERO;
        }
        BigInteger multiplier = TWO.pow(decisionVariable(function) - previousVar - 1);
        return multiplier.multiply(countSatisfyingAssignmentsRecursive(function, values));
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, IntPredicate values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<BitSet> assignmentIterator(int function, IntPredicate values, BitSet support) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Product cartesianProduct(int[] functions) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forEachPath(int function, BiConsumer<BinaryPath, Integer> action) {
        throw new UnsupportedOperationException();
    }

    String format(int reference) {
        return isConstant(reference)
                ? String.format("V%d", constantToValue(reference))
                : String.format("N%d", reference);
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
            return mtbdd.isConstant(node) && !markedValues.get(constantToValue(node));
        }

        private boolean isMarkedConstant(int node) {
            return mtbdd.isConstant(node) && markedValues.get(constantToValue(node));
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int low = low(node);
            int high = high(node);
            return (isUnmarkedConstant(low) || doIsNoneMarkedBelow(low))
                    && (isUnmarkedConstant(high) || doIsNoneMarkedBelow(high));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node) {
            int low = low(node);
            int high = high(node);
            return (isMarkedConstant(low) || doIsAllMarkedBelow(low))
                    && (isMarkedConstant(high) || doIsAllMarkedBelow(high));
        }

        @Override
        protected void markLeafNodeIfManaged(int node, boolean mark) {
            assert mtbdd.isValidConstant(node);
            markedValues.set(node, mark);
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark) {
            int low = low(node);
            int high = high(node);
            int sum = 0;
            if (mtbdd.isConstant(low)) {
                markedValues.set(constantToValue(low), mark);
            } else {
                sum += doSetMarkBelow(low, mark);
            }
            if (mtbdd.isConstant(high)) {
                markedValues.set(constantToValue(high), mark);
            } else {
                sum += doSetMarkBelow(high, mark);
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
        protected boolean anyManagedLeafMarked() {
            return !markedValues.isEmpty();
        }

        @Override
        protected void unmarkAllManagedLeafs() {
            markedValues.clear();
        }

        @Override
        protected boolean isLeafNodeMarkedOrUnmanaged(int leaf) {
            return markedValues.get(constantToValue(leaf));
        }

        @Override
        protected boolean isLeafUnmarkedOrUnmanaged(int leaf) {
            return !markedValues.get(constantToValue(leaf));
        }

        @Override
        public String format(int pointer) {
            return mtbdd.format(pointer);
        }
    }

    private void clearCacheAfterGC(int reclaimedNodes) {}

    private boolean check() {
        return true;
    }
}
